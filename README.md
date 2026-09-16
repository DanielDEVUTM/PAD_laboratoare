# Sistem Pub/Sub - Client Publisher (Python)

Aplicație client pentru rolul de **Publisher** într-un sistem distribuit de mesagerie de tip Publish/Subscribe (Pub/Sub).
Suportă două moduri de transport:
1. **TCP raw** (implicit): socket TCP direct, mesaje newline-delimited JSON (`\n`).
2. **gRPC**: apeluri procedurale la distanță bazate pe protocol buffers (`broker.proto`).

---

## 1. Modul TCP raw

### Conectare și Handshake
La conectare, Publisher-ul trimite pe socket-ul TCP:
```json
{"role": "publisher", "topic": "sport"}
```

### Formatul Mesajelor
```json
{
  "id": "uuid4",
  "topic": "sport",
  "payload": {
    "text": "Mesaj de test"
  },
  "timestamp": "2026-09-16T11:15:30.123456+00:00"
}
```

### Răspuns Broker (ACK)
- `{"status": "ok"}` sau `{"status": "error", "reason": "..."}`

---

## 2. Modul gRPC

Definiția serviciului este declarată în `broker.proto`:

```protobuf
syntax = "proto3";
package pubsub;

service Broker {
  rpc Publish(Message) returns (Ack);
  rpc Subscribe(SubRequest) returns (stream Message);
}

message Message {
  string id = 1;
  string topic = 2;
  string payload = 3;   // JSON serializat ca string
  string timestamp = 4;
}

message Ack {
  bool success = 1;
  string detail = 2;
}

message SubRequest {
  string topic = 1;
}
```

### Generare Stub-uri Python (dacă se modifică proto)
```bash
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. broker.proto
```

---

## Ghid de Rulare

### Sintaxă generală:
```bash
python main.py --topic <nume_topic> [--mode {tcp,grpc}] [--host <adresa_ip>] [--port <port>]
```
sau folosind aliasul:
```bash
python publisher.py --topic <nume_topic> [--mode {tcp,grpc}] [--host <adresa_ip>] [--port <port>]
```

---

### Exemple de rulare:

#### Rulare în mod TCP (implicit, port 5050):
```bash
# Terminal 1:
python publisher.py --topic sport --mode tcp --host 127.0.0.1 --port 5050

# Terminal 2:
python publisher.py --topic stiri --mode tcp

# Terminal 3:
python publisher.py --topic meteo --mode tcp
```

#### Rulare în mod gRPC (port 50051):
```bash
# Terminal 1:
python publisher.py --topic sport --mode grpc --host 127.0.0.1 --port 50051

# Terminal 2:
python publisher.py --topic stiri --mode grpc

# Terminal 3:
python publisher.py --topic meteo --mode grpc
```

---

## Utilizare în bucla interactivă

1. **Text simplu:** Tastați mesajul și apăsați `Enter`. În TCP devine `{"text": "<mesaj>"}`, iar în gRPC este serializat ca string JSON.
2. **Obiect JSON structurat:** Dacă introduceți un JSON (ex: `{"scor": "2-1"}`), va fi transmis structurat.
3. **Comanda `reconnect`:** În modul TCP, reîncearcă manual conectarea la Broker.
4. **Ieșire:** Tastați `exit`, `quit` sau `Ctrl+C`.
