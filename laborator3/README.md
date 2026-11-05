# Laborator 3 — Web Proxy / Data Warehouse (Java, MongoDB, Redis, React)

Realizarea transparenței în distribuire: clientul (Postman sau UI) vorbește **numai cu gateway-ul**
(`http://localhost:8000`) și nu știe câte noduri Data Warehouse există, unde rulează sau ce port au.

```
                         ┌───────────── gateway :8000 ─────────────┐
 Postman / UI  ───HTTP──▶│ service discovery · health check        │──▶ dw-1 ┐
                         │ round-robin · prioritate · cache        │──▶ dw-2 ├─▶ MongoDB replica set
                         │ circuit breaker + retry                 │──▶ dw-3 ┘   mongo1 · mongo2 · mongo3
                         └───────────────┬─────────────────────────┘
                                         └──▶ Redis (cache)
```

| Componentă | Tehnologie | Rol |
|---|---|---|
| `dw-node/` | Java 21, Spring Boot | API REST (CRUD complet) peste MongoDB; se înregistrează singur în gateway |
| `gateway/` | Java 21, Spring Boot | proxy transparent: discovery, health check, round-robin, cache, prioritate, circuit breaker |
| `ui/` | React + Tailwind (Vite) | vezi ce conțin bazele de date, rulezi CRUD, urmărești gateway-ul |
| `infra/` | mongosh | inițializare replica set + script de populare (20 angajați, aceleași date la fiecare pornire) |
| `docker-compose.yml`, `k8s/` | Docker, Kubernetes | rulare completă |

## Pornire

```bash
cd laborator3
docker compose up -d --build        # prima dată durează câteva minute (build Maven + npm)
```

- **UI:** http://localhost:3000 — **Gateway/API:** http://localhost:8000 (deschis, fără autentificare)
- Nodurile DW și bazele de date **nu** sunt publicate pe host (ca în lumea reală).
- Oprire: `docker compose down` (păstrează datele) sau `docker compose down -v` (resetează la datele inițiale).
- Colecție Postman: `postman/lab3.postman_collection.json` (variabila `gateway`).
- Verificare automată a tuturor cerințelor: `./scripts/smoke-test.sh` (și `--chaos` pentru oprit/înghețat noduri și baze).

## API (fără verbe în URL)

| Metodă | URL | Descriere |
|---|---|---|
| GET | `/employees?offset=0&limit=10&department=&id=&updatedSince=` | listă paginată / filtrare |
| GET | `/employees/{id}` | un angajat |
| POST | `/employees` | creare (id generat) → 201 + `Location` |
| PUT | `/employees/{id}` | creare sau înlocuire (upsert) → 201 / 200 |
| DELETE | `/employees/{id}` | ștergere → 204 / 404 |

**JSON sau XML (bonus 2):** formatul răspunsului = `?format=json|xml` (sau `Accept`); formatul cererii = `Content-Type: application/json|application/xml`.
Se pot combina (trimiți JSON, primești XML).

Câmpuri: `id, firstName*, lastName*, email*, department, position, salary, hireDate (YYYY-MM-DD), updatedAt` (*obligatorii).

Endpoint-uri ale gateway-ului: `GET /registry/instances`, `POST /registry/instances`, `DELETE /registry/instances/{id}`,
`GET /admin/state`, `GET /admin/events`, `GET /admin/instances/{id}/employees|db` (conținutul unei baze anume, ocolind balansarea),
`POST /admin/priority-tests`.

## Cum e rezolvat fiecare punct din cerință

**Nota 5 — CRUD complet, NoSQL, JSON/XML, scalabilitate, gateway.**
Baza este MongoDB (document-based). Același API rulează în 3 instanțe (`dw-1..3`); `dw-4`, `dw-5` se adaugă copiind blocul din compose.
Fiecare instanță se înregistrează singură în gateway (`POST /registry/instances`, repetat la 3 s și deregistrare la oprire).
Procesare concurentă: Tomcat, **thread-per-request** (până la 200 de fire pe nod, 400 pe gateway). Colecțiile thread-safe
sunt înlocuite de atomicitatea operațiilor MongoDB; în gateway se folosesc `ConcurrentHashMap`, `AtomicLong`, `PriorityBlockingQueue`.
Maparea cererilor HTTP pe servicii se face în controllere (`EmployeeController`, `ProxyController`, `RegistryController`).

**Service discovery + health check.** Registrul e în gateway. La fiecare secundă gateway-ul face `GET /health` pe fiecare nod
(asincron, deci un nod blocat nu întârzie verificarea celorlalte). După 2 eșecuri nodul devine `DOWN` și iese din rotație;
când reapare (health OK sau heartbeat nou) intră singur înapoi. Un nod `DOWN` > 60 s fără heartbeat este scos din registru.

**Round-robin.** Cererile merg pe rând la nodurile disponibile (`InstanceRegistry.pick`). Răspunsul conține `X-Served-By`.

**Sincronizarea bazelor.** MongoDB replica set cu 3 membri: replicarea e încorporată, doar o configurăm (`infra/mongo-init.sh`).
Scrierile se confirmă cu `w=majority` (mai lente), citirile se fac de pe secundari (`readPreference=secondaryPreferred`, mai rapide):
exact compromisul „scrieri mai lente, citiri mai rapide” din curs. În UI, panoul „Databases” arată conținutul fiecărui nod
și dacă toate sunt sincronizate.

**Cache (Redis).** Doar `GET` cu 200, TTL 30 s, cheie = URL + `Accept`. Orice scriere reușită incrementează o versiune globală
inclusă în chei → tot cache-ul vechi devine inaccesibil imediat. O mică „fereastră de gardă” (1,5 s) după scriere împiedică
stocarea unei citiri vechi de la o replică încă nesincronizată. Dacă Redis cade, gateway-ul merge mai departe fără cache.
Răspunsul are `X-Cache: HIT|MISS|BYPASS` (`Cache-Control: no-cache` ocolește cache-ul).

**Bonus 1 — Docker și Kubernetes.** Vezi secțiunea de mai jos.

**Bonus 3 — Prioritate.** Header `X-Priority: high|normal|low`. Gateway-ul are un număr limitat de „workeri” spre noduri (8);
când sunt toți ocupați, cererile așteaptă într-un `PriorityBlockingQueue` ordonat după prioritate, apoi după sosire.
O cerere `high` depășește tot ce e în coadă. Demo: butonul „Run test” din UI sau `POST /admin/priority-tests`
(30 cereri lente + 1 `high`; testul ruleazā în gateway pentru că un browser nu poate satura gateway-ul — are limită de ~6 conexiuni).

**Bonus 4 — Circuit breaker.** Gateway-ul **ține minte cererea** (metodă, URL, headere, corp). Dacă un nod nu răspunde în 2 s
(sau conexiunea eșuează), conexiunea e tăiată, circuitul spre nod devine `OPEN` (10 s fără trafic), iar **aceeași cerere se retrimite
altui nod**; clientul primește răspunsul normal (`X-Attempts: 2`). După 10 s circuitul trece în `HALF_OPEN`: o singură cerere de probă;
dacă reușește → `CLOSED`. Un răspuns HTTP 502/503/504 de la nod declanșează doar retry, nu deschide circuitul.
Gateway-ul adaugă `X-Request-Id` și nodul îl folosește ca id la `POST` fără id → un `POST` retrimis nu creează duplicate.
Demo: `docker compose pause dw-2` (nodul acceptă conexiunea și nu răspunde niciodată) sau header `X-Simulate-Delay-Ms: 3000`.

**Bonus 5 — Alegerea unui master.** Replica set-ul MongoDB are 3 membri: un PRIMARY și 2 SECONDARY. Dacă primarul cade,
ceilalți doi aleg automat un nou primar în câteva secunde, iar scrierile continuă. Demo: `docker compose stop mongo1` (sau care e primar —
îl vezi în UI) și urmărești panoul „Databases”. *Notă de proiectare:* cele 3 baze sunt membri ai aceluiași replica set (nu 3 baze
independente cu sincronizare scrisă de noi); așa sincronizarea și alegerea masterului sunt cele încorporate în MongoDB.

> **De ce nu Firebase?** E serviciu cloud gestionat: nu poți rula 3 replici proprii, nu poți opri „primarul” ca să demonstrezi
> alegerea masterului, cere cont și internet, iar sincronizarea nu e configurabilă de noi. MongoDB (document NoSQL) acoperă
> cerințele laboratorului: replicare încorporată, failover automat, ușor de rulat local în Docker.

## Docker și Kubernetes (pentru explicație la apărare)

**Docker.** Fiecare serviciu are un `Dockerfile` multi-stage (build Maven/npm într-o imagine mare, rulare într-una mică JRE/nginx).
`docker-compose.yml` descrie toată rețeaua: 3 × mongo (cu volume), `mongo-init` (job care face `rs.initiate` și populează datele,
rulează o dată), redis, gateway, ui și `dw-1..3`. Serviciile se găsesc după nume (DNS-ul rețelei Docker: `mongo1`, `redis`, `gateway`);
doar gateway-ul (8000) și UI-ul (3000) sunt publicate.

**Kubernetes** (`k8s/`, aplicat cu `./k8s/deploy.sh`):
- `mongo` = **StatefulSet** cu 3 pod-uri + **Service headless**: fiecare pod are nume DNS stabil (`mongo-0.mongo...`), necesar pentru că replica set-ul reține numele membrilor; fiecare are propriul volum (`volumeClaimTemplates`).
- `mongo-init` = **Job** (rulează o dată), scripturile vin dintr-un **ConfigMap** generat de kustomize din `infra/` (aceleași fișiere ca în compose).
- `dw` = **Deployment** cu 3 replici; `kubectl -n pad-lab3 scale deploy/dw --replicas=6` adaugă noduri care **se înregistrează singure** în gateway. `readinessProbe` pe `/health`; `NODE_ID` și `ADVERTISE_HOST` vin din metadata pod-ului (nume, IP).
- `gateway`, `redis`, `ui` = Deployment + **Service** (gateway și UI de tip NodePort: 30800 / 30300). Pentru UI folosește `kubectl -n pad-lab3 port-forward svc/gateway 8000:8000`, fiindcă UI-ul apelează `http://localhost:8000`.
- Imaginile se construiesc local; pe Docker Desktop sunt vizibile direct, pe kind/minikube trebuie încărcate (`kind load docker-image …` / `minikube image load …`).
- Dacă un pod `dw` moare, Deployment-ul îl recreează, iar gateway-ul îl scoate din rotație (health check) și îl reia după reînregistrare.

> Manifestele au fost randate cu `kubectl kustomize` (12 obiecte) dar **nu au fost aplicate pe un cluster real** (nu exista unul pe mașina de dezvoltare).

## Configurare (variabile de mediu ale gateway-ului)

`REQUEST_TIMEOUT_MS=2000`, `MAX_ATTEMPTS=3`, `PROXY_WORKERS=8`, `BREAKER_COOLDOWN_MS=10000`, `HEALTH_INTERVAL_MS=1000`,
`HEALTH_FAILURES_TO_DOWN=2`, `CACHE_TTL_SECONDS=30`, `CACHE_WRITE_GUARD_MS=1500`, `REDIS_HOST=redis`.

## Limitări cunoscute

- Gateway-ul e o singură instanță, iar registrul este în memorie (se reconstruiește în ~3 s, când nodurile se reînregistrează).
- Un `DELETE` retrimis după timeout poate întoarce 404 dacă prima încercare a reușit totuși.
- Header-ul `X-Simulate-Delay-Ms` este o cârjă de demonstrație; într-un sistem real ar fi dezactivat.
