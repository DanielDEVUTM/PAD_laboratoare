"""
test_subscriber.py
Test automat pentru verificarea Subscriber C# (.NET):
1. TCP raw mode:
   - Handshake protocol: {"role": "subscriber", "topic": "<topic>"}
   - Receptionare si afisare mesaje JSON transmise de Broker
   - Rulare simultana a 2 subscriberi pe topicuri diferite
2. gRPC server-streaming mode:
   - RPC Subscribe(SubRequest) -> stream Message
   - Rulare simultana a 2 subscriberi gRPC pe topicuri diferite
   - Izolare mesaje intre topicuri
   - Inchidere curata fara exceptii sau blocaje
"""

import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
from concurrent import futures

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "publisher"))

try:
    import grpc
    import broker_pb2
    import broker_pb2_grpc
    HAS_GRPC = True
except ImportError:
    HAS_GRPC = False

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class MockTcpBroker:
    """Mock TCP Broker pentru testarea SubscriberClient C# (mod TCP)."""

    def __init__(self, host="127.0.0.1", port=5055):
        self.host = host
        self.port = port
        self.server_sock = None
        self.running = False
        self.clients = []
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


_grpc_base = broker_pb2_grpc.BrokerServicer if HAS_GRPC else object

class MockGrpcBroker(_grpc_base):
    """Mock gRPC Broker pentru testarea SubscriberGrpcClient C#."""

    def __init__(self, port=50055):
        self.port = port
        self.subscribers = {}
        self.lock = threading.Lock()
        self.server = None

    def start(self):
        self.server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        broker_pb2_grpc.add_BrokerServicer_to_server(self, self.server)
        self.server.add_insecure_port(f"127.0.0.1:{self.port}")
        self.server.start()

    def Subscribe(self, request, context):
        q = queue.Queue()
        with self.lock:
            self.subscribers.setdefault(request.topic, []).append(q)
        try:
            while context.is_active():
                try:
                    msg = q.get(timeout=0.3)
                    yield msg
                except queue.Empty:
                    continue
        finally:
            with self.lock:
                if request.topic in self.subscribers and q in self.subscribers[request.topic]:
                    self.subscribers[request.topic].remove(q)

    def publish(self, topic, msg_pb):
        with self.lock:
            for q in self.subscribers.get(topic, []):
                q.put(msg_pb)

    def stop(self):
        if self.server:
            self.server.stop(0)


def test_single_tcp_subscriber():
    print("\n--- TEST 1 (TCP): Conectare Subscriber, Handshake si Receptie Mesaj ---")
    mock = MockTcpBroker(port=5055)
    mock.start()
    time.sleep(0.5)

    proc = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--mode", "tcp", "--port", "5055"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        for _ in range(30):
            with mock.lock:
                if len(mock.clients) > 0:
                    break
            time.sleep(0.2)

        with mock.lock:
            assert len(mock.clients) == 1, f"Subscriber-ul TCP nu s-a conectat! Clients: {len(mock.clients)}"
            handshake = mock.clients[0]["handshake"]
            assert handshake == {"role": "subscriber", "topic": "sport"}, f"Handshake invalid: {handshake}"
            print("[OK] Handshake TCP valid receptionat de broker:", handshake)

        test_msg = {
            "id": "11111111-2222-3333-4444-555555555555",
            "topic": "sport",
            "payload": {"eveniment": "Finala Ligii Campionilor", "scor": "3-2"},
            "timestamp": "2026-09-16T16:15:00Z"
        }
        mock.broadcast_to_topic("sport", test_msg)
        time.sleep(1.0)

        proc.terminate()
        try:
            stdout, _ = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, _ = proc.communicate()

        assert "MESAJ PRIMIT" in stdout, f"Mesajul nu a fost afisat in stdout! Output:\n{stdout}"
        assert "Finala Ligii Campionilor" in stdout, f"Payload-ul nu a fost gasit in stdout! Output:\n{stdout}"
        print("[OK] Mesaj TCP receptionat si afisat corect in consola Subscriber:")
        for line in stdout.splitlines():
            if any(k in line for k in ["[MESAJ PRIMIT]", "Topic:", "Payload:", "Timestamp:", "ID:"]):
                print("   ", line)

        print("[OK] TEST 1 (TCP) TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc.poll() is None:
            proc.kill()


def test_two_tcp_subscribers_parallel():
    print("\n--- TEST 2 (TCP): Doi Subscriberi TCP in Paralel pe Topice Diferite ---")
    mock = MockTcpBroker(port=5056)
    mock.start()
    time.sleep(0.5)

    proc_sport = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--mode", "tcp", "--port", "5056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    proc_stiri = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "stiri", "--mode", "tcp", "--port", "5056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        for _ in range(30):
            with mock.lock:
                if len(mock.clients) == 2:
                    break
            time.sleep(0.2)

        with mock.lock:
            topics = [c["handshake"].get("topic") for c in mock.clients]
            assert "sport" in topics and "stiri" in topics, f"Topice incorecte conectate: {topics}"
            print(f"[OK] Ambii subscriberi TCP s-au conectat cu succes pe topicele: {topics}")

        mock.broadcast_to_topic("sport", {
            "id": "sport-uuid-1",
            "topic": "sport",
            "payload": {"scor": "1-0"},
            "timestamp": "2026-09-16T16:20:00Z"
        })

        mock.broadcast_to_topic("stiri", {
            "id": "stiri-uuid-2",
            "topic": "stiri",
            "payload": {"titlu": "Stire de ultima ora"},
            "timestamp": "2026-09-16T16:20:01Z"
        })

        time.sleep(1.0)

        proc_sport.terminate()
        proc_stiri.terminate()

        out_sport, _ = proc_sport.communicate(timeout=5)
        out_stiri, _ = proc_stiri.communicate(timeout=5)

        assert "sport-uuid-1" in out_sport and "stiri-uuid-2" not in out_sport
        assert "stiri-uuid-2" in out_stiri and "sport-uuid-1" not in out_stiri

        print("[OK] Izolare perfecta: fiecare subscriber TCP a primit strict mesajele topicului sau!")
        print("[OK] TEST 2 (TCP) TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc_sport.poll() is None:
            proc_sport.kill()
        if proc_stiri.poll() is None:
            proc_stiri.kill()


def test_grpc_single_subscriber():
    print("\n--- TEST 3 (gRPC): Conectare Subscriber gRPC Streaming si Receptie Mesaj ---")
    mock = MockGrpcBroker(port=50055)
    mock.start()
    time.sleep(0.5)

    proc = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--mode", "grpc", "--port", "50055"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        # Asteptam abonarea
        for _ in range(30):
            with mock.lock:
                if len(mock.subscribers.get("sport", [])) > 0:
                    break
            time.sleep(0.2)

        with mock.lock:
            assert len(mock.subscribers.get("sport", [])) == 1, "Subscriber-ul gRPC nu s-a abonat la 'sport'!"
            print("[OK] Subscriber gRPC abonat cu succes la stream-ul 'sport'")

        # Publicam un mesaj gRPC
        msg = broker_pb2.Message(
            id="grpc-test-uuid-1",
            topic="sport",
            payload=json.dumps({"goluri": 4, "castigator": "Real Madrid"}),
            timestamp="2026-09-16T16:30:00Z"
        )
        mock.publish("sport", msg)
        time.sleep(1.0)

        proc.terminate()
        try:
            stdout, _ = proc.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            stdout, _ = proc.communicate()

        assert "MESAJ PRIMIT (gRPC)" in stdout or "MESAJ PRIMIT" in stdout, f"Mesajul nu apare in stdout:\n{stdout}"
        assert "Real Madrid" in stdout, f"Payload-ul nu a fost gasit in stdout:\n{stdout}"
        print("[OK] Mesaj gRPC receptionat si afisat corect in consola Subscriber:")
        for line in stdout.splitlines():
            if any(k in line for k in ["[MESAJ PRIMIT", "Topic:", "Payload:", "Timestamp:", "ID:"]):
                print("   ", line)

        print("[OK] TEST 3 (gRPC) TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc.poll() is None:
            proc.kill()


def test_two_grpc_subscribers_parallel():
    print("\n--- TEST 4 (gRPC): Doi Subscriberi gRPC in Paralel pe Topice Diferite ---")
    mock = MockGrpcBroker(port=50056)
    mock.start()
    time.sleep(0.5)

    proc_sport = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "sport", "--mode", "grpc", "--port", "50056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    proc_meteo = subprocess.Popen(
        ["dotnet", "run", "--project", "subscriber", "--no-build", "--", "--topic", "meteo", "--mode", "grpc", "--port", "50056"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8"
    )

    try:
        # Asteptam abonarea ambilor
        for _ in range(30):
            with mock.lock:
                if len(mock.subscribers.get("sport", [])) == 1 and len(mock.subscribers.get("meteo", [])) == 1:
                    break
            time.sleep(0.2)

        with mock.lock:
            assert len(mock.subscribers.get("sport", [])) == 1, "Subscriber-ul 'sport' nu s-a abonat!"
            assert len(mock.subscribers.get("meteo", [])) == 1, "Subscriber-ul 'meteo' nu s-a abonat!"
            print("[OK] Ambii subscriberi gRPC s-au abonat in paralel pe 'sport' si 'meteo'")

        # Trimitem mesaje distincte
        mock.publish("sport", broker_pb2.Message(
            id="grpc-sport-1",
            topic="sport",
            payload=json.dumps({"scor": "2-2"}),
            timestamp="2026-09-16T16:35:00Z"
        ))

        mock.publish("meteo", broker_pb2.Message(
            id="grpc-meteo-2",
            topic="meteo",
            payload=json.dumps({"temperatura": 24, "stare": "senin"}),
            timestamp="2026-09-16T16:35:01Z"
        ))

        time.sleep(1.0)

        proc_sport.terminate()
        proc_meteo.terminate()

        out_sport, _ = proc_sport.communicate(timeout=5)
        out_meteo, _ = proc_meteo.communicate(timeout=5)

        assert "grpc-sport-1" in out_sport and "grpc-meteo-2" not in out_sport
        assert "grpc-meteo-2" in out_meteo and "grpc-sport-1" not in out_meteo

        print("[OK] Izolare gRPC perfecta: fiecare subscriber a primit doar mesajele topicului sau!")
        print("[OK] TEST 4 (gRPC) TRECUT CU SUCCES!")
    finally:
        mock.stop()
        if proc_sport.poll() is None:
            proc_sport.kill()
        if proc_meteo.poll() is None:
            proc_meteo.kill()


if __name__ == "__main__":
    test_single_tcp_subscriber()
    test_two_tcp_subscribers_parallel()
    if HAS_GRPC:
        test_grpc_single_subscriber()
        test_two_grpc_subscribers_parallel()
    else:
        print("\n[INFO] Modulul Python 'grpc' nu este instalat local - testele gRPC Python au fost omise.")
    print("\n==================================================================")
    print(" TOATE TESTELE SUBSCRIBER DISPONIBILE AU TRECUT CU SUCCES! ")
    print("==================================================================")
