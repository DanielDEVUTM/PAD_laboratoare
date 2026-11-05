#!/usr/bin/env bash
# End-to-end checks of every requirement against a running cluster (docker compose up -d).
#   ./scripts/smoke-test.sh           functional checks
#   ./scripts/smoke-test.sh --chaos   + failure injection (docker pause/stop: needs docker compose)
set -u
G="${GATEWAY:-http://localhost:8000}"
cd "$(dirname "$0")/.."
pass=0; fail=0
ok(){ pass=$((pass+1)); printf "  \033[32mPASS\033[0m %s\n" "$1"; }
ko(){ fail=$((fail+1)); printf "  \033[31mFAIL\033[0m %s\n" "$1"; }
check(){ if [ "$2" = "$3" ]; then ok "$1"; else ko "$1 (expected '$3', got '$2')"; fi; }
code(){ curl -s -o /dev/null -w "%{http_code}" "$@"; }
hdr(){ local h="$1"; shift; curl -s -D- -o /dev/null "$@" | tr -d '\r' | grep -i "^$h:" | cut -d' ' -f2-; }
json(){ python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

echo "== service discovery"
n=$(curl -s $G/registry/instances | json "len([i for i in d if i['status']=='UP'])")
[ "$n" -ge 2 ] && ok "at least 2 instances registered and UP ($n)" || ko "instances UP: $n"

echo "== CRUD (JSON)"
check "POST /employees -> 201" "$(code -X POST $G/employees -H 'Content-Type: application/json' -d '{"firstName":"Smoke","lastName":"Test","email":"s@t.test"}')" 201
check "PUT /employees/smoke-1 (create) -> 201" "$(code -X PUT $G/employees/smoke-1 -H 'Content-Type: application/json' -d '{"firstName":"A","lastName":"B","email":"a@b.test"}')" 201
check "PUT /employees/smoke-1 (replace) -> 200" "$(code -X PUT $G/employees/smoke-1 -H 'Content-Type: application/json' -d '{"firstName":"A2","lastName":"B","email":"a@b.test"}')" 200
sleep 2
check "GET /employees/smoke-1 reflects the update" "$(curl -s $G/employees/smoke-1 | json "d['firstName']")" A2
check "GET /employees?offset=0&limit=3 returns 3 items" "$(curl -s "$G/employees?offset=0&limit=3" | json "len(d['items'])")" 3
check "GET /employees?id=1" "$(curl -s "$G/employees?id=1" | json "d['items'][0]['firstName']")" "$(curl -s $G/employees/1 | json "d['firstName']")"
check "DELETE -> 204" "$(code -X DELETE $G/employees/smoke-1)" 204
check "GET deleted -> 404" "$(code -H 'Cache-Control: no-cache' $G/employees/smoke-1)" 404
check "invalid body -> 400" "$(code -X POST $G/employees -H 'Content-Type: application/json' -d '{"firstName":""}')" 400

echo "== JSON / XML"
check "?format=xml answers XML" "$(curl -s "$G/employees/1?format=xml" | head -c 10)" "<employee>"
check "Accept: application/xml answers XML" "$(curl -s -H 'Accept: application/xml' $G/employees/1 | head -c 10)" "<employee>"
check "XML request body accepted -> 201" "$(code -X POST "$G/employees?format=xml" -H 'Content-Type: application/xml' -d '<employee><firstName>X</firstName><lastName>M</lastName><email>x@m.test</email></employee>')" 201

echo "== load balancing (round-robin)"
served=$(for i in 1 2 3 4 5 6; do hdr X-Served-By -H 'Cache-Control: no-cache' $G/employees/1; done | sort -u | wc -l | tr -d ' ')
[ "$served" -ge 2 ] && ok "requests spread over $served instances" || ko "only $served instance served"

echo "== cache (Redis)"
sleep 2   # gateway write-guard: nothing is cached right after a write
curl -s -o /dev/null "$G/employees/3"
check "second GET is a cache HIT" "$(hdr X-Cache $G/employees/3)" HIT
curl -s -o /dev/null -X PUT $G/employees/3 -H 'Content-Type: application/json' -d '{"firstName":"Elena","lastName":"Rusu","email":"elena.rusu@company.test"}'
check "write invalidates the cache (next GET is a MISS)" "$(hdr X-Cache $G/employees/3)" MISS

echo "== replication (every node's DB holds the same data)"
counts=$(for id in $(curl -s $G/registry/instances | json "' '.join(i['id'] for i in d if i['status']=='UP')"); do curl -s "$G/admin/instances/$id/employees?limit=1" | json "d['total']"; done | sort -u | wc -l | tr -d ' ')
check "all nodes report the same document count" "$counts" 1

echo "== priority queue"
res=$(curl -s -X POST "$G/admin/priority-tests?normal=30&delayMs=300")
over=$(echo "$res" | json "d['normalRequests']-(d['highFinishedPosition']-1)")
hi=$(echo "$res" | json "d['highFinishedAfterMs']"); last=$(echo "$res" | json "d['lastNormalFinishedAfterMs']")
[ "$over" -ge 5 ] && [ "$hi" -lt "$last" ] && ok "HIGH overtook $over queued requests (HIGH ${hi} ms vs last normal ${last} ms)" || ko "priority not respected: $res"

if [ "${1:-}" = "--chaos" ]; then
  echo "== circuit breaker + retry (docker pause = node accepts the connection but never answers)"
  docker compose pause dw-2 >/dev/null
  curl -s -o /dev/null -H 'Cache-Control: no-cache' $G/employees/1
  for i in 1 2 3 4 5 6; do curl -s -o /dev/null -H 'Cache-Control: no-cache' $G/employees/1; done
  br=$(curl -s $G/registry/instances | json "[i['breaker']+'/'+i['status'] for i in d if i['id']=='dw-2'][0]")
  case "$br" in OPEN/*|*/DOWN) ok "dw-2 cut off from rotation ($br)";; *) ko "dw-2 still in rotation ($br)";; esac
  c=$(for i in 1 2 3 4 5 6; do code -H 'Cache-Control: no-cache' $G/employees/1; echo; done | sort -u | grep .)
  check "clients still get 200 while dw-2 is frozen" "$c" 200
  docker compose unpause dw-2 >/dev/null

  echo "== health check / service discovery (stop a node)"
  docker compose stop dw-3 >/dev/null; sleep 3
  st=$(curl -s $G/registry/instances | json "[i['id'] for i in d if i['id']=='dw-3']")
  [ "$st" = "[]" ] && ok "stopped node removed from the registry" || ko "dw-3 still registered: $st"
  docker compose start dw-3 >/dev/null; sleep 20
  st=$(curl -s $G/registry/instances | json "[i['status'] for i in d if i['id']=='dw-3']")
  check "restarted node re-registers by itself" "$st" "['UP']"

  echo "== master election (stop the MongoDB primary)"
  primary=$(docker compose exec -T mongo1 mongosh --quiet --eval 'db.hello().primary' 2>/dev/null | tr -d '\r'); svc=${primary%%:*}
  [ -z "$svc" ] && { svc=mongo2; primary=$(docker compose exec -T mongo2 mongosh --quiet --eval 'db.hello().primary' | tr -d '\r'); svc=${primary%%:*}; }
  docker compose stop "$svc" >/dev/null; sleep 12
  c=$(code -X PUT $G/employees/election -H 'Content-Type: application/json' -d '{"firstName":"E","lastName":"L","email":"e@l.test"}')
  case "$c" in 200|201) ok "write succeeds after the primary ($svc) died (HTTP $c)";; *) ko "write failed after primary died (HTTP $c)";; esac
  newp=$(docker compose exec -T $([ "$svc" = mongo1 ] && echo mongo2 || echo mongo1) mongosh --quiet --eval 'db.hello().primary' | tr -d '\r')
  [ -n "$newp" ] && [ "$newp" != "$primary" ] && ok "new primary elected: $newp (was $primary)" || ko "no new primary ($newp)"
  docker compose start "$svc" >/dev/null; sleep 10
  curl -s -o /dev/null -X DELETE $G/employees/election
fi

echo
echo "result: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
