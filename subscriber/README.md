# Subscriber Client (C# / .NET)

Client de tip **Subscriber** dezvoltat în C# (.NET 8) pentru un sistem distribuit de mesagerie Pub/Sub.
Suportă două moduri de transport:
1. **TCP raw** (implicit, port 5050): conectare prin socket direct, mesaje newline-delimited JSON (`\n`).
2. **gRPC streaming** (port 5051): apel de tip server-streaming bazat pe `broker.proto` (`rpc Subscribe(SubRequest) returns (stream Message)`).

---

## Caracteristici Cheie

### 1. Modul TCP Raw (`--mode tcp` - implicit)
- **Conectare & Handshake**:
  - Se conectează pe socket TCP la adresa Broker-ului (`127.0.0.1:5050` implicit).
  - Trimite handshake JSON: `{"role": "subscriber", "topic": "<topic>"}\n`.
- **Format Mesaj**:
  ```json
  {
    "id": "<uuid>",
    "topic": "<topic>",
    "payload": { ... },
    "timestamp": "<ISO8601>"
  }
  ```
- **Ascultare pe Task dedicat**: folosește `System.Text.Json` pentru parsarea mesajelor primite linie cu linie.

### 2. Modul gRPC Streaming (`--mode grpc`)
- **Definiție Proto (`broker.proto`)**:
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
    string payload = 3;
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
- **Server Streaming**: apelează RPC-ul `Subscribe(SubRequest)` și citește asincron fluxul de `Message` prin `call.ResponseStream.ReadAllAsync()`.
- **Generare cod**: realizată automat la `dotnet build` folosind pachetul `Grpc.Tools` configurat în `Subscriber.csproj`.

### 3. Reconectare Automată cu Exponential Backoff (Ambele Moduri)
- Dacă Broker-ul nu este disponibil la pornire sau conexiunea/stream-ul cade în timpul funcționării (`SocketException`, `RpcException`), clientul reîncearcă automat conectarea la intervale de **1s, 2s, 4s**.
- Dacă toate încercările eșuează, afișează un mesaj clar de eroare critică, fără crash sau blocare.

### 4. Închidere Curată (Graceful Shutdown)
- La `Ctrl+C` (SIGINT) sau terminarea procesului:
  - În modul **TCP**: socketul apelează `Shutdown(SocketShutdown.Both)` și se închide curat. Broker-ul detectează `EOF` (`null`) și dezabonează clientul din `TopicRegistry`.
  - În modul **gRPC**: tokenul de anulare oprește stream-ul și trimite `RST_STREAM` / `CANCEL`, declanșând `onCancelHandler` pe brokerul Java.
- **Niciun alt subscriber sau publisher conectat la broker nu este afectat.**

---

## Structura Proiectului (`subscriber/`)

- [`subscriber/Protos/broker.proto`](file:///c:/Users/Mihai/Desktop/PAD_laboratoare/subscriber/Protos/broker.proto): Fișierul de definiție protocol buffer gRPC.
- [`subscriber/SubscriberClient.cs`](file:///c:/Users/Mihai/Desktop/PAD_laboratoare/subscriber/SubscriberClient.cs): Implementarea clientului TCP (Connect, Listen, Reconnect, Close).
- [`subscriber/SubscriberGrpcClient.cs`](file:///c:/Users/Mihai/Desktop/PAD_laboratoare/subscriber/SubscriberGrpcClient.cs): Implementarea clientului gRPC streaming (Listen, Reconnect, Close).
- [`subscriber/Program.cs`](file:///c:/Users/Mihai/Desktop/PAD_laboratoare/subscriber/Program.cs): Parsare argumente (`--topic`, `--mode`, `--host`, `--port`) și tratare semnal `Ctrl+C`.
- [`subscriber/Subscriber.csproj`](file:///c:/Users/Mihai/Desktop/PAD_laboratoare/subscriber/Subscriber.csproj): Fișierul de proiect .NET 8 cu dependințe `Grpc.Net.Client`, `Google.Protobuf`, `Grpc.Tools`.

---

## Cum se Rulează

### 1. Rulare din folderul `subscriber`

Navigați în folderul `subscriber`:
```bash
cd subscriber
```

#### Modul TCP (implicit, port 5050):
```bash
# Terminal 1:
dotnet run --topic sport

# Terminal 2 (în paralel pe alt topic):
dotnet run --topic stiri

# Opțional cu host și port:
dotnet run --topic meteo --host 127.0.0.1 --port 5050
```

#### Modul gRPC (port 5051):
```bash
# Terminal 1:
dotnet run --topic sport --mode grpc

# Terminal 2 (în paralel pe alt topic):
dotnet run --topic stiri --mode grpc

# Opțional cu host și port:
dotnet run --topic meteo --mode grpc --host 127.0.0.1 --port 5051
```

---

### 2. Rulare din rădăcina depozitului

```bash
# Mod TCP:
dotnet run --project subscriber -- --topic sport
dotnet run --project subscriber -- --topic stiri

# Mod gRPC:
dotnet run --project subscriber -- --topic sport --mode grpc
dotnet run --project subscriber -- --topic stiri --mode grpc
```

### 3. Oprire Curată

Apăsați `Ctrl+C` în fereastra subscriberului pentru a-l deconecta curat fără erori.
