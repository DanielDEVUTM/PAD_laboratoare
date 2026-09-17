"""
ui_server.py
Bridge local intre interfata web (browser) si sistemul Pub/Sub existent.

Un browser nu poate deschide un socket TCP brut sau un canal gRPC direct,
asa ca acest server Flask joaca rolul unui Publisher/Subscriber in plus:
- publica mesaje pe Broker (Java) folosind exact protocolul TCP existent
  (reutilizeaza PublisherClient din publisher_client.py);
- se aboneaza pe topicuri cerute de UI si retine ultimele mesaje intr-un
  buffer in memorie, pe care browserul il citeste prin polling;
- face proxy la API-ul de administrare al Broker-ului (topicuri, DLQ).

Nu inlocuieste niciuna dintre componentele Partii 1/2 ale proiectului -
este doar un al patrulea client, folosit exclusiv pentru interfata grafica.
"""

import json
import socket
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict
from typing import Any, Dict, List

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS

from publisher_client import PublisherClient

BROKER_HOST = "127.0.0.1"
BROKER_TCP_PORT = 5050
BROKER_ADMIN_PORT = 5052
UI_PORT = 8000
MAX_MESSAGES_PER_TOPIC = 300
RECONNECT_DELAYS = (1, 2, 4)

app = Flask(__name__, static_folder="ui", static_url_path="")
CORS(app)

_feeds_lock = threading.Lock()
_feeds: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
_feed_counters: Dict[str, int] = defaultdict(int)
_subscribed_topics: set = set()


def _append_message(topic: str, message: Dict[str, Any]) -> None:
    with _feeds_lock:
        _feed_counters[topic] += 1
        entry = {
            "index": _feed_counters[topic],
            "id": message.get("id"),
            "payload": message.get("payload"),
            "timestamp": message.get("timestamp"),
            "receivedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        bucket = _feeds[topic]
        bucket.append(entry)
        if len(bucket) > MAX_MESSAGES_PER_TOPIC:
            del bucket[: len(bucket) - MAX_MESSAGES_PER_TOPIC]


def _subscriber_worker(topic: str) -> None:
    """Runs forever in background: keeps one live subscriber connection per
    topic open on behalf of the browser, reconnecting with backoff like the
    real Subscriber client does."""
    buffer = ""
    failed_attempts = 0
    while True:
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5.0)
            sock.connect((BROKER_HOST, BROKER_TCP_PORT))
            sock.sendall((json.dumps({"role": "subscriber", "topic": topic}) + "\n").encode("utf-8"))
            sock.settimeout(None)
            failed_attempts = 0

            while True:
                chunk = sock.recv(4096)
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
                    _append_message(topic, message)
        except Exception:
            pass
        finally:
            if sock is not None:
                try:
                    sock.close()
                except Exception:
                    pass

        delay = RECONNECT_DELAYS[min(failed_attempts, len(RECONNECT_DELAYS) - 1)]
        failed_attempts += 1
        time.sleep(delay)


def _ensure_subscribed(topic: str) -> None:
    with _feeds_lock:
        already = topic in _subscribed_topics
        if not already:
            _subscribed_topics.add(topic)
    if not already:
        thread = threading.Thread(target=_subscriber_worker, args=(topic,), daemon=True)
        thread.start()


def _admin_get(path: str):
    url = f"http://{BROKER_HOST}:{BROKER_ADMIN_PORT}{path}"
    try:
        with urllib.request.urlopen(url, timeout=2.0) as response:
            return json.loads(response.read().decode("utf-8")), True
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return [], False


@app.route("/")
def index():
    return send_from_directory("ui", "index.html")


@app.route("/api/publish", methods=["POST"])
def publish():
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    payload = data.get("payload")

    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

    client = PublisherClient(host=BROKER_HOST, port=BROKER_TCP_PORT, topic=topic, timeout=5.0)
    if not client.connect(retries=1):
        return jsonify({"status": "error", "reason": "Broker indisponibil (TCP 5050)"}), 503

    ack = client.publish(payload)
    client.close()

    if ack is None:
        return jsonify({"status": "error", "reason": "Nu s-a primit ACK de la Broker"}), 502
    return jsonify(ack)


@app.route("/api/subscribe", methods=["POST"])
def subscribe():
    data = request.get_json(force=True, silent=True) or {}
    topic = (data.get("topic") or "").strip()
    if not topic:
        return jsonify({"status": "error", "reason": "Topic lipsa"}), 400

    _ensure_subscribed(topic)
    return jsonify({"status": "ok", "topic": topic})


@app.route("/api/messages/<topic>")
def messages(topic: str):
    since = request.args.get("since", default=0, type=int)
    with _feeds_lock:
        bucket = _feeds.get(topic, [])
        new_items = [m for m in bucket if m["index"] > since]
        latest = bucket[-1]["index"] if bucket else since
    return jsonify({"messages": new_items, "latest": latest})


@app.route("/api/topics")
def topics():
    data, online = _admin_get("/api/topics")
    return jsonify({"topics": data, "brokerOnline": online})


@app.route("/api/dlq")
def dlq():
    data, online = _admin_get("/api/dlq")
    return jsonify({"entries": data, "brokerOnline": online})


if __name__ == "__main__":
    print(f"UI bridge pornit pe http://127.0.0.1:{UI_PORT}  (Broker asteptat pe TCP {BROKER_TCP_PORT}, Admin API pe {BROKER_ADMIN_PORT})")
    app.run(host="127.0.0.1", port=UI_PORT, debug=False, threaded=True)
