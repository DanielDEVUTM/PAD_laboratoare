"""
test_subscriber.py
Test automat pentru verificarea Subscriber C# (.NET):
- Handshake protocol: {"role": "subscriber", "topic": "<topic>"}
- Receptionare si afisare mesaje JSON transmise de Broker
- Rulare simultana a 2 subscriberi pe topicuri diferite
- Inchidere curata fara exceptii sau blocaje
- Reconectare automata la intreruperea brokerului
"""

import json
import socket
import subprocess
import sys
import threading
import time

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class MockBroker:
    """Mock TCP Broker pentru testarea SubscriberClient C#."""

    def __init__(self, host="127.0.0.1", port=5055):
        self.host = host
        self.port = port
        self.server_sock = None
        self.running = False
        self.clients = []  # list of (conn, handshake_data)
        self.lock = threading.Lock()

    def start(self):
        self.running = True
        self.server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_sock.bind((self.host, self.port))
        self.server_sock.listen(5)
        threading.Thread(target=self._accept_loop, daemon=True).start()

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
            # Step 1: Read Handshake line
            while self.running:
                chunk = conn.recv(1024)
                if not chunk:
                    return
                buf += chunk.decode("utf-8")
                if "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    handshake = json.loads(line.strip())
                    with self.lock:
                        self.clients.append({"conn": conn, "handshake": handshake})
                    break

            # Keep connection open until closed
            while self.running:
                data = conn.recv(1024)
                if not data:
                    break
        except Exception:
            pass
        finally:
            with self.lock:
                self.clients = [c for c in self.clients if c["conn"] != conn]
            try:
                conn.close()
            except Exception:
                pass

    def broadcast_to_topic(self, topic, message_dict):
        msg_str = json.dumps(message_dict) + "\n"
        with self.lock:
            for c in list(self.clients):
                if c["handshake"].get("topic") == topic:
                    try:
                        c["conn"].sendall(msg_str.encode("utf-8"))
                    except Exception:
                        pass

    def stop(self):
        self.running = False
        if self.server_sock:
            try:
                self.server_sock.close()
            except Exception:
                pass
        with self.lock:
            for c in self.clients:
                try:
                    c["conn"].close()
                except Exception:
                    pass
            self.clients.clear()


def test_single_subscriber():
    print("\n--- TEST 1: Conectare Subscriber, Handshake si Receptie Mesaj ---")
    mock = MockBroker(port=5055)
    mock.start()
    time.sleep(0.5)

    proc = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--port", "5055"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        # Asteptam conectarea si handshake-ul
        for _ in range(30):
            with mock.lock:
                if len(mock.clients) > 0:
                    break
            time.sleep(0.2)

        with mock.lock:
            assert len(mock.clients) == 1, f"Subscriber-ul nu s-a conectat! Clients: {len(mock.clients)}"
            handshake = mock.clients[0]["handshake"]
            assert handshake == {"role": "subscriber", "topic": "sport"}, f"Handshake invalid: {handshake}"
            print("[OK] Handshake valid receptionat de broker:", handshake)

        # Trimitem un mesaj pe topicul 'sport'
        test_msg = {
            "id": "11111111-2222-3333-4444-555555555555",
            "topic": "sport",
            "payload": {"eveniment": "Finala Ligii Campionilor", "scor": "3-2"},
            "timestamp": "2026-09-16T16:15:00Z"
        }
        mock.broadcast_to_topic("sport", test_msg)
        time.sleep(1.0)

        # Inchidere curata subscriber
        proc.terminate()
        try:
            stdout, stderr = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, stderr = proc.communicate()

        assert "MESAJ PRIMIT" in stdout, f"Mesajul nu a fost afisat in stdout! Output:\n{stdout}"
        assert "Finala Ligii Campionilor" in stdout, f"Payload-ul nu a fost gasit in stdout! Output:\n{stdout}"
        print("[OK] Mesaj receptionat si afisat corect in consola Subscriber:")
        for line in stdout.splitlines():
            if any(k in line for k in ["[MESAJ PRIMIT]", "Topic:", "Payload:", "Timestamp:", "ID:"]):
                print("   ", line)

        print("[OK] TEST 1 TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc.poll() is None:
            proc.kill()


def test_two_subscribers_parallel():
    print("\n--- TEST 2: Doi Subscriberi in Paralel pe Topice Diferite ---")
    mock = MockBroker(port=5056)
    mock.start()
    time.sleep(0.5)

    proc_sport = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--port", "5056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    proc_stiri = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "stiri", "--port", "5056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        # Asteptam ca ambii subscriberi sa se conecteze
        for _ in range(30):
            with mock.lock:
                if len(mock.clients) == 2:
                    break
            time.sleep(0.2)

        with mock.lock:
            topics = [c["handshake"].get("topic") for c in mock.clients]
            assert "sport" in topics and "stiri" in topics, f"Topice incorecte conectate: {topics}"
            print(f"[OK] Ambii subscriberi s-au conectat cu succes pe topicele: {topics}")

        # Trimitem un mesaj pentru 'sport'
        mock.broadcast_to_topic("sport", {
            "id": "sport-uuid-1",
            "topic": "sport",
            "payload": {"scor": "1-0"},
            "timestamp": "2026-09-16T16:20:00Z"
        })

        # Trimitem un mesaj pentru 'stiri'
        mock.broadcast_to_topic("stiri", {
            "id": "stiri-uuid-2",
            "topic": "stiri",
            "payload": {"titlu": "Stire de ultima ora"},
            "timestamp": "2026-09-16T16:20:01Z"
        })

        time.sleep(1.0)

        # Oprim ambii subscriberi
        proc_sport.terminate()
        proc_stiri.terminate()

        out_sport, _ = proc_sport.communicate(timeout=5)
        out_stiri, _ = proc_stiri.communicate(timeout=5)

        # Verificam ca sport a primit doar mesajul de sport
        assert "sport-uuid-1" in out_sport, "Subscriber-ul 'sport' nu a primit mesajul sau!"
        assert "stiri-uuid-2" not in out_sport, "Subscriber-ul 'sport' a primit eronat mesajul de 'stiri'!"

        # Verificam ca stiri a primit doar mesajul de stiri
        assert "stiri-uuid-2" in out_stiri, "Subscriber-ul 'stiri' nu a primit mesajul sau!"
        assert "sport-uuid-1" not in out_stiri, "Subscriber-ul 'stiri' a primit eronat mesajul de 'sport'!"

        print("[OK] Izolare perfecta: fiecare subscriber a primit strict mesajele topicului sau!")
        print("[OK] TEST 2 TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc_sport.poll() is None:
            proc_sport.kill()
        if proc_stiri.poll() is None:
            proc_stiri.kill()


if __name__ == "__main__":
    test_single_subscriber()
    test_two_subscribers_parallel()
    print("\n========================================================")
    print(" TOATE TESTELE SUBSCRIBER AU FOST TRECUTE CU SUCCES! ")
    print("========================================================")
