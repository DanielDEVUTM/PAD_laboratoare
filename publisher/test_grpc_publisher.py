"""
test_grpc_publisher.py
Test automat pentru verificarea PublisherGrpcClient:
- Apel unar Publish(Message) -> Ack
- Verificare format mesaj: id (uuid4), topic, payload (JSON serializat), timestamp (ISO8601)
- Tratare erori gRPC (server indisponibil) fara crash
"""

import json
import sys
import time
from concurrent import futures

import grpc
import broker_pb2
import broker_pb2_grpc
from publisher_grpc_client import PublisherGrpcClient

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


class MockBrokerServicer(broker_pb2_grpc.BrokerServicer):
    """Implementare de test a serviciului Broker definit in broker.proto."""

    def __init__(self):
        self.received_messages = []

    def Publish(self, request, context):
        self.received_messages.append(request)
        return broker_pb2.Ack(
            success=True,
            detail=f"Mesaj {request.id} acceptat pe topicul {request.topic}"
        )

    def Subscribe(self, request, context):
        context.abort(grpc.StatusCode.UNIMPLEMENTED, "Nu este necesar pentru test publisher")


def test_grpc_publish_success():
    print("\n--- TEST gRPC 1: Apel unar Publish si verificare Ack ---")
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    servicer = MockBrokerServicer()
    broker_pb2_grpc.add_BrokerServicer_to_server(servicer, server)
    port = server.add_insecure_port("127.0.0.1:50052")
    server.start()
    print(f"[TEST] Mock gRPC Broker pornit pe portul {port}")

    try:
        client = PublisherGrpcClient(host="127.0.0.1", port=50052)
        payload = {"scor": "3-2", "echipa": "Moldova"}
        ack = client.publish(topic="sport", payload_dict=payload)

        assert ack is not None, "ACK nu trebuia sa fie None!"
        assert ack.success is True, f"ACK success trebuia sa fie True, dar a fost {ack.success}"
        assert len(servicer.received_messages) == 1, "Brokerul trebuia sa primeasca 1 mesaj!"

        msg = servicer.received_messages[0]
        assert msg.topic == "sport", f"Topic nepotrivit: {msg.topic}"
        assert len(msg.id) > 10, f"ID invalid: {msg.id}"
        assert "T" in msg.timestamp, f"Timestamp invalid: {msg.timestamp}"

        parsed_payload = json.loads(msg.payload)
        assert parsed_payload == payload, f"Payload nepotrivit: {parsed_payload}"
        print(f"[OK] Mesaj gRPC receptionat corect: id={msg.id}, topic={msg.topic}, payload={msg.payload}")
        print(f"[OK] Ack primit: success={ack.success}, detail='{ack.detail}'")

        client.close()
        print("[OK] TEST gRPC 1 TRECUT CU SUCCES!")
    finally:
        server.stop(0)


def test_grpc_server_unavailable():
    print("\n--- TEST gRPC 2: Tratare server gRPC indisponibil fara crash ---")
    # Port la care nu asculta niciun server gRPC
    client = PublisherGrpcClient(host="127.0.0.1", port=50059, timeout=1.0)
    ack = client.publish(topic="stiri", payload_dict={"text": "Test offline"})

    assert ack is None, "ACK trebuia sa fie None cand serverul e oprit!"
    print("[OK] Clientul a tratat eroarea UNAVAILABLE elegant, fara crash!")
    client.close()
    print("[OK] TEST gRPC 2 TRECUT CU SUCCES!")


if __name__ == "__main__":
    test_grpc_publish_success()
    test_grpc_server_unavailable()
    print("\n==============================================")
    print(" TOATE TESTELE gRPC AUTOMATE AU TRECUT CU SUCCES!")
    print("==============================================")
