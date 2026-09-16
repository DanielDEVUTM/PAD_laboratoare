"""
publisher_grpc_client.py
Modul ce contine clasa PublisherGrpcClient pentru comunicarea cu Broker-ul
prin apeluri procedurale la distanta (gRPC) utilizand broker.proto.
"""

import json
import logging
import sys
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

import grpc

# Import stub-uri generate din broker.proto
try:
    import broker_pb2
    import broker_pb2_grpc
except ImportError:
    # Vor fi disponibile dupa generarea cu grpc_tools
    pass

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

logger = logging.getLogger("PublisherGrpcClient")


class PublisherGrpcClient:
    """
    Client gRPC pentru Publisher intr-un sistem Pub/Sub.
    
    Apeleaza RPC-ul unar:
      rpc Publish(Message) returns (Ack);
    unde Message contine:
      - id: uuid4 generat automat
      - topic: topicul specificat
      - payload: JSON serializat ca string
      - timestamp: ISO8601 UTC
    """

    def __init__(self, host: str = "127.0.0.1", port: int = 50051, timeout: float = 5.0):
        self.host = host
        self.port = port
        self.target = f"{host}:{port}"
        self.timeout = timeout
        self.channel = grpc.insecure_channel(self.target)
        self.stub = broker_pb2_grpc.BrokerStub(self.channel)
        print(f"[gRPC] Canal initializat catre Broker la {self.target}")

    def publish(self, topic: str, payload_dict: Any) -> Optional[Any]:
        """
        Publica un mesaj pe un topic apeland RPC-ul unar Publish().
        
        Parametri:
          - topic: numele topicului (ex: 'sport')
          - payload_dict: datele mesajului (dict sau text ce va fi serializat ca JSON string)
        
        Trateaza exceptiile de retea gRPC (UNAVAILABLE, DEADLINE_EXCEEDED etc.) fara sa crape.
        """
        msg_id = str(uuid.uuid4())
        timestamp = datetime.now(timezone.utc).isoformat()

        # Serializare payload ca string JSON conform proto: string payload = 3;
        if isinstance(payload_dict, (dict, list)):
            payload_str = json.dumps(payload_dict, ensure_ascii=False)
        elif isinstance(payload_dict, str):
            # Daca e deja string, ne asiguram ca e JSON valid sau il impachetam
            try:
                json.loads(payload_dict)
                payload_str = payload_dict
            except Exception:
                payload_str = json.dumps({"text": payload_dict}, ensure_ascii=False)
        else:
            payload_str = json.dumps({"data": str(payload_dict)}, ensure_ascii=False)

        message = broker_pb2.Message(
            id=msg_id,
            topic=topic,
            payload=payload_str,
            timestamp=timestamp
        )

        try:
            ack = self.stub.Publish(message, timeout=self.timeout)
            if ack.success:
                print(f"[gRPC ACK] Mesaj livrat cu succes (id: {msg_id})! Detalii: {ack.detail}")
            else:
                print(f"[gRPC ACK RESPINS] Broker-ul a respins mesajul (id: {msg_id}): {ack.detail}")
            return ack

        except grpc.RpcError as e:
            status_code = e.code()
            details = e.details()
            if status_code == grpc.StatusCode.UNAVAILABLE:
                print(f"[gRPC EROARE] Broker-ul gRPC la {self.target} este INDISPONIBIL (server oprit sau port gresit).")
            elif status_code == grpc.StatusCode.DEADLINE_EXCEEDED:
                print(f"[gRPC TIMEOUT] A expirat timpul de asteptare ({self.timeout}s) pentru confirmarea mesajului.")
            else:
                print(f"[gRPC EROARE] Eroare RPC ({status_code}): {details}")
            return None

        except Exception as ex:
            print(f"[EROARE NECUNOSCUTA] Exceptie la publicare gRPC: {ex}")
            return None

    def close(self) -> None:
        """Inchide canalul gRPC."""
        try:
            self.channel.close()
            print("[gRPC] Canal inchis.")
        except Exception:
            pass
