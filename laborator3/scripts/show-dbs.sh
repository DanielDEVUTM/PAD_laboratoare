#!/usr/bin/env bash
# Shows, for EVERY database container (wh-N-db-1/2/3), what that MongoDB instance contains.
#   ./scripts/show-dbs.sh            summary: role + number of documents + ids, per container
#   ./scripts/show-dbs.sh full       full documents
#   ./scripts/show-dbs.sh wh-1-db-2  only that container (add "full" for full documents)
mode=summary; only=""
for a in "$@"; do [ "$a" = full ] && mode=full || only="$a"; done
containers=$(docker ps --filter label=lab3.role=db --format '{{.Names}}' | sort -V)
[ -n "$only" ] && containers="$only"
for c in $containers; do
  echo "================ $c ================"
  if [ "$mode" = full ]; then
    docker exec "$c" mongosh --quiet warehouse --eval '
      db.getMongo().setReadPref("secondaryPreferred");
      print("role: " + (db.hello().isWritablePrimary ? "PRIMARY" : "SECONDARY") + "   replica set: " + db.hello().setName);
      db.employees.find().sort({_id:1}).forEach(d => printjson(d))'
  else
    docker exec "$c" mongosh --quiet warehouse --eval '
      db.getMongo().setReadPref("secondaryPreferred");
      const h = db.hello();
      print("role: " + (h.isWritablePrimary ? "PRIMARY" : "SECONDARY") + "   replica set: " + h.setName + "   documents: " + db.employees.countDocuments());
      print("ids: " + db.employees.find({}, {_id:1}).toArray().map(d => d._id).sort((a,b)=>a.localeCompare(b,undefined,{numeric:true})).join(", "))' 2>&1
  fi
  echo
done
