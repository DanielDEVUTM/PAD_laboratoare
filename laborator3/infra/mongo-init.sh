#!/bin/sh
# Initiates the 3-member MongoDB replica set (idempotent) and seeds the data.
# MONGO_HOSTS: space separated list of host:port of the members (default: mongo1..3)
set -e
HOSTS="${MONGO_HOSTS:-mongo1:27017 mongo2:27017 mongo3:27017}"
FIRST=$(echo "$HOSTS" | cut -d' ' -f1)

echo "[init] waiting for MongoDB members: $HOSTS"
for h in $HOSTS; do
  until mongosh --host "$h" --quiet --eval 'db.adminCommand({ping:1}).ok' 2>/dev/null | grep -q 1; do sleep 1; done
  echo "[init] $h is up"
done

MEMBERS=""
i=0
for h in $HOSTS; do
  [ -n "$MEMBERS" ] && MEMBERS="$MEMBERS,"
  MEMBERS="$MEMBERS{_id:$i,host:'$h'}"
  i=$((i+1))
done

mongosh --host "$FIRST" --quiet --eval "
  try { rs.status(); print('[init] replica set already initiated'); }
  catch (e) { printjs = rs.initiate({_id:'rs0', members:[$MEMBERS]}); print('[init] rs.initiate ok=' + printjs.ok); }
"

echo "[init] waiting for a PRIMARY ..."
until mongosh --host "$FIRST" --quiet --eval 'db.hello().isWritablePrimary || db.hello().primary' 2>/dev/null | grep -qE "true|:"; do sleep 1; done
PRIMARY=$(mongosh --host "$FIRST" --quiet --eval 'db.hello().primary')
echo "[init] primary is $PRIMARY"

mongosh --host "$PRIMARY" --quiet /seed.js
echo "[init] done"
