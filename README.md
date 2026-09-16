# Sistem Pub/Sub - Client Publisher (Python)

Aplicație client pentru rolul de **Publisher** într-un sistem distribuit de mesagerie de tip Publish/Subscribe (Pub/Sub).
Sistemul este alcătuit din:
- **Broker**: Java (TCP Server, implicit `127.0.0.1:5050`)
- **Publisheri**: Python (acest modul)
- **Subscriberi**: C#

---

## Cerințe și Protocol

### 1. Conectare și Handshake
La deschiderea conexiunii TCP către Broker, Publisher-ul trimite imediat un mesaj de înregistrare JSON pe o singură linie (delimitat cu `\n`):
```json
{"role": "publisher", "topic": "sport"}
```

### 2. Trimiterea Mesajelor
După handshake, fiecare mesaj publicat este serializat JSON pe o linie separată (`\n`):
```json
{
  "id": "c1f760e9-b5d2-4309-9069-fbbaee2a3cf2",
  "topic": "sport",
  "payload": {
    "text": "Echipa gazdă a înscris un gol!"
  },
  "timestamp": "2026-09-16T11:15:30.123456+00:00"
}
```

### 3. Confirmare de la Broker (ACK)
Pentru fiecare mesaj transmis, Broker-ul răspunde cu:
- Succes: `{"status": "ok"}`
- Eroare: `{"status": "error", "reason": "descriere eroare"}`

### 4. Reconectare cu Backoff
Dacă Broker-ul nu este pornit sau conexiunea se întrerupe, clientul încearcă reconectarea aplicând pauze exponențiale:
- Încercarea 1: eșec -> pauză 1 secundă
- Încercarea 2: eșec -> pauză 2 secunde
- Încercarea 3: eșec -> pauză 4 secunde
- La epuizarea încercărilor, afișează un mesaj clar de eroare, fără blocaj și **fără să crape programul**.

---

## Structura Proiectului

- `publisher_client.py`: Clasa `PublisherClient` (metodele `connect`, `publish`, `close`, citire bufferizată de stream TCP, generare automată UUID4 și timestamp ISO8601 UTC).
- `main.py`: Punctul de intrare cu argumente din linia de comandă (`argparse`) și buclă interactivă.
- `publisher.py`: Alias pentru `main.py` (pentru a putea rula `python publisher.py ...`).

---

## Rulare

### Sintaxă generală
```bash
python main.py --topic <nume_topic> [--host <ip_broker>] [--port <port_broker>]
```
sau
```bash
python publisher.py --topic <nume_topic> [--host <ip_broker>] [--port <port_broker>]
```

> **Notă:** Parametrii `--host` (implicit `127.0.0.1`) și `--port` (implicit `5050`) sunt opționali.

---

### Exemplu: Pornirea a 3 Publisheri în terminale separate

Deschideți 3 ferestre de terminal (PowerShell sau CMD) și rulați:

#### Terminal 1 (Topic: `sport`)
```bash
python publisher.py --topic sport --host 127.0.0.1 --port 5050
```

#### Terminal 2 (Topic: `stiri`)
```bash
python publisher.py --topic stiri --host 127.0.0.1 --port 5050
```

#### Terminal 3 (Topic: `meteo`)
```bash
python publisher.py --topic meteo --host 127.0.0.1 --port 5050
```

---

## Utilizare în bucla interactivă

1. **Text simplu:** Tastați orice mesaj și apăsați `Enter`. Acesta va fi împachetat automat ca `{"text": "<mesajul_dvs>"}`.
2. **Obiect JSON structurat:** Dacă introduceți direct un JSON valid (ex: `{"temperatura": 23.5, "oras": "Chisinau"}`), acesta va fi trimis direct ca obiect în `payload`.
3. **Comanda `reconnect`:** Tastați `reconnect` pentru a forța o reîncercare manuală de conectare la Broker.
4. **Ieșire:** Tastați `exit`, `quit` sau apăsați combinația `Ctrl+C`.
