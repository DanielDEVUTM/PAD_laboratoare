# Broker Java (Pub/Sub Engine)

Acest proiect reprezintă un broker de mesaje **Publish/Subscribe** dezvoltat în **Java 17 (Maven)** care asigură livrarea de mesaje între Publisheri și Subscriberi conectați pe topic-uri dinamice.

---

## Arhitectură și Componente

1. **`BrokerServer`**:
   - Punctul de intrare principal.
   - Suportă un flag configurabil `--mode` (`tcp`, `grpc`, sau `both` - implicit `both`).
   - Ascultă pe **portul 5050** pentru conexiuni TCP brute.
   - Ascultă pe **portul 5051** pentru servicii gRPC.
   - Implementează un **Shutdown Hook** curat la `Ctrl+C`.

2. **`TopicRegistry`**:
   - Registrul central partajat, thread-safe.
   - Folosește `ConcurrentHashMap<String, List<ConnectionHandler>>` cu liste `CopyOnWriteArrayList` instanțiate dinamic (`computeIfAbsent`) la primul abonat al unui topic.
   - Gestionează un **backlog temporar** (`ConcurrentLinkedQueue<Message>`) pentru fiecare topic; dacă un mesaj este publicat pe un topic fără abonati activi, mesajul este păstrat în backlog și livrat imediat primului subscriber care se abonează ulterior.
   - Deconectarea unui client elimină handler-ul din listă fără să afecteze funcționarea brokerului sau a celorlalți clienți.

3. **`ConnectionHandlerImpl` (TCP)**:
   - Fiecare socket acceptat pe portul 5050 este procesat pe un thread separat dintr-un `ExecutorService` (`newCachedThreadPool`).
   - Citește prima linie JSON pentru handshake: `{"role":"publisher"|"subscriber", "topic":"..."}`.
   - **Subscriber**: Rămâne conectat și ascultă până la deconectare (detectată de `readLine() == null` sau `IOException`), moment în care este dezabonat.
   - **Publisher**: Rămâne într-o buclă citind mesaje JSON, le validează/deserializează, apelează `topicRegistry.broadcast(...)` și trimite răspunsul `{"status":"ok"}` sau `{"status":"error"}`.

4. **`GrpcBrokerServiceImpl` & `GrpcConnectionHandler` (gRPC)**:
   - Implementează serviciul gRPC definit în `broker.proto`.
   - RPC `Publish(Message)`: Validează mesajul și face broadcast reutilizând `TopicRegistry`.
   - RPC `Subscribe(SubRequest)` (server streaming): Creează o adaptare `GrpcConnectionHandler` în `TopicRegistry` și trimite mesaje în timp real prin stream-ul `StreamObserver<Message>`.

---

## De ce TCP și nu UDP pentru Protocolul de Transport?

Protocolul de transport ales pentru brokerul de mesagerie este **TCP** (precum și **gRPC** construit peste HTTP/2 - TCP) din următoarele motive fundamentale:

1. **Garanția Livrării (Reliable Delivery)**: TCP oferă retransmitere automată în cazul pierderii pachetelor pe rețea. În mesagerie pub/sub, pierderea unui mesaj de comandă sau eveniment este inacceptabilă. UDP nu garantează sosirea pachetelor.
2. **Păstrarea Ordinii (Ordered Delivery)**: TCP garantează că mesajele ajung la subscriberi exact în ordinea în care au fost trimise de publisher. UDP poate reordona pachetele.
3. **Controlul Fluxului și al Congestiei (Flow & Congestion Control)**: TCP previne copleșirea subscriberilor sau a brokerului în caz de trafic intens.
4. **Detectarea Deconectărilor**: Starea conexiunii TCP permite brokerului să detecteze imediat deconectarea unui abonat sau publisher (`socket.close()`, `EOF`).

---

## Structura Protocolului

### Protocol TCP (Port 5050, JSON delimitat prin `\n`)
- **Handshake Publisher**: `{"role": "publisher", "topic": "senzor/temperatura"}`
- **Handshake Subscriber**: `{"role": "subscriber", "topic": "senzor/temperatura"}`
- **Format Mesaj**:
  ```json
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "topic": "senzor/temperatura",
    "payload": {"valoare": 23.5},
    "timestamp": "2026-09-16T15:30:00Z"
  }
  ```
- **Răspuns Broker**: `{"status":"ok"}` sau `{"status":"error","reason":"..."}`

### Protocol gRPC (Port 5051, `broker.proto`)
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

---

## Compilare și Rulare

### 1. Compilare Proiect
```bash
mvn clean compile
```

### 2. Rulare Broker

- **Mod Ambele (TCP pe 5050 + gRPC pe 5051 - Implicit)**:
  ```bash
  mvn exec:java -Dexec.mainClass="com.pubsub.broker.BrokerServer"
  ```
  sau
  ```bash
  mvn exec:java -Dexec.mainClass="com.pubsub.broker.BrokerServer" -Dexec.args="--mode=both"
  ```

- **Mod Doar TCP (Port 5050)**:
  ```bash
  mvn exec:java -Dexec.mainClass="com.pubsub.broker.BrokerServer" -Dexec.args="--mode=tcp"
  ```

- **Mod Doar gRPC (Port 5051)**:
  ```bash
  mvn exec:java -Dexec.mainClass="com.pubsub.broker.BrokerServer" -Dexec.args="--mode=grpc"
  ```
