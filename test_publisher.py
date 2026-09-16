"""
test_publisher.py
Test automat pentru verificarea PublisherClient:
- Handshake protocol
- Format mesaj (id uuid4, timestamp ISO8601, topic, payload)
- Primire si parsare ACK
- Reconectare cu backoff (1s, 2s, 4s) in caz de broker indisponibil
"""

import json
import socket
import sys
import threading
import time
from publisher_client import PublisherClient

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class MockBroker:
    """Mock TCP Broker pentru testarea comportamentului PublisherClient."""

    def __init__(self, host="127.0.0.1", port=5051):
        self.host = host
        self.port = port
        self.server_sock = None
        self.running = False
        self.received_handshake = None
        self.received_messages = []
        self._thread = None

    def start(self):
        self.running = True
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.port))
        self.server_sock.listen(5)
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()

    def _accept_loop(self):
        while self.running:
            try:
                conn, _ = self.server_sock.accept()
                threading.Thread(target=self._handle_client, args=(conn,), daemon=True).start()
            except Exception:
                break

    def _handle_client(self, conn):
        buf = ""
        try:
            with conn:
                while self.running:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    buf += chunk.decode("utf-8")
                    while "\n" in buf:
                        line, buf = buf.split("\n", 1)
                        line = line.strip()
                        if not line:
                            continue
                        data = json.loads(line)
                        if "role" in data:
                            self.received_handshake = data
                        elif "payload" in data:
                            self.received_messages.append(data)
                            ack = {"status": "ok"}
                            conn.sendall((json.dumps(ack) + "\n").encode("utf-8"))
        except Exception:
            pass

    def stop(self):
        self.running = False
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass


def test_publisher_protocol():
    print("\n--- TEST 1: Conectare, Handshake si Publicare ---")
    mock = MockBroker(port=5051)
    mock.start()
    time.sleep(0.1)

    try:
        client = PublisherClient(host="127.0.0.1", port=5051, topic="sport")
        connected = client.connect()
        assert connected is True, "Conectarea ar fi trebuit sa reuseasca!"
        time.sleep(0.1)

        assert mock.received_handshake is not None, "Broker-ul nu a primit handshake-ul!"
        assert mock.received_handshake == {"role": "publisher", "topic": "sport"}, f"Handshake invalid: {mock.received_handshake}"
        print("[OK] Handshake valid:", mock.received_handshake)

        # Publicare mesaj
        ack = client.publish({"text": "Salutare din test"})
        assert ack == {"status": "ok"}, f"ACK invalid: {ack}"
        assert len(mock.received_messages) == 1, "Mesajul nu a ajuns la Broker!"

        msg = mock.received_messages[0]
        assert msg["topic"] == "sport", "Topic nepotrivit!"
        assert msg["payload"] == {"text": "Salutare din test"}, "Payload incorect!"
        assert "id" in msg and len(msg["id"]) > 10, "ID invalid!"
        assert "timestamp" in msg and "T" in msg["timestamp"], "Timestamp invalid!"
        print("[OK] Mesaj valid receptionat de broker:", msg)
        client.close()
        print("[OK] TEST 1 TRECUT CU SUCCES!")
    finally:
        mock.stop()


def test_backoff_reconnection():
    print("\n--- TEST 2: Backoff Reconnection cand Brokerul este oprit ---")
    # Port la care nu asculta nimeni
    client = PublisherClient(host="127.0.0.1", port=5052, topic="meteo", timeout=1.0)
    start_time = time.time()
    # Testam cu backoff rapid (1s, 2s, 4s) conform cerintei
    connected = client.connect(retries=3, backoff_delays=(1, 2, 4))
    elapsed = time.time() - start_time

    assert connected is False, "Nu ar fi trebuit sa se poata conecta!"
    # 1s + 2s = minim 3s de sleep intre cele 3 incercari
    assert elapsed >= 3.0, f"Timpul scurs ({elapsed}s) trebuia sa fie minim 3s pentru backoff 1s, 2s!"
    print(f"[OK] Backoff executat corect in {elapsed:.2f} secunde fara crash!")
    print("[OK] TEST 2 TRECUT CU SUCCES!")


if __name__ == "__main__":
    test_publisher_protocol()
    test_backoff_reconnection()
    print("\n==========================================")
    print(" TOATE TESTELE AUTOMATE AU TRECUT CU SUCCES!")
    print("==========================================")
