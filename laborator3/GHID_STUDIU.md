# Ghid de studiu — Laborator 3 (Web Proxy / Data Warehouse)

Scris pentru voi trei, ca să puteți explica și apăra laboratorul. Fiecare concept are în dreptul lui **unde îl găsiți în cod**.

---

## 0. Ce tehnologii am folosit (întrebare sigură la apărare)

| Parte | Alegere | Observație |
|---|---|---|
| **Gateway** | **scris de noi**, Java 21 + Spring Boot (`gateway/`) | nu Nginx/Traefik/Spring Cloud Gateway; astfel controlăm discovery, prioritatea, retry cu memorarea cererii și circuit breaker-ul |
| **Bază de date** | **MongoDB 7** (NoSQL, document), replica set | replicarea și alegerea masterului sunt încorporate |
| **Cache** | **Redis 7** (cheie-valoare, în memorie) | conectat la gateway |
| Limbaj / framework | Java 21, Spring Boot 3.4 (Spring Web, Spring Data MongoDB, validare), Tomcat | thread-per-request |
| Build | Maven | |
| Client Redis | Jedis | |
| Client HTTP în gateway | `java.net.http.HttpClient` (JDK) | |
| JSON / XML | Jackson + `jackson-dataformat-xml` | |
| **Sincronizare între warehouse-uri** | microserviciul `sync/` (Java, MongoDB change streams) | aceleași date în toate warehouse-urile |
| Orchestrator | biblioteca `docker-java` (Docker Engine API prin `docker.sock`) | creează/șterge warehouse-uri |
| UI | React 18 + Tailwind CSS 4 + Vite | servit de Nginx în container (doar fișiere statice) |
| Containere | Docker + Docker Compose | imagini: `eclipse-temurin:21`, `mongo:7`, `redis:7-alpine`, `nginx` |
| Kubernetes | manifeste + Kustomize | randate, **nerulate** pe un cluster real |
| Teste | `scripts/smoke-test.sh`, colecție Postman | |

Nu am implementat: alte strategii de balansare (random, least-connections), backend Kubernetes pentru orchestrator.

---

## 1. Ideea întregului laborator

**Transparența în distribuire**: clientul nu trebuie să știe că sistemul e distribuit (câte servere, unde, ce port). El vorbește cu un singur punct, **gateway-ul**.

Lanțul: `client → gateway → (unul din) API-uri → bază de date`.

| Termen | Ce înseamnă | La noi |
|---|---|---|
| **Data Warehouse (DW)** | nod care ține datele necesare clientului: API + bază de date | un *warehouse* = `wh-N-api` + replica set `wh-N-db-1/2/3` („1 + 2”) |
| **Microserviciu** | program mic, cu o singură responsabilitate, care comunică prin rețea | dw-node, gateway, orchestrator, redis |
| **Scalabilitate (orizontală)** | pui mai multe copii ale aceluiași serviciu, nu un server mai puternic | `POST /warehouses` creează încă un API + o bază |
| **Gateway / reverse proxy** | primește cererile clienților și le trimite mai departe | `gateway/` |
| **Service discovery** | nodurile se anunță singure („sunt treaz, adresa mea e…”) | `RegistryController`, `GatewayRegistration` |
| **Health check** | gateway-ul întreabă periodic „ești viu?” | `HealthChecker` |
| **Load balancing** | repartizarea cererilor între noduri | round-robin în `InstanceRegistry.pick` |
| **Caching** | rețin răspunsul la o cerere frecventă ca să nu mai întreb nodul | Redis, `CacheService` |

**De ce mai multe baze de date?** Aplicațiile mari (Instagram, Facebook) *citesc* mult mai mult decât *scriu*. Cu mai multe copii ale datelor, citirile se împart pe mai multe baze și merg mai repede. Prețul: o scriere trebuie propagată în toate copiile, deci scrierea devine mai lentă. Asta e **replicarea** (aceleași date pe toate nodurile). Nu o confundați cu **sharding-ul** (date *diferite* pe noduri diferite, ca să împarți volumul). Profesorul vrea replicare: aceleași date peste tot.

---

## 2. HTTP (protocolul din titlul laboratorului)

**Metode** și ce înseamnă (și dacă sunt *idempotente*, adică repetate dau același rezultat):

| Metodă | Rol | Idempotentă? | La noi |
|---|---|---|---|
| GET | citește | da | `GET /employees?offset=0&limit=10`, `GET /employees/1` |
| POST | creează | **nu** (repetat → duplicate) | `POST /employees` → 201 + `Location` |
| PUT | creează sau înlocuiește la un URL cunoscut | da | `PUT /employees/{id}` → 201 sau 200 |
| DELETE | șterge | da (efectul), dar a doua oară dă 404 | `DELETE /employees/{id}` → 204 |

**Coduri de stare**: 200 OK, 201 Created, 202 Accepted (am primit, se face în fundal — folosit la `/warehouses`), 204 No Content, 400 date invalide, 404 nu există, 409 conflict, 415 tip de conținut nesuportat, 502/503/504 probleme la server/gateway.

**REST, fără verbe în URL**: URL-ul e un *substantiv* (resursa), verbul e metoda HTTP. Corect: `DELETE /employees/5`. Greșit: `/deleteEmployee?id=5`. (Profesorul a cerut explicit.)

**Headere importante la noi**: `Content-Type` (formatul corpului trimis), `Accept` (formatul dorit la răspuns), `Location` (unde a fost creată resursa), `X-Priority`, `Cache-Control: no-cache`, `X-Cache`, `X-Served-By`, `X-Request-Id`.

**Content negotiation (bonus JSON/XML)**: clientul alege formatul. Răspuns: `?format=xml` sau `Accept: application/xml`. Cerere: `Content-Type: application/json` sau `application/xml`. Cod: `WebConfig` și `jackson-dataformat-xml` în `dw-node`.

**Pull vs push** (din enunț): *pull* = sursa întreabă periodic „s-a schimbat ceva?” (la noi `GET /employees?updatedSince=...`); *push* = DW inițiază cererea către sursă când se schimbă ceva.

---

## 3. Concurență: thread-per-request

Fiecare cerere HTTP e servită de **propriul fir de execuție (thread)** din pool-ul serverului (Tomcat, `server.tomcat.threads.max`). Așa mai multe cereri se procesează simultan.

Problema: dacă două fire modifică aceeași colecție în același timp apar erori. Soluții: colecții *thread-safe* (`ConcurrentHashMap`, `PriorityBlockingQueue`, `AtomicLong`) sau excludere reciprocă (`synchronized`). La noi:
- în **dw-node** datele sunt în MongoDB; scrierile sunt atomice la nivel de document, deci nu avem colecții de protejat în aplicație;
- în **gateway**: registrul e `ConcurrentHashMap`, contoarele `AtomicLong`, coada de priorități `PriorityBlockingQueue`; `pick()` e `synchronized`;
- în **orchestrator**: `synchronized(rsLock)` ca să nu se modifice replica set-ul de două ori în același timp.

**Controller** = clasa care mapează o cerere HTTP pe o operație a serviciului (`EmployeeController`, `RegistryController`, `ProxyController`, `WarehouseController`).

---

## 4. Funcțiile gateway-ului (cele cerute pe note)

### 4.1 Service discovery
Nodul face `POST /registry/instances {id, url}` la pornire și o dată la 3 s (dacă gateway-ul repornește, nodurile reapar singure). La oprire face `DELETE`. Registrul e un `ConcurrentHashMap` în memorie.
*De ce nu configurăm adresele manual?* Pentru că numărul și adresele nodurilor se schimbă oricând.

### 4.2 Health check
La fiecare secundă: `GET /health` pe fiecare nod, **asincron** (un nod blocat nu întârzie verificarea celorlalte). După 2 eșecuri → `DOWN`, iese din rotație. Dacă răspunde din nou → `UP`. Un nod `DOWN` mult timp și fără heartbeat este șters din registru.

### 4.3 Load balancing — round-robin
Cererile merg pe rând: 1→A, 2→B, 3→C, 4→A… Alte strategii (de știut ca alternative): *random*, *least connections* (cel mai puțin încărcat), *weighted round-robin*, *IP hash* (același client → același nod).

### 4.4 Caching (Redis)
- Se memorează doar `GET` reușite, cu **TTL** (30 s): după TTL intrarea dispare.
- **Invalidare**: după orice scriere reușită se incrementează un *număr de versiune* inclus în cheie → tot ce era în cache devine nefolosit imediat.
- **Fereastra de gardă** (1,5 s): imediat după o scriere nu stocăm citiri, pentru că o replică încă nesincronizată ar putea da date vechi și am pune date vechi în cache.
- Dacă Redis cade, gateway-ul merge mai departe fără cache (*graceful degradation*).
- `X-Cache: HIT` = din cache, `MISS` = de la nod, `BYPASS` = `Cache-Control: no-cache`.
- Redis = bază de date **cheie-valoare, în memorie**, foarte rapidă. De aceea e potrivită pentru cache.

### 4.5 Prioritatea cererilor (bonus)
Gateway-ul are un număr limitat de „workeri” (8) către noduri. Când toți sunt ocupați, cererile așteaptă într-un `PriorityBlockingQueue`, ordonat după `X-Priority` (high > normal > low), apoi după ordinea sosirii. O cerere `high` trece înaintea celor din coadă. Cod: `PriorityExecutor`.

### 4.6 Circuit breaker + retry (bonus)
Problema: un nod nu răspunde (nici nu moare clar, nici nu răspunde). Clientul nu vrea să aștepte la nesfârșit.
- **Timeout**: după 2 s tăiem conexiunea.
- **Circuit breaker** (ca siguranța electrică), stări:
  - `CLOSED` — normal, trece traficul;
  - `OPEN` — nodul e „tăiat”, nu mai primește cereri (10 s);
  - `HALF_OPEN` — după pauză trimitem *o singură* cerere de probă: reușește → `CLOSED`, eșuează → `OPEN`.
- **Gateway-ul ține minte cererea** (metodă, URL, headere, corp) și o **retrimite altui nod**, transparent pentru client (`X-Attempts: 2`). Fără memorie ar pierde cererea.
- **Pericol la retry**: un `POST` retrimis poate crea duplicate. Soluție la noi: gateway-ul trimite `X-Request-Id`, iar nodul îl folosește ca id al resursei → a doua încercare nu mai creează, întoarce ce există (**idempotență**).
- Diferența health check vs circuit breaker: health check = verificare *periodică, activă*; circuit breaker = reacție *imediată*, la cererea reală.
- Cod: `ProxyService.forwardWithRetry`, `InstanceRegistry` (stările).

---

## 5. Bazele de date NoSQL

| Tip | Exemplu | Idee |
|---|---|---|
| **Document** | **MongoDB** (alegerea noastră) | JSON-uri flexibile, fără schemă fixă |
| Cheie-valoare (dicționar) | Redis | `cheie → valoare`, foarte rapid |
| Column-family | Cassandra | coloane grupate, scrieri masive |
| Graf | Neo4j | noduri și relații |

SQL vs NoSQL: SQL = tabele, schemă fixă, JOIN, ACID strict; NoSQL = schemă flexibilă, scalare orizontală ușoară, de obicei consistență mai relaxată.

**Teorema CAP**: într-un sistem distribuit, când apare o partiție de rețea, alegi între **C**onsistență și **A**vailability (disponibilitate). MongoDB, la noi, favorizează consistența scrierilor (`w=majority`).

**MongoDB replica set** (esențial pentru bonusul 5):
- grup de baze (membri) cu aceleași date; **un PRIMARY** (primește scrierile) și **SECONDARY** (copiază de la el, pot servi citiri);
- **replicare încorporată** — profesorul a zis „doar o configurezi”;
- dacă primarul cade, secundarii **aleg** (election) un nou primar în câteva secunde, prin **vot majoritar** (de aceea 3 membri: 2 din 3 formează majoritate);
- `w=majority`: scrierea e confirmată după ce majoritatea a primit-o (mai lent, mai sigur);
- `readPreference=secondaryPreferred`: citim de pe secundari (mai rapid, posibil puțin în urmă);
- **consistență eventuală**: copiile ajung identice după un timp scurt, nu instantaneu;
- **initial sync**: când adaugi un membru nou (`rs.add`), MongoDB îi copiază singur toate datele (la noi un warehouse nou pornește direct cu 3 membri și e umplut de serviciul `sync`).
- **Structura „1 + 2” cerută la bonusul 5**: fiecare warehouse are propriul replica set de 3 baze; între warehouse-uri sincronizează microserviciul `sync` prin **change streams** (MongoDB emite un eveniment la fiecare insert/update/delete; serviciul îl aplică în celelalte warehouse-uri, last-writer-wins pe `updatedAt`).
- Comenzi: `rs.initiate`, `rs.add`, `rs.remove`, `rs.status()`, `db.hello()`.

**De ce nu Firebase**: e serviciu cloud gestionat; nu poți rula replici proprii, nu poți opri primarul ca să arăți alegerea, cere cont/internet.

---

## 6. Docker

**Idei de bază**
- **Imagine** = șablon read-only (sistem de fișiere + program). **Container** = o imagine pornită, un proces izolat. Imaginea e „clasa”, containerul e „obiectul”.
- Containerul nu e mașină virtuală: partajează nucleul sistemului, deci e mult mai ușor și pornește în secunde.
- **Dockerfile** = rețeta imaginii. Fiecare instrucțiune creează un *strat* (layer), reținut în cache; de aceea copiem `pom.xml` și descărcăm dependențele *înainte* de a copia codul (strat refolosit când schimbi doar codul).
- **Multi-stage build**: prima etapă (`maven`) compilează, a doua (`eclipse-temurin:21-jre`) conține doar rezultatul → imagine mică. Vezi `dw-node/Dockerfile`.
- **Rețea**: containerele din același network se găsesc după **nume** (DNS intern Docker): `redis`, `gateway`, `wh-1-db`. Doar ce e în `ports:` e accesibil din afară (`8000:8000` = port gazdă:port container).
- **Volum**: stocare care supraviețuiește containerului (altfel datele dispar când îl ștergi).
- **Variabile de mediu** (`environment`) configurează aplicația fără să recompilezi (`MONGO_URI`, `GATEWAY_URL`, `NODE_ID`).
- **`/var/run/docker.sock`**: socket prin care comanzi Docker. Orchestratorul îl montează ca să poată crea containere. Dă control total asupra Docker, deci e un acces privilegiat (ok pentru laborator, riscant în producție).

**Docker Compose** = descrie mai multe containere într-un fișier (`docker-compose.yml`): servicii, rețea, porturi, `depends_on`, `restart`. O comandă pornește tot. La noi compose conține doar părțile fixe; warehouse-urile le creează orchestratorul la runtime.

**Comenzi de știut**
```
docker build -t nume .        # construiește imaginea
docker run -d --name x -p 8080:8080 img   # pornește un container
docker ps / docker ps -a      # containere active / toate
docker logs -f x              # loguri
docker exec -it x sh          # intri în container
docker stop / start / rm -f x
docker pause / unpause x      # îngheață procesul (simulează un nod blocat)
docker compose up -d --build / down / ps / logs -f
```

---

## 7. Kubernetes

Docker rulează containere pe **o** mașină. **Kubernetes (K8s)** orchestrează containere pe un **cluster** de mașini: le pornește, le repornește când cad, le scalează, le expune în rețea. Ești declarativ: descrii *starea dorită* în YAML („vreau 3 replici”), iar K8s o menține.

| Obiect | Ce face | La noi (`k8s/`) |
|---|---|---|
| **Pod** | cea mai mică unitate: unul sau mai multe containere cu aceeași IP | fiecare API/DB/gateway |
| **Deployment** | menține N pod-uri identice (prin ReplicaSet), repornește pe cele căzute, face update treptat | `dw` (3 replici), `gateway`, `redis`, `ui` |
| **StatefulSet** | ca Deployment, dar pod-uri cu **nume stabil** (`mongo-0`, `mongo-1`) și **volum propriu** | `mongo` — replica set-ul memorează numele membrilor |
| **Service** | adresă stabilă + load balancing către pod-uri (pod-urile își schimbă IP-ul) | `gateway`, `redis`, `ui`, `mongo` |
| Tipuri de Service | `ClusterIP` (doar intern), `NodePort` (port pe fiecare nod), `LoadBalancer` (IP extern în cloud), **headless** (`clusterIP: None`, DNS direct către pod-uri) | gateway/ui = NodePort, mongo = headless |
| **ConfigMap** | configurări/fișiere injectate în pod | scripturile `mongo-init.sh` și `seed.js` |
| **Job** | rulează o dată până termină | `mongo-init` (rs.initiate + seed) |
| **Namespace** | „folder” logic pentru resurse | `pad-lab3` |
| **readinessProbe** | K8s trimite trafic doar la pod-uri care răspund la `/health` | `dw`, `gateway` |
| **PersistentVolumeClaim** | cerere de disc persistent | `volumeClaimTemplates` din StatefulSet |
| **Kustomize** | asamblează manifeste și generează ConfigMap din fișiere | `k8s/kustomization.yaml` |
| **HPA** (nu-l avem) | scalare automată după CPU | menționabil |

**Comenzi**
```
kubectl get pods -n pad-lab3 [-w]
kubectl describe pod X / kubectl logs X
kubectl scale deploy/dw --replicas=6 -n pad-lab3     # scalare: noile pod-uri se înregistrează singure
kubectl delete pod X          # Deployment îl recreează
kubectl port-forward svc/gateway 8000:8000 -n pad-lab3
kubectl apply -f ... / kubectl kustomize k8s
```

**Compose vs Kubernetes**: Compose = o mașină, simplu, bun pentru dezvoltare. K8s = cluster, auto-reparare (self-healing), scalare, update-uri treptate, descoperire de servicii; mai complex.

**Onestitate la apărare**: manifestele K8s au fost randate cu `kubectl kustomize` dar **nu rulate pe un cluster real**. Crearea dinamică a warehouse-urilor din UI merge doar pe Docker Compose; în K8s API-urile se scalează cu `kubectl scale`, dar un membru Mongo nou se adaugă manual.

---

## 8. Cum se leagă fiecare cerință de cod

| Cerință | Fișier |
|---|---|
| CRUD complet, fără verbe | `dw-node/.../EmployeeController.java` |
| JSON/XML | `WebConfig.java`, `Employee.java` (adnotări XML) |
| Auto-înregistrare | `GatewayRegistration.java` (nod), `RegistryController.java` (gateway) |
| Health check | `HealthChecker.java`, `HealthController.java` |
| Round-robin | `InstanceRegistry.pick` |
| Cache | `CacheService.java` |
| Prioritate | `PriorityExecutor.java`, `Priority.java` |
| Circuit breaker + retry | `ProxyService.java`, `InstanceRegistry.java` |
| Idempotență POST | `EmployeeService.create` (X-Request-Id) |
| Replica set „1 + 2”, seed | `infra/seed.js`, `orchestrator/WarehouseService.java` |
| Sincronizare între warehouse-uri | `sync/SyncManager.java` |
| Creare dinamică | `orchestrator/`, `ui/.../WarehousePanel.jsx` |
| UI cu conținutul bazelor | `ui/.../DatabasePanel.jsx` |
| Docker / K8s | `docker-compose.yml`, `*/Dockerfile`, `k8s/` |
| Teste | `scripts/smoke-test.sh` |

---

## 9. Întrebări probabile la apărare (cu răspuns scurt)

1. **De ce gateway?** Clientul cunoaște un singur punct; nu știe câte API-uri sunt, unde sunt; gateway-ul se ocupă de discovery, balansare, cache, erori.
2. **Cum află gateway-ul de un API nou?** Acesta se înregistrează singur (`POST /registry/instances`), repetat la 3 s.
3. **Ce se întâmplă dacă un API cade?** Health check îl marchează `DOWN` în ~2 s și iese din rotație; cererile în curs sunt retrimise altui nod.
4. **Diferența dintre health check și circuit breaker?** Health check e periodic și activ; circuit breaker reacționează imediat la o cerere reală care eșuează (timeout) și are stările CLOSED/OPEN/HALF_OPEN.
5. **De ce `POST` nu e idempotent și cum rezolvați retry-ul?** Repetat creează duplicate; folosim `X-Request-Id` ca id → reîncercarea întoarce resursa deja creată.
6. **Cum se sincronizează bazele?** Replicare încorporată MongoDB (replica set); nu am scris noi sincronizarea.
7. **Ce se întâmplă dacă cade baza primară?** Fiecare warehouse are 3 baze (1 + 2); cele 2 rămase aleg un nou PRIMARY prin vot majoritar în câteva secunde; scrierile continuă. (Demo: *Stop* pe membrul PRIMARY în UI.)
7b. **Cum ajung aceleași date în toate warehouse-urile?** Serviciul `sync` ascultă change stream-ul fiecărui warehouse și aplică modificările în celelalte (last-writer-wins pe `updatedAt`); un warehouse nou e sincronizat complet înainte să pornească API-ul.
8. **Replicare vs sharding?** Replicare = aceleași date pe toate nodurile (citiri rapide, toleranță la defecte); sharding = date diferite pe noduri (volum mare).
9. **De ce citirile sunt mai rapide și scrierile mai lente?** Citirile se împart pe replici; o scriere trebuie confirmată de majoritate (`w=majority`).
10. **De ce cache-ul poate da date vechi și cum evitați?** TTL + invalidare la scriere (versiune în cheie) + fereastră de gardă după scriere.
11. **Cum funcționează prioritatea?** Coadă de priorități în fața unui număr limitat de workeri; high trece înaintea celor din coadă.
12. **Thread-per-request?** Fiecare cerere = un thread din pool-ul Tomcat; protejăm datele partajate cu colecții thread-safe/`synchronized`.
13. **De ce MongoDB, nu Firebase sau SQL?** NoSQL document cerut de profesor; Firebase e cloud gestionat, fără control asupra replicării/alegerii; Mongo rulează local, cu replicare și failover.
14. **Cum adaug un warehouse?** `POST /warehouses` (sau butonul din UI): orchestratorul pornește 3 containere MongoDB și formează replica set-ul (`rs.initiate`), serviciul `sync` copiază datele din celelalte warehouse-uri, apoi pornește API-ul, care se înregistrează în gateway.
15. **Ce e un Dockerfile multi-stage?** Compilează într-o imagine mare și copiază doar rezultatul într-una mică.
16. **De ce StatefulSet pentru Mongo și Deployment pentru API?** Mongo are nevoie de identitate stabilă (nume, volum); API-urile sunt fără stare, interschimbabile.
17. **Ce face un Service în K8s?** Dă o adresă stabilă și balansează între pod-uri, care își schimbă IP-ul.
18. **Docker vs mașină virtuală?** Containerul partajează nucleul gazdei; e mai ușor și mai rapid.
19. **Riscul de la `docker.sock`?** Cine îl are controlează Docker-ul întregii mașini; acceptabil la laborator.
20. **Limitări ale soluției?** Un singur gateway (registru în memorie), K8s doar static și neactualizat la „1 + 2”, ștergerile făcute cât `sync` e oprit se pot pierde, `DELETE` retrimis poate da 404, `X-Simulate-Delay-Ms` e doar pentru demo.

---

## 10. Ce să exersați înainte de predare

1. `docker compose up -d --build`, apoi deschideți UI-ul și explicați fiecare panou.
2. Creați un warehouse din UI și arătați că are aceleași date; ștergeți-l.
3. În Postman: două `GET` identice (MISS → HIT), `?format=xml`, un `POST` + `GET` imediat.
4. *Freeze* pe un API → cererile merg; arătați evenimentele din „Gateway events”.
5. *Stop* pe DB-ul PRIMARY → apare un nou PRIMARY.
6. `./scripts/smoke-test.sh --chaos` ca dovadă că totul trece.
7. Fiecare membru să poată explica **toate** componentele, nu doar partea scrisă de el.

---

## 11. Cum testezi în Postman (pas cu pas)

**Pregătire:** `docker compose up -d --build`, așteaptă ~1–2 min. Import: *Import* → fișierul `postman/lab3.postman_collection.json`. Variabila `gateway` = `http://localhost:8000` (se vede la colecție → Variables). Totul merge la gateway; nu există alte adrese.

**A. Warehouse-uri (folderul „Warehouses (dynamic)”)**
1. `List warehouses` → vezi `wh-1..3`, fiecare cu `api` și `db.members` (3 baze).
2. `Create warehouse with a name` (`{"name":"paris"}`) → răspuns **202**. Repetă `List warehouses` până `phase` devine `ready` (trece prin „starting 3 databases”, „forming replica set”, „synchronising data”, „starting API”).
3. `Registered instances` → apare `paris` cu `status: UP`.
4. `Remove warehouse` (`paris`) → 202; după ~10 s dispare.

**B. CRUD + format (foldere „CRUD (JSON)” și „JSON / XML flag”)**
1. `Create (POST)` → **201** + header `Location`; copiază `id` din răspuns.
2. `Get by id` cu acel id → 200. `Create/replace (PUT)` pe un id → 201 prima dată, 200 a doua oară. `Delete` → 204, apoi `Get` → 404.
3. `List as XML (?format=xml)` și `Create from XML body` → răspuns/corp XML. Poți trimite JSON și cere răspuns XML.
4. În tab-ul *Headers* al unei cereri: `Accept: application/xml` schimbă formatul.

**C. Round-robin și cache**
1. Rulează `Get by id` de 4–6 ori cu *Cache-Control: no-cache* (cererea „Cache bypass”): headerul de răspuns `X-Served-By` se schimbă între `wh-1`, `wh-2`, `wh-3`.
2. Rulează `Cache: run twice`: prima dată `X-Cache: MISS`, a doua `HIT` (și `X-Served-By: cache`).
3. Fă un `POST`/`PUT`, apoi imediat `GET` → `MISS` (cache invalidat).

**D. Sincronizarea între warehouse-uri (folderul „Sync & replica sets”)**
1. Fă `Create (POST)` și notează `id`.
2. După 2–3 s rulează `Content of the database of wh-1`, `…wh-2`, `…wh-3`: **același document apare în toate trei** (caută `id`-ul în `items`).
3. `Sync service status` → fiecare warehouse `READY`, `pendingChanges: 0`.
4. `Delete` pe acel id → dispare din toate trei.

**E. Structura „1 + 2” și alegerea masterului**
1. `Replica set of wh-1` → `members`: 3 baze, una `PRIMARY` și două `SECONDARY`.
2. În folderul „Warehouses”: `Stop DB member 1 of wh-1` (dacă membrul 1 e PRIMARY; altfel schimbă numărul cu al primarului în URL: `/warehouses/wh-1/db/2`).
3. Așteaptă ~10 s și rulează din nou `Replica set of wh-1`: un alt membru e acum `PRIMARY`, cel oprit apare „(not reachable/healthy)”.
4. Fă un `Create (POST)` → tot **201** (scrierile continuă) și apare în toate warehouse-urile.
5. `Start DB member …` îl readuce ca `SECONDARY`.

**F. Health check și circuit breaker**
1. `Freeze API of wh-1` → rulează `Cache bypass` de câteva ori: **toate dau 200**; prima poate dura ~2 s (timeout, apoi retrimitere la alt nod; vezi `X-Attempts: 2`).
2. `Gateway events` → vezi `BREAKER … OPEN` și `RETRY …`. `Registered instances` arată `breaker: OPEN` pentru wh-1.
3. `Start API of wh-1` readuce nodul. `Stop API of wh-1` → după ~2–3 s dispare din `Registered instances` (health check / deregistrare), `Start` → reapare singur.
4. Varianta fără Docker: `Slow node (3 s) -> timeout…` (header `X-Simulate-Delay-Ms: 3000`) arată același retry.

**G. Prioritate**
1. `Priority self-test` (POST `/admin/priority-tests?normal=30&delayMs=300`): în răspuns, `highFinishedAfterMs` e mult mai mic decât `lastNormalFinishedAfterMs` (high a trecut înaintea celor 30 din coadă).
2. Cererile `Priority HIGH` / `Priority LOW` pun headerul `X-Priority`; răspunsul confirmă în `X-Priority-Applied`.

**Test automat echivalent:** `./scripts/smoke-test.sh --chaos` face toate aceste verificări (36).
