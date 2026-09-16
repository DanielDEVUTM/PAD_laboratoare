# Subscriber Client (C# / .NET)

Client de tip **Subscriber** dezvoltat în C# (.NET 8) pentru un sistem distribuit de mesagerie Pub/Sub, conectat prin socket TCP direct la Brokerul de mesaje (Java).

---

## Caracteristici Cheie

1. **Protocol TCP direct & Handshake JSON**:
   - Conectare pe socket TCP la adresa Broker-ului (`127.0.0.1:5050` implicit).
   - Trimitere handshake la conectare: `{"role": "subscriber", "topic": "<topic>"}\n`.
   - Primire mesaje JSON transmise linie cu linie (`\n`) de către Broker:
     ```json
     {
       "id": "<uuid>",
       "topic": "<topic>",
       "payload": { ... },
       "timestamp": "<ISO8601>"
     }
     ```
2. **Citire continuă pe Task/Thread dedicat**:
   - Folosește `System.Text.Json` pentru parsarea asincronă și performantă a mesajelor primite.
   - Afișează clar în consolă topicul, timestamp-ul, id-ul și payload-ul structurat.
3. **Reconectare automată cu Exponential Backoff**:
   - Dacă Broker-ul nu este pornit sau conexiunea de rețea cade brusc, clientul reîncearcă automat conectarea la intervale de **1s, 2s, 4s**.
   - Dacă toate încercările eșuează, afișează un mesaj de eroare critică clar, fără crash sau excepții netratate.
4. **Închidere curată (Graceful Shutdown)**:
   - La `Ctrl+C` (SIGINT) sau ieșirea din proces, socketul TCP apelează `Shutdown` și este închis curat.
   - Trimiterea semnalului de închidere generează un EOF (`null`) pe serverul Java, permițând brokerului să șteargă subscriber-ul din registrul de topicuri (`TopicRegistry`) fără a afecta restul sistemului sau ceilalți clienți.
5. **Suport pentru clienți multipli în paralel**:
   - Se pot deschide oricâte terminale/instanțe, fiecare abonat la un topic distinct (ex: `sport`, `stiri`, `meteo`).

---

## Structura Proiectului

- `SubscriberClient.cs`: Clasa principală a clientului ce conține metodele `ConnectAsync`, `ListenAsync`, `ReconnectAsync` și `Close`.
- `Program.cs`: Punctul de intrare (Main), parsarea argumentelor (`--topic`, `--host`, `--port`), capturarea semnalelor `Console.CancelKeyPress` și gestionarea ciclului de viață.
- `Subscriber.csproj`: Proiectul .NET configurat pe `net8.0`.

---

## Cum se Rulează

### 1. Rulare din folderul `subscriber`

Deschideți un terminal în folderul `subscriber`:
```bash
cd subscriber
```

Porniți subscriberul specificând topicul dorit:
```bash
# Terminal 1 (pentru topicul sport):
dotnet run --topic sport

# Terminal 2 (pentru topicul stiri):
dotnet run --topic stiri

# Terminal 3 (opțiuni avansate host/port):
dotnet run --topic meteo --host 127.0.0.1 --port 5050
```

### 2. Rulare din rădăcina depozitului

Dacă vă aflați în rădăcina proiectului:
```bash
# Terminal 1:
dotnet run --project subscriber -- --topic sport

# Terminal 2:
dotnet run --project subscriber -- --topic stiri
```

### 3. Oprire Curată

Apăsați `Ctrl+C` în orice terminal pentru a deconecta curat acel subscriber.
