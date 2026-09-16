"""
main.py
Punctul de intrare principal pentru aplicatia Publisher.
Suporta doua moduri de transport:
  - TCP raw (implicit) conform cerintelor initiale
  - gRPC conform broker.proto
"""

import argparse
import json
import sys

# Compatibilitate encoding consola Windows
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Publisher Python pentru sistemul de mesagerie Pub/Sub (TCP sau gRPC)."
    )
    parser.add_argument(
        "--topic",
        type=str,
        required=True,
        help="Numele topicului pe care publica aceasta instanta (ex: sport, stiri, meteo)."
    )
    parser.add_argument(
        "--mode",
        type=str,
        choices=["tcp", "grpc"],
        default="tcp",
        help="Modul de transport: 'tcp' (implicit, socket raw) sau 'grpc' (apeluri RPC)."
    )
    parser.add_argument(
        "--host",
        type=str,
        default="127.0.0.1",
        help="Adresa IP a Broker-ului (implicit: 127.0.0.1)."
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="Portul Broker-ului (implicit: 5050 pentru TCP, 50051 pentru gRPC)."
    )
    args = parser.parse_args()

    # Setare port implicit in functie de mod daca nu este specificat
    if args.port is None:
        args.port = 5050 if args.mode == "tcp" else 50051

    return args


def display_banner(topic: str, mode: str, host: str, port: int) -> None:
    print("\n" + "=" * 62)
    print("           SISTEM PUB/SUB - CLIENT PUBLISHER (Python)")
    print("=" * 62)
    print(f"  Mod transport : {mode.upper()}")
    print(f"  Topic activ   : {topic}")
    print(f"  Broker tinta  : {host}:{port}")
    print("-" * 62)
    print("  Instructiuni:")
    print("   - Introduceti text simplu sau un obiect JSON si apasati Enter.")
    if mode == "tcp":
        print("   - Tastati 'reconnect' pentru reconectare manuala la Broker.")
    print("   - Tastati 'exit' sau 'quit' (ori Ctrl+C) pentru a iesi.")
    print("=" * 62 + "\n")


def main() -> None:
    args = parse_arguments()
    display_banner(args.topic, args.mode, args.host, args.port)

    # Initializare client in functie de modul ales
    if args.mode == "tcp":
        from publisher_client import PublisherClient
        client = PublisherClient(host=args.host, port=args.port, topic=args.topic)
        connected = client.connect()
        if not connected:
            print("[NOTA] Puteti tasta un mesaj oricand; clientul va reincerca automat conectarea,")
            print("       sau tastati 'reconnect' pentru a forta reconectarea manuala.\n")
    else:
        from publisher_grpc_client import PublisherGrpcClient
        client = PublisherGrpcClient(host=args.host, port=args.port)

    prompt = f"[{args.mode.upper()}:{args.topic}] > "

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

            if args.mode == "tcp" and line.lower() == "reconnect":
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

            # Trimitere mesaj in functie de mod
            if args.mode == "tcp":
                ack = client.publish(payload)
                if ack is None:
                    print("[AVERTISMENT] Mesajul TCP nu a primit confirmare. Verificati daca Broker-ul este pornit.\n")
                else:
                    print()
            else:
                ack = client.publish(topic=args.topic, payload_dict=payload)
                if ack is None:
                    print("[AVERTISMENT] Mesajul gRPC nu a primit confirmare.\n")
                else:
                    print()

    finally:
        client.close()
        print("[INFO] Conexiune inchisa. La revedere!")


if __name__ == "__main__":
    main()
