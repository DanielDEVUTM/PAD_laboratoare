"""
subscriber-ui/app.py
Aplicatie INDEPENDENTA pentru rolul de Subscriber, cu interfata web proprie.

Fiecare proces = O SINGURA instanta de Subscriber. Pentru mai multi
subscriberi, rulezi acest script de mai multe ori (in terminale diferite,
eventual pe laptopuri diferite) - fiecare proces isi genereaza automat un
ID unic si isi alege automat un port liber, deci nu se incurca intre ele.

Rulata separat, pe laptopul persoanei responsabile de Subscriber, conectata
la Broker (care poate fi pe alt laptop, pe aceeasi retea/hotspot) prin IP.
Nu are nicio dependenta de broker-ui/ sau publisher-ui/ - e o aplicatie
de sine statatoare, exact cum ar rula pe un calculator diferit.

Configurare (variabile de mediu, cu valori implicite pentru testare locala):
  BROKER_HOST=192.168.1.23   (IP-ul laptopului cu Broker-ul; implicit 127.0.0.1)
  BROKER_TCP_PORT=5050       (implicit 5050)
  BROKER_MODE=grpc           (transport: tcp - implicit - sau grpc)
  BROKER_GRPC_PORT=5051      (implicit 5051, folosit doar cu BROKER_MODE=grpc)
  SUBSCRIBER_UI_PORT=8002    (port de pornit cautarea; daca e ocupat, se alege
                              automat urmatorul liber - 8003, 8004, ...)

Rulare (o instanta):
  BROKER_HOST=192.168.1.23 python subscriber-ui/app.py

Rulare (mai multe instante, acelasi laptop, terminale diferite):
  BROKER_HOST=192.168.1.23 python subscriber-ui/app.py   # instanta 1 -> 8002
  BROKER_HOST=192.168.1.23 python subscriber-ui/app.py   # instanta 2 -> 8003 (auto)
"""

import json
import os
import socket
import sys
import threading
import time
import uuid
from typing import Any, Dict, Optional

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS
import grpc

# Stub-urile generate din broker.proto sunt in directorul publisher/
sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "publisher"))
import broker_pb2
import broker_pb2_grpc

BROKER_HOST = os.environ.get("BROKER_HOST", "127.0.0.1")
BROKER_TCP_PORT = int(os.environ.get("BROKER_TCP_PORT", "5050"))
BROKER_GRPC_PORT = int(os.environ.get("BROKER_GRPC_PORT", "5051"))
BROKER_MODE = os.environ.get("BROKER_MODE", "tcp").lower()
BROKER_PORT = BROKER_GRPC_PORT if BROKER_MODE == "grpc" else BROKER_TCP_PORT
UI_PORT_START = int(os.environ.get("SUBSCRIBER_UI_PORT", "8002"))
MAX_MESSAGES = 300
RECONNECT_DELAYS = (1, 2, 4)

SUBSCRIBER_ID = uuid.uuid4().hex[:8]


def _find_free_port(start_port: int, host: str = "0.0.0.0", max_tries: int = 50) -> int:
    # Nu folosim SO_REUSEADDR aici: pe Windows, acel flag permite legarea la
    # un port deja ascultat de alt proces (spre deosebire de Linux), ceea ce
    # ar face testul sa treaca fals-pozitiv si doua instante ar porni pe
    # acelasi port.
    port = start_port
    for _ in range(max_tries):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((host, port))
                return port
            except OSError:
                port += 1
    raise RuntimeError(f"Niciun port liber intre {start_port} si {start_port + max_tries - 1}")


UI_PORT = _find_free_port(UI_PORT_START)

app = Flask(__name__, static_folder=os.path.dirname(os.path.abspath(__file__)), static_url_path="")
CORS(app)

_lock = threading.Lock()
_instance: Dict[str, Any] = {
    "topic": None,
    "subscriberId": SUBSCRIBER_ID,
    "status": "disconnected",
    "stop_event": None,
    "socket": None,
    "feed": [],
    "counter": 0,
    "createdAt": None,
    "generation": 0,
}


def _instance_snapshot() -> Dict[str, Any]:
    with _lock:
        return {
            "id": SUBSCRIBER_ID,
            "topic": _instance["topic"],
            "subscriberId": _instance["subscriberId"],
            "status": _instance["status"],
            "messageCount": _instance["counter"],
            "createdAt": _instance["createdAt"],
        }


def _close_connection(conn: Any) -> None:
    # conn e fie un socket TCP, fie un apel gRPC de tip stream (are cancel())
    if conn is None:
        return
    if hasattr(conn, "cancel"):
        conn.cancel()
        return
    try:
        conn.shutdown(socket.SHUT_RDWR)
    except Exception:
        pass
    try:
        conn.close()
    except Exception:
        pass


def _append_message(generation: int, message: Dict[str, Any]) -> bool:
    with _lock:
        if _instance["generation"] != generation:
            return False
        _instance["counter"] += 1
        _instance["feed"].append({
            "index": _instance["counter"],
            "id": message.get("id"),
            "payload": message.get("payload"),
            "timestamp": message.get("timestamp"),
            "receivedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        })
        if len(_instance["feed"]) > MAX_MESSAGES:
            del _instance["feed"][: len(_instance["feed"]) - MAX_MESSAGES]
    return True


def _grpc_subscriber_worker(topic: str, generation: int) -> None:
    failed_attempts = 0

    while True:
        with _lock:
            if _instance["generation"] != generation:
                return
            stop_event = _instance["stop_event"]

        if stop_event.is_set():
            return

        channel = grpc.insecure_channel(f"{BROKER_HOST}:{BROKER_GRPC_PORT}")
        try:
            grpc.channel_ready_future(channel).result(timeout=5.0)
            stub = broker_pb2_grpc.BrokerStub(channel)
            # Server streaming: o singura cerere, Broker-ul impinge mesajele pe stream
            call = stub.Subscribe(broker_pb2.SubRequest(topic=topic))

            with _lock:
                if _instance["generation"] != generation or stop_event.is_set():
                    call.cancel()
                    return
                _instance["socket"] = call
                _instance["status"] = "connected"
            failed_attempts = 0

            for msg in call:
                try:
                    payload = json.loads(msg.payload)
                except (json.JSONDecodeError, TypeError):
                    payload = msg.payload
                if not _append_message(generation, {"id": msg.id, "payload": payload, "timestamp": msg.timestamp}):
                    call.cancel()
                    return
        except Exception:
            pass
        finally:
            with _lock:
                if _instance["generation"] == generation:
                    _instance["socket"] = None
                    if not stop_event.is_set():
                        _instance["status"] = "reconnecting"
            channel.close()

        if stop_event.is_set():
            return

        delay = RECONNECT_DELAYS[min(failed_attempts, len(RECONNECT_DELAYS) - 1)]
        failed_attempts += 1
        for _ in range(delay * 10):
            if stop_event.is_set():
                return
            time.sleep(0.1)


def _subscriber_worker(topic: str, generation: int) -> None:
    buffer = ""
    failed_attempts = 0

    while True:
        with _lock:
            if _instance["generation"] != generation:
                return
            stop_event = _instance["stop_event"]
            subscriber_id = _instance["subscriberId"]

        if stop_event.is_set():
            return

        sock: Optional[socket.socket] = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5.0)
            sock.connect((BROKER_HOST, BROKER_TCP_PORT))
            handshake = {"role": "subscriber", "topic": topic, "subscriberId": subscriber_id}
            sock.sendall((json.dumps(handshake) + "\n").encode("utf-8"))
            sock.settimeout(1.0)

            with _lock:
                if _instance["generation"] != generation or stop_event.is_set():
                    sock.close()
                    return
                _instance["socket"] = sock
                _instance["status"] = "connected"
            failed_attempts = 0

            while not stop_event.is_set():
                try:
                    chunk = sock.recv(4096)
                except socket.timeout:
                    continue
                if not chunk:
                    raise ConnectionError("Broker a inchis conexiunea")
                buffer += chunk.decode("utf-8", errors="replace")
                while "\n" in buffer:
                    line, buffer = buffer.split("\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        message = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if not _append_message(generation, message):
                        return
        except Exception:
            pass
        finally:
            with _lock:
                if _instance["generation"] == generation:
                    _instance["socket"] = None
                    if not stop_event.is_set():
                        _instance["status"] = "reconnecting"
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass

        if stop_event.is_set():
            return

        delay = RECONNECT_DELAYS[min(failed_attempts, len(RECONNECT_DELAYS) - 1)]
        failed_attempts += 1
        for _ in range(delay * 10):
            if stop_event.is_set():
                return
            time.sleep(0.1)


@app.route("/")
def index():
    return send_from_directory(os.path.dirname(os.path.abspath(__file__)), "index.html")


@app.route("/api/config")
def config():
    return jsonify({
        "brokerHost": BROKER_HOST,
        "brokerTcpPort": BROKER_PORT,
        "mode": BROKER_MODE,
        "instanceId": SUBSCRIBER_ID,
        "uiPort": UI_PORT,
    })


@app.route("/api/instance", methods=["GET"])
def get_instance():
    return jsonify(_instance_snapshot())


@app.route("/api/connect", methods=["POST"])
def connect():
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    name = data.get("name")
    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

    with _lock:
        old_stop_event = _instance["stop_event"]
        old_socket = _instance["socket"]
        _instance["generation"] += 1
        generation = _instance["generation"]

    if old_stop_event is not None:
        old_stop_event.set()
    _close_connection(old_socket)

    subscriber_id = name.strip() if name and name.strip() else SUBSCRIBER_ID

    with _lock:
        _instance["topic"] = topic
        _instance["subscriberId"] = subscriber_id
        _instance["status"] = "connecting"
        _instance["stop_event"] = threading.Event()
        _instance["socket"] = None
        _instance["feed"] = []
        _instance["counter"] = 0
        _instance["createdAt"] = time.strftime("%Y-%m-%d %H:%M:%S")

    worker = _grpc_subscriber_worker if BROKER_MODE == "grpc" else _subscriber_worker
    threading.Thread(target=worker, args=(topic, generation), daemon=True).start()
    return jsonify(_instance_snapshot())


@app.route("/api/disconnect", methods=["POST"])
def disconnect():
    with _lock:
        stop_event = _instance["stop_event"]
        sock = _instance["socket"]
        _instance["generation"] += 1
        _instance["topic"] = None
        _instance["status"] = "disconnected"
        _instance["socket"] = None
        _instance["stop_event"] = None

    if stop_event is not None:
        stop_event.set()
    _close_connection(sock)

    return jsonify(_instance_snapshot())


@app.route("/api/messages")
def messages():
    since = request.args.get("since", default=0, type=int)
    with _lock:
        new_items = [m for m in _instance["feed"] if m["index"] > since]
        latest = _instance["feed"][-1]["index"] if _instance["feed"] else since
        status = _instance["status"]
    return jsonify({"messages": new_items, "latest": latest, "status": status})


if __name__ == "__main__":
    print(f"Subscriber UI [{SUBSCRIBER_ID}] pornit pe http://0.0.0.0:{UI_PORT}  (Broker tinta: {BROKER_HOST}:{BROKER_PORT}, mod {BROKER_MODE.upper()})")
    app.run(host="0.0.0.0", port=UI_PORT, debug=False, threaded=True)
