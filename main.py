"""
main.py
Punctul de intrare principal pentru aplicatia Publisher.
Gestioneaza argumentele CLI (argparse) si bucla interactiva de citire si trimitere mesaje.
"""

import argparse
import json
import sys
from publisher_client import PublisherClient

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Publisher Python pentru sistemul de mesagerie Pub/Sub."
    )
    parser.add_argument(
        "--topic",
        type=str,
        required=True,
        help="Numele topicului pe care publica aceasta instanta (ex: sport, stiri, meteo)."
    )
    parser.add_argument(
        "--host",
        type=str,
        default="127.0.0.1",
        help="Adresa IP a Broker-ului TCP (implicit: 127.0.0.1)."
    )
    parser.add_argument(
        "--port",
        type=int,
        default=5050,
        help="Portul TCP al Broker-ului (implicit: 5050)."
    )
    return parser.parse_args()


def display_banner(topic: str, host: str, port: int) -> None:
    print("\n" + "=" * 62)
    print("           SISTEM PUB/SUB - CLIENT PUBLISHER (Python)")
    print("=" * 62)
    print(f"  Topic activ   : {topic}")
    print(f"  Broker tinta  : {host}:{port}")
    print("-" * 62)
    print("  Instructiuni:")
    print("   - Introduceti text simplu sau un obiect JSON si apasati Enter.")
    print("   - Tastati 'reconnect' pentru reconectare manuala la Broker.")
    print("   - Tastati 'exit' sau 'quit' (ori Ctrl+C) pentru a iesi.")
    print("=" * 62 + "\n")


def main() -> None:
    args = parse_arguments()
    display_banner(args.topic, args.host, args.port)

    client = PublisherClient(host=args.host, port=args.port, topic=args.topic)

    # Conectare initiala la pornire
    connected = client.connect()
    if not connected:
        print("[NOTA] Puteti tasta un mesaj oricand; clientul va reincerca automat conectarea,")
        print("       sau tastati 'reconnect' pentru a forta reconectarea manuala.\n")

    prompt = f"[{args.topic}] > "

    try:
        while True:
            try:
                line = input(prompt).strip()
            except (EOFError, KeyboardInterrupt):
                print("\n[INFO] Oprire solicitata de utilizator.")
                break

            if not line:
                continue

            if line.lower() in ("exit", "quit"):
                print("[INFO] Inchidere publisher...")
                break

            if line.lower() == "reconnect":
                client.connect()
                continue

            # Impachetare payload:
            # Daca utilizatorul introduce un JSON valid (ex: {"score": 2}), il trimite ca obiect structurat.
            # Altfel, textul simplu este impachetat automat ca {"text": "<mesaj>"}.
            try:
                payload = json.loads(line)
                if not isinstance(payload, dict):
                    payload = {"text": line}
            except Exception:
                payload = {"text": line}

            # Trimitere mesaj
            ack = client.publish(payload)
            if ack is None:
                print("[AVERTISMENT] Mesajul nu a primit confirmare. Verificati daca Broker-ul este pornit.\n")
            else:
                print()  # Linie noua pentru claritate vizuala

    finally:
        client.close()
        print("[INFO] Conexiune inchisa. La revedere!")


if __name__ == "__main__":
    main()
