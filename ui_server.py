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
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Dict, Optional

from flask import Flask, jsonify, request, send_from_directory
from flask_cors import CORS

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "publisher"))
from publisher_client import PublisherClient

BROKER_HOST = "127.0.0.1"
BROKER_TCP_PORT = 5050
BROKER_ADMIN_PORT = 5052
UI_PORT = 8000
MAX_MESSAGES_PER_SUBSCRIBER = 300
RECONNECT_DELAYS = (1, 2, 4)

app = Flask(__name__, static_folder="ui", static_url_path="")
CORS(app)

# Each browser-managed subscriber is its own independent TCP connection to
# the broker, so you can add/remove as many as you want per topic (or spread
# across topics) to demonstrate broadcast/backlog behaviour.
_subscribers_lock = threading.Lock()
_subscribers: Dict[str, Dict[str, Any]] = {}

# Each browser-managed publisher is its own persistent TCP connection (via
# PublisherClient), so you can run several named publisher instances - possibly
# on different topics - and remove any one of them independently, same as
# subscribers. PublisherClient.publish() already reconnects on failure, we
# just add a lock since Flask serves requests from multiple threads.
_publishers_lock = threading.Lock()
_publishers: Dict[str, Dict[str, Any]] = {}


def _create_subscriber(topic: str, name: Optional[str] = None) -> Dict[str, str]:
    sub_id = uuid.uuid4().hex[:8]
    # The subscriberId is what the BROKER uses to recognize "the same subscriber"
    # across disconnect/reconnect (see TopicRegistry.addIdentifiedSubscriber).
    # If the user gives it a name, reusing that same name later reconnects as
    # the same identity and flushes whatever it missed. Without a name, we still
    # send a (random) id, which is enough for it to have its own personal backlog
    # for as long as this particular card lives, but a NEW card next time means
    # a NEW identity - so naming it is what makes "reconnect and catch up" possible.
    subscriber_id = name.strip() if name and name.strip() else sub_id
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
    with _subscribers_lock:
        _subscribers[sub_id] = entry
    threading.Thread(target=_subscriber_worker, args=(sub_id,), daemon=True).start()
    return {"id": sub_id, "subscriberId": subscriber_id}


def _remove_subscriber(sub_id: str) -> bool:
    """Closes the live TCP connection for this specific subscriber instance,
    so the broker sees it as disconnected (subscriberCount drops for that
    topic, and future messages for it go into the topic's backlog)."""
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


def _create_publisher(topic: str, name: Optional[str] = None) -> Dict[str, str]:
    pub_id = uuid.uuid4().hex[:8]
    display_name = name.strip() if name and name.strip() else pub_id
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
        _publishers[pub_id] = entry
    return {"id": pub_id, "name": display_name, "status": entry["status"]}


def _remove_publisher(pub_id: str) -> bool:
    with _publishers_lock:
        entry = _publishers.pop(pub_id, None)
    if entry is None:
        return False
    entry["client"].close()
    return True


def _admin_get(path: str):
    url = f"http://{BROKER_HOST}:{BROKER_ADMIN_PORT}{path}"
    try:
        with urllib.request.urlopen(url, timeout=2.0) as response:
            return json.loads(response.read().decode("utf-8")), True
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return [], False


def _admin_delete(path: str):
    url = f"http://{BROKER_HOST}:{BROKER_ADMIN_PORT}{path}"
    req = urllib.request.Request(url, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=2.0) as response:
            return json.loads(response.read().decode("utf-8")), response.status
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode("utf-8")), e.code
        except Exception:
            return {"status": "error", "reason": "Raspuns invalid de la Broker"}, e.code
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return {"status": "error", "reason": "Broker indisponibil (Admin API 5052)"}, 503


@app.route("/")
def index():
    return send_from_directory("ui", "index.html")


@app.route("/api/publishers", methods=["GET"])
def list_publishers():
    with _publishers_lock:
        result = [
            {
                "id": p["id"],
                "topic": p["topic"],
                "name": p["name"],
                "status": p["status"],
                "sentCount": p["sentCount"],
                "createdAt": p["createdAt"],
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


@app.route("/api/subscribers", methods=["GET"])
def list_subscribers():
    with _subscribers_lock:
        result = [
            {
                "id": s["id"],
                "topic": s["topic"],
                "subscriberId": s["subscriberId"],
                "status": s["status"],
                "messageCount": s["counter"],
                "createdAt": s["createdAt"],
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


@app.route("/api/publish-invalid", methods=["POST"])
def publish_invalid():
    """Sends a deliberately malformed line on a real publisher TCP connection,
    so the Dead Letter Queue can be demonstrated straight from the UI without
    an external script. Mirrors what a corrupted/buggy client would send."""
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
        return jsonify({"status": "error", "reason": "Broker indisponibil (TCP 5050)"}), 503
    finally:
        try:
            sock.close()
        except Exception:
            pass

    return jsonify(ack)


@app.route("/api/topics")
def topics():
    data, online = _admin_get("/api/topics")
    return jsonify({"topics": data, "brokerOnline": online})


@app.route("/api/topics/<topic>", methods=["DELETE"])
def delete_topic(topic: str):
    """Deletes a whole topic on the Broker: disconnects every subscriber on
    it (anonymous and identified) and discards its backlog/roster."""
    encoded = urllib.parse.quote(topic, safe="")
    data, status = _admin_delete(f"/api/topics/{encoded}")
    return jsonify(data), status


@app.route("/api/topics/<topic>/subscribers/<subscriber_id>", methods=["DELETE"])
def delete_topic_subscriber(topic: str, subscriber_id: str):
    """Forgets one identified subscriber on the Broker: disconnects it if
    online and discards its personal pending backlog."""
    encoded_topic = urllib.parse.quote(topic, safe="")
    encoded_id = urllib.parse.quote(subscriber_id, safe="")
    data, status = _admin_delete(f"/api/topics/{encoded_topic}/subscribers/{encoded_id}")
    return jsonify(data), status


@app.route("/api/dlq")
def dlq():
    data, online = _admin_get("/api/dlq")
    return jsonify({"entries": data, "brokerOnline": online})


@app.route("/api/subscriber-roster")
def subscriber_roster():
    data, online = _admin_get("/api/subscriber-roster")
    return jsonify({"roster": data, "brokerOnline": online})


if __name__ == "__main__":
    print(f"UI bridge pornit pe http://127.0.0.1:{UI_PORT}  (Broker asteptat pe TCP {BROKER_TCP_PORT}, Admin API pe {BROKER_ADMIN_PORT})")
    app.run(host="127.0.0.1", port=UI_PORT, debug=False, threaded=True)
