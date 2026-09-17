"""
subscriber-ui/app.py
Aplicatie INDEPENDENTA pentru rolul de Subscriber, cu interfata web proprie.

Rulata separat, pe laptopul persoanei responsabile de Subscriber, conectata
la Broker (care poate fi pe alt laptop, pe aceeasi retea/hotspot) prin IP.
Nu are nicio dependenta de broker-ui/ sau publisher-ui/ - e o aplicatie
de sine statatoare, exact cum ar rula pe un calculator diferit.

Configurare (variabile de mediu, cu valori implicite pentru testare locala):
  BROKER_HOST=192.168.1.23   (IP-ul laptopului cu Broker-ul; implicit 127.0.0.1)
  BROKER_TCP_PORT=5050       (implicit 5050)
  SUBSCRIBER_UI_PORT=8002    (implicit 8002)

Rulare:
  BROKER_HOST=192.168.1.23 python subscriber-ui/app.py
"""

import json
import os
import socket
import threading
import time
import uuid
from typing import Any, Dict, Optional

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS

BROKER_HOST = os.environ.get("BROKER_HOST", "127.0.0.1")
BROKER_TCP_PORT = int(os.environ.get("BROKER_TCP_PORT", "5050"))
UI_PORT = int(os.environ.get("SUBSCRIBER_UI_PORT", "8002"))
MAX_MESSAGES_PER_SUBSCRIBER = 300
RECONNECT_DELAYS = (1, 2, 4)

app = Flask(__name__, static_folder=os.path.dirname(os.path.abspath(__file__)), static_url_path="")
CORS(app)

_subscribers_lock = threading.Lock()
_subscribers: Dict[str, Dict[str, Any]] = {}


def _name_taken_on_topic(topic: str, subscriber_id: str) -> bool:
    return any(s["topic"] == topic and s["subscriberId"] == subscriber_id for s in _subscribers.values())


def _create_subscriber(topic: str, name: Optional[str] = None) -> Optional[Dict[str, str]]:
    sub_id = uuid.uuid4().hex[:8]
    subscriber_id = name.strip() if name and name.strip() else sub_id

    with _subscribers_lock:
        if name and name.strip() and _name_taken_on_topic(topic, subscriber_id):
            return None
        entry = {
            "id": sub_id,
            "topic": topic,
            "subscriberId": subscriber_id,
            "status": "connecting",
            "stop_event": threading.Event(),
            "socket": None,
            "feed": [],
            "counter": 0,
            "createdAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        _subscribers[sub_id] = entry

    threading.Thread(target=_subscriber_worker, args=(sub_id,), daemon=True).start()
    return {"id": sub_id, "subscriberId": subscriber_id}


def _remove_subscriber(sub_id: str) -> bool:
    with _subscribers_lock:
        entry = _subscribers.pop(sub_id, None)
    if entry is None:
        return False

    entry["stop_event"].set()
    sock = entry.get("socket")
    if sock is not None:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
        try:
            sock.close()
        except Exception:
            pass
    return True


def _subscriber_worker(sub_id: str) -> None:
    buffer = ""
    failed_attempts = 0

    while True:
        with _subscribers_lock:
            entry = _subscribers.get(sub_id)
            if entry is None:
                return
            topic = entry["topic"]
            subscriber_id = entry["subscriberId"]
            stop_event = entry["stop_event"]

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

            with _subscribers_lock:
                entry = _subscribers.get(sub_id)
                if entry is None or entry["stop_event"].is_set():
                    sock.close()
                    return
                entry["socket"] = sock
                entry["status"] = "connected"
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
                    with _subscribers_lock:
                        entry = _subscribers.get(sub_id)
                        if entry is None:
                            return
                        entry["counter"] += 1
                        entry["feed"].append({
                            "index": entry["counter"],
                            "id": message.get("id"),
                            "payload": message.get("payload"),
                            "timestamp": message.get("timestamp"),
                            "receivedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
                        })
                        if len(entry["feed"]) > MAX_MESSAGES_PER_SUBSCRIBER:
                            del entry["feed"][: len(entry["feed"]) - MAX_MESSAGES_PER_SUBSCRIBER]
        except Exception:
            pass
        finally:
            with _subscribers_lock:
                entry = _subscribers.get(sub_id)
                if entry is not None:
                    entry["socket"] = None
                    if not stop_event.is_set():
                        entry["status"] = "reconnecting"
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
    return jsonify({"brokerHost": BROKER_HOST, "brokerTcpPort": BROKER_TCP_PORT})


@app.route("/api/subscribers", methods=["GET"])
def list_subscribers():
    with _subscribers_lock:
        result = [
            {
                "id": s["id"], "topic": s["topic"], "subscriberId": s["subscriberId"],
                "status": s["status"], "messageCount": s["counter"], "createdAt": s["createdAt"],
            }
            for s in sorted(_subscribers.values(), key=lambda s: s["createdAt"])
        ]
    return jsonify({"subscribers": result})


@app.route("/api/subscribers", methods=["POST"])
def add_subscriber():
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    name = data.get("name")
    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

    created = _create_subscriber(topic, name)
    if created is None:
        return jsonify({"status": "error",
                         "reason": f"Există deja un subscriber numit „{name.strip()}” pe topicul „{topic}”"}), 409
    return jsonify({"status": "ok", "id": created["id"], "subscriberId": created["subscriberId"], "topic": topic})


@app.route("/api/subscribers/<sub_id>", methods=["DELETE"])
def remove_subscriber_route(sub_id: str):
    removed = _remove_subscriber(sub_id)
    if not removed:
        return jsonify({"status": "error", "reason": "Subscriber inexistent"}), 404
    return jsonify({"status": "ok", "id": sub_id})


@app.route("/api/subscribers/<sub_id>/messages")
def subscriber_messages(sub_id: str):
    since = request.args.get("since", default=0, type=int)
    with _subscribers_lock:
        entry = _subscribers.get(sub_id)
        if entry is None:
            return jsonify({"error": "Subscriber inexistent"}), 404
        new_items = [m for m in entry["feed"] if m["index"] > since]
        latest = entry["feed"][-1]["index"] if entry["feed"] else since
        status = entry["status"]
    return jsonify({"messages": new_items, "latest": latest, "status": status})


if __name__ == "__main__":
    print(f"Subscriber UI pornit pe http://0.0.0.0:{UI_PORT}  (Broker tinta: {BROKER_HOST}:{BROKER_TCP_PORT})")
    app.run(host="0.0.0.0", port=UI_PORT, debug=False, threaded=True)
