"""
publisher-ui/app.py
Aplicatie INDEPENDENTA pentru rolul de Publisher, cu interfata web proprie.

Fiecare proces = O SINGURA instanta de Publisher. Pentru mai multi publisheri,
rulezi acest script de mai multe ori (in terminale diferite, eventual pe
laptopuri diferite) - fiecare proces isi genereaza automat un ID unic si
isi alege automat un port liber, deci nu se incurca intre ele.

Rulata separat, pe laptopul persoanei responsabile de Publisher, conectata
la Broker (care poate fi pe alt laptop, pe aceeasi retea/hotspot) prin IP.
Nu are nicio dependenta de broker-ui/ sau subscriber-ui/ - e o aplicatie
de sine statatoare, exact cum ar rula pe un calculator diferit.

Configurare (variabile de mediu, cu valori implicite pentru testare locala):
  BROKER_HOST=192.168.1.23   (IP-ul laptopului cu Broker-ul; implicit 127.0.0.1)
  BROKER_TCP_PORT=5050       (implicit 5050)
  BROKER_MODE=grpc           (transport: tcp - implicit - sau grpc)
  BROKER_GRPC_PORT=5051      (implicit 5051, folosit doar cu BROKER_MODE=grpc)
  PUBLISHER_UI_PORT=8001     (port de pornit cautarea; daca e ocupat, se alege
                              automat urmatorul liber - 8002, 8003, ...)

Rulare (o instanta):
  BROKER_HOST=192.168.1.23 python publisher-ui/app.py

Rulare (mai multe instante, acelasi laptop, terminale diferite):
  BROKER_HOST=192.168.1.23 python publisher-ui/app.py   # instanta 1 -> 8001
  BROKER_HOST=192.168.1.23 python publisher-ui/app.py   # instanta 2 -> 8002 (auto)
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

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "publisher"))
from publisher_client import PublisherClient
from publisher_grpc_client import PublisherGrpcClient

BROKER_HOST = os.environ.get("BROKER_HOST", "127.0.0.1")
BROKER_TCP_PORT = int(os.environ.get("BROKER_TCP_PORT", "5050"))
BROKER_GRPC_PORT = int(os.environ.get("BROKER_GRPC_PORT", "5051"))
BROKER_MODE = os.environ.get("BROKER_MODE", "tcp").lower()
BROKER_PORT = BROKER_GRPC_PORT if BROKER_MODE == "grpc" else BROKER_TCP_PORT
UI_PORT_START = int(os.environ.get("PUBLISHER_UI_PORT", "8001"))

PUBLISHER_ID = uuid.uuid4().hex[:8]


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
    "name": PUBLISHER_ID,
    "client": None,
    "status": "disconnected",
    "sentCount": 0,
    "createdAt": None,
}


def _instance_snapshot() -> Dict[str, Any]:
    with _lock:
        return {
            "id": PUBLISHER_ID,
            "topic": _instance["topic"],
            "name": _instance["name"],
            "status": _instance["status"],
            "sentCount": _instance["sentCount"],
            "createdAt": _instance["createdAt"],
        }


@app.route("/")
def index():
    return send_from_directory(os.path.dirname(os.path.abspath(__file__)), "index.html")


@app.route("/api/config")
def config():
    return jsonify({
        "brokerHost": BROKER_HOST,
        "brokerTcpPort": BROKER_PORT,
        "mode": BROKER_MODE,
        "instanceId": PUBLISHER_ID,
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
        old_client = _instance["client"]

    if old_client is not None:
        old_client.close()

    display_name = name.strip() if name and name.strip() else PUBLISHER_ID
    if BROKER_MODE == "grpc":
        client = PublisherGrpcClient(host=BROKER_HOST, port=BROKER_GRPC_PORT, timeout=5.0)
        try:
            grpc.channel_ready_future(client.channel).result(timeout=5.0)
            connected = True
        except grpc.FutureTimeoutError:
            connected = False
    else:
        client = PublisherClient(host=BROKER_HOST, port=BROKER_TCP_PORT, topic=topic, timeout=5.0)
        connected = client.connect(retries=1)

    with _lock:
        _instance["topic"] = topic
        _instance["name"] = display_name
        _instance["client"] = client
        _instance["status"] = "connected" if connected else "disconnected"
        _instance["sentCount"] = 0
        _instance["createdAt"] = time.strftime("%Y-%m-%d %H:%M:%S")

    return jsonify(_instance_snapshot())


@app.route("/api/disconnect", methods=["POST"])
def disconnect():
    with _lock:
        client = _instance["client"]
        _instance["client"] = None
        _instance["topic"] = None
        _instance["status"] = "disconnected"
    if client is not None:
        client.close()
    return jsonify(_instance_snapshot())


@app.route("/api/send", methods=["POST"])
def send():
    data = request.get_json(force=True, silent=True) or {}
    payload = data.get("payload")

    with _lock:
        client = _instance["client"]
        topic = _instance["topic"]
    if client is None:
        return jsonify({"status": "error", "reason": "Publisher neconectat la niciun topic"}), 409

    if BROKER_MODE == "grpc":
        # Ack-ul gRPC (success/detail) e tradus in formatul JSON asteptat de UI
        grpc_ack = client.publish(topic, payload)
        if grpc_ack is None:
            ack = None
        elif grpc_ack.success:
            ack = {"status": "ok", "detail": grpc_ack.detail}
        else:
            ack = {"status": "error", "reason": grpc_ack.detail}
    else:
        ack = client.publish(payload)
    with _lock:
        _instance["status"] = "connected" if ack is not None else "disconnected"
        if ack is not None:
            _instance["sentCount"] += 1

    if ack is None:
        return jsonify({"status": "error", "reason": "Trimitere esuata (Broker indisponibil?)"}), 502
    return jsonify(ack)


@app.route("/api/publish-invalid", methods=["POST"])
def publish_invalid():
    """Trimite intentionat o linie corupta pe topicul curent al acestei
    instante, ca sa poti demonstra Dead Letter Queue-ul direct din UI."""
    with _lock:
        topic = _instance["topic"]
    if not topic:
        return jsonify({"status": "error", "reason": "Publisher neconectat la niciun topic"}), 409

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5.0)
    try:
        sock.connect((BROKER_HOST, BROKER_TCP_PORT))
        sock.sendall((json.dumps({"role": "publisher", "topic": topic}) + "\n").encode("utf-8"))
        sock.sendall(b"acesta nu e JSON valid - mesaj corupt trimis intentionat\n")

        buffer = ""
        while "\n" not in buffer:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buffer += chunk.decode("utf-8", errors="replace")
        line = buffer.split("\n", 1)[0].strip()
        ack = json.loads(line) if line else {"status": "error", "reason": "Broker nu a raspuns"}
    except OSError:
        return jsonify({"status": "error", "reason": f"Broker indisponibil ({BROKER_HOST}:{BROKER_TCP_PORT})"}), 503
    finally:
        try:
            sock.close()
        except Exception:
            pass

    return jsonify(ack)


if __name__ == "__main__":
    print(f"Publisher UI [{PUBLISHER_ID}] pornit pe http://0.0.0.0:{UI_PORT}  (Broker tinta: {BROKER_HOST}:{BROKER_PORT}, mod {BROKER_MODE.upper()})")
    app.run(host="0.0.0.0", port=UI_PORT, debug=False, threaded=True)
