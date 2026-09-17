"""
publisher_client.py
Modul ce contine clasa PublisherClient pentru conectarea si trimiterea de mesaje
la Broker-ul pub/sub prin socket TCP conform protocolului definit.
"""

import json
import logging
import socket
import sys
import time
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple

# Asigurare compatibilitate encodare consola Windows
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

logger = logging.getLogger("PublisherClient")


class PublisherClient:
    """
    Client TCP pentru Publisher intr-un sistem Pub/Sub.
    
    Protocol:
      - Handshake: {"role": "publisher", "topic": "<topic>"}
      - Mesaj: {"id": "<uuid>", "topic": "<topic>", "payload": <data>, "timestamp": "<ISO8601>"}
      - Raspuns Broker (ACK): {"status": "ok"} sau {"status": "error", "reason": "..."}
    """

    def __init__(self, host: str = "127.0.0.1", port: int = 5050, topic: str = "", timeout: float = 5.0):
        self.host = host
        self.port = port
        self.topic = topic
        self.timeout = timeout
        self.sock: Optional[socket.socket] = None
        self.is_connected: bool = False
        self._buffer: str = ""

    def connect(self, retries: int = 3, backoff_delays: Tuple[int, ...] = (1, 2, 4)) -> bool:
        """
        Initiaza conexiunea TCP la Broker si trimite Handshake-ul.
        Daca Broker-ul nu este disponibil, incearca reconectarea cu backoff (1s, 2s, 4s).
        Daca toate incercarile esueaza, raporteaza o eroare clara fara sa blocheze sau sa crape aplicatia.
        """
        self.close()

        for attempt in range(1, retries + 1):
            try:
                print(f"[RETEA] Incercare de conectare la Broker ({self.host}:{self.port}) [incercarea {attempt}/{retries}]...")
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.settimeout(self.timeout)
                s.connect((self.host, self.port))
                self.sock = s
                self.is_connected = True
                self._buffer = ""
                print(f"[RETEA] Conectat cu succes la Broker ({self.host}:{self.port})!")

                # Trimitere Handshake
                handshake_payload = {
                    "role": "publisher",
                    "topic": self.topic
                }
                self._send_raw_json(handshake_payload)
                print(f"[HANDSHAKE] Handshake trimis cu succes pentru topicul: '{self.topic}'")
                return True

            except (socket.error, ConnectionRefusedError, TimeoutError) as e:
                print(f"[EROARE] Nu s-a putut conecta la Broker ({self.host}:{self.port}): {e}")
                self.close()

                if attempt < retries:
                    delay = backoff_delays[attempt - 1] if (attempt - 1) < len(backoff_delays) else backoff_delays[-1]
                    print(f"[BACKOFF] Reincercare in {delay}s...")
                    time.sleep(delay)
                else:
                    print(f"[EROARE CRITICA] S-au epuizat toate cele {retries} incercari de conectare. Broker-ul este momentan indisponibil.")
                    return False

        return False

    def publish(self, payload: Any) -> Optional[Dict[str, Any]]:
        """
        Impacheteaza si trimite un mesaj conform specificatiilor:
          - id: uuid4
          - topic: topicul curent
          - payload: datele mesajului
          - timestamp: ISO8601 UTC
        Asteapta si returneaza raspunsul ACK de la Broker.
        Daca apare o eroare de conexiune, incearca reconectarea automata.
        """
        if not self.is_connected or not self.sock:
            print("[AVERTISMENT] Clientul nu este conectat. Se incearca reconectarea...")
            if not self.connect():
                print("[EROARE] Trimiterea mesajului a esuat deoarece reconectarea nu a reusit.")
                return None

        # Generare id si timestamp conform protocolului
        message = {
            "id": str(uuid.uuid4()),
            "topic": self.topic,
            "payload": payload,
            "timestamp": datetime.now(timezone.utc).isoformat()
        }

        try:
            self._send_raw_json(message)
        except (socket.error, BrokenPipeError, ConnectionResetError) as e:
            print(f"[EROARE RETEA] S-a pierdut conexiunea in timpul trimiterii: {e}")
            self.close()
            print("[RECONECTARE] Se incearca restabilirea conexiunii...")
            if self.connect():
                try:
                    self._send_raw_json(message)
                except Exception as retry_err:
                    print(f"[EROARE] Reincercarea trimiterii mesajului a esuat: {retry_err}")
                    return None
            else:
                print("[EROARE] Mesajul nu a putut fi transmis. Broker indisponibil.")
                return None

        # Citire confirmare (ACK) de la Broker
        try:
            ack = self._read_response()
            if ack:
                status = ack.get("status")
                if status == "ok":
                    print(f"[ACK] Mesaj livrat cu succes la Broker (id: {message['id']}).")
                else:
                    reason = ack.get("reason", "Motiv nespecificat")
                    print(f"[ACK EROARE] Broker-ul a respins mesajul: {reason}")
                return ack
            else:
                print("[AVERTISMENT] Broker-ul a inchis conexiunea inainte de a trimite ACK.")
                self.close()
                return None
        except socket.timeout:
            print("[AVERTISMENT] Timeout la asteptarea ACK-ului de la Broker.")
            return None
        except Exception as e:
            print(f"[EROARE] Exceptie la receptia ACK-ului: {e}")
            self.close()
            return None

    def _send_raw_json(self, data: Dict[str, Any]) -> None:
        """Serializare JSON si trimitere pe socket cu delimitator newline (\\n)."""
        if not self.sock:
            raise socket.error("Socket-ul nu este initializat.")
        json_bytes = (json.dumps(data, ensure_ascii=False) + "\n").encode("utf-8")
        self.sock.sendall(json_bytes)

    def _read_response(self) -> Optional[Dict[str, Any]]:
        """
        Citeste date din socket pana la intalnirea caracterului newline (\\n)
        si returneaza obiectul JSON deserializat.
        """
        if not self.sock:
            return None

        while "\n" not in self._buffer:
            chunk = self.sock.recv(4096)
            if not chunk:
                # Conexiunea a fost inchisa de peer
                return None
            self._buffer += chunk.decode("utf-8", errors="replace")

        line, self._buffer = self._buffer.split("\n", 1)
        line = line.strip()
        if not line:
            return None

        try:
            return json.loads(line)
        except json.JSONDecodeError as err:
            print(f"[EROARE PARSARE] Raspunsul primit de la Broker nu este JSON valid: '{line}' ({err})")
            return None

    def close(self) -> None:
        """Inchide conexiunea socket in siguranta."""
        if self.sock:
            try:
                self.sock.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        self.is_connected = False
        self._buffer = ""
