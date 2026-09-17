"""
publisher-ui/app.py
Aplicatie INDEPENDENTA pentru rolul de Publisher, cu interfata web proprie.

Rulata separat, pe laptopul persoanei responsabile de Publisher, conectata
la Broker (care poate fi pe alt laptop, pe aceeasi retea/hotspot) prin IP.
Nu are nicio dependenta de broker-ui/ sau subscriber-ui/ - e o aplicatie
de sine statatoare, exact cum ar rula pe un calculator diferit.

Configurare (variabile de mediu, cu valori implicite pentru testare locala):
  BROKER_HOST=192.168.1.23   (IP-ul laptopului cu Broker-ul; implicit 127.0.0.1)
  BROKER_TCP_PORT=5050       (implicit 5050)
  PUBLISHER_UI_PORT=8001     (implicit 8001)

Rulare:
  BROKER_HOST=192.168.1.23 python publisher-ui/app.py
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

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "publisher"))
from publisher_client import PublisherClient

BROKER_HOST = os.environ.get("BROKER_HOST", "127.0.0.1")
BROKER_TCP_PORT = int(os.environ.get("BROKER_TCP_PORT", "5050"))
UI_PORT = int(os.environ.get("PUBLISHER_UI_PORT", "8001"))

app = Flask(__name__, static_folder=os.path.dirname(os.path.abspath(__file__)), static_url_path="")
CORS(app)

_publishers_lock = threading.Lock()
_publishers: Dict[str, Dict[str, Any]] = {}


def _name_taken_on_topic(topic: str, name: str) -> bool:
    return any(p["topic"] == topic and p["name"] == name for p in _publishers.values())


def _create_publisher(topic: str, name: Optional[str] = None) -> Optional[Dict[str, str]]:
    pub_id = uuid.uuid4().hex[:8]
    display_name = name.strip() if name and name.strip() else pub_id

    with _publishers_lock:
        if name and name.strip() and _name_taken_on_topic(topic, display_name):
            return None

    client = PublisherClient(host=BROKER_HOST, port=BROKER_TCP_PORT, topic=topic, timeout=5.0)
    connected = client.connect(retries=1)
    entry = {
        "id": pub_id,
        "topic": topic,
        "name": display_name,
        "client": client,
        "lock": threading.Lock(),
        "status": "connected" if connected else "disconnected",
        "sentCount": 0,
        "createdAt": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    with _publishers_lock:
        if name and name.strip() and _name_taken_on_topic(topic, display_name):
            client.close()
            return None
        _publishers[pub_id] = entry
    return {"id": pub_id, "name": display_name, "status": entry["status"]}


def _remove_publisher(pub_id: str) -> bool:
    with _publishers_lock:
        entry = _publishers.pop(pub_id, None)
    if entry is None:
        return False
    entry["client"].close()
    return True


@app.route("/")
def index():
    return send_from_directory(os.path.dirname(os.path.abspath(__file__)), "index.html")


@app.route("/api/config")
def config():
    return jsonify({"brokerHost": BROKER_HOST, "brokerTcpPort": BROKER_TCP_PORT})


@app.route("/api/publishers", methods=["GET"])
def list_publishers():
    with _publishers_lock:
        result = [
            {
                "id": p["id"], "topic": p["topic"], "name": p["name"],
                "status": p["status"], "sentCount": p["sentCount"], "createdAt": p["createdAt"],
            }
            for p in sorted(_publishers.values(), key=lambda p: p["createdAt"])
        ]
    return jsonify({"publishers": result})


@app.route("/api/publishers", methods=["POST"])
def add_publisher():
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    name = data.get("name")
    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

    created = _create_publisher(topic, name)
    if created is None:
        return jsonify({"status": "error",
                         "reason": f"Există deja un publisher numit „{name.strip()}” pe topicul „{topic}”"}), 409
    return jsonify({"status": "ok", "id": created["id"], "name": created["name"],
                     "topic": topic, "connectionStatus": created["status"]})


@app.route("/api/publishers/<pub_id>", methods=["DELETE"])
def remove_publisher_route(pub_id: str):
    removed = _remove_publisher(pub_id)
    if not removed:
        return jsonify({"status": "error", "reason": "Publisher inexistent"}), 404
    return jsonify({"status": "ok", "id": pub_id})


@app.route("/api/publishers/<pub_id>/send", methods=["POST"])
def publisher_send(pub_id: str):
    data = request.get_json(force=True, silent=True) or {}
    payload = data.get("payload")

    with _publishers_lock:
        entry = _publishers.get(pub_id)
    if entry is None:
        return jsonify({"status": "error", "reason": "Publisher inexistent"}), 404

    with entry["lock"]:
        ack = entry["client"].publish(payload)
        with _publishers_lock:
            entry = _publishers.get(pub_id)
            if entry is not None:
                entry["status"] = "connected" if ack is not None else "disconnected"
                if ack is not None:
                    entry["sentCount"] += 1

    if ack is None:
        return jsonify({"status": "error", "reason": "Trimitere eșuată (Broker indisponibil?)"}), 502
    return jsonify(ack)


@app.route("/api/publish-invalid", methods=["POST"])
def publish_invalid():
    """Trimite intentionat o linie corupta pe o conexiune reala de publisher,
    ca sa poti demonstra Dead Letter Queue-ul direct din UI."""
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

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
    print(f"Publisher UI pornit pe http://0.0.0.0:{UI_PORT}  (Broker tinta: {BROKER_HOST}:{BROKER_TCP_PORT})")
    app.run(host="0.0.0.0", port=UI_PORT, debug=False, threaded=True)
