#!/usr/bin/env bash
set -euo pipefail
DB=mcp-facebook-identity-db-1
NETWORK=blessrom_backend

docker inspect "$DB" >/dev/null
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
	docker network create --driver bridge "$NETWORK" >/dev/null
fi
STATE=$(docker inspect --format '{{with index .NetworkSettings.Networks "blessrom_backend"}}connected{{end}}' "$DB")
if [[ "$STATE" != connected ]]; then
	docker network connect --alias blessrom-db "$NETWORK" "$DB"
fi
ALIASES=$(docker inspect --format '{{with index .NetworkSettings.Networks "blessrom_backend"}}{{range .Aliases}}{{println .}}{{end}}{{end}}' "$DB")
if ! grep -Fxq blessrom-db <<<"$ALIASES"; then
	echo "Already attached but alias blessrom-db is missing. No networks were disconnected."
	exit 1
fi
docker inspect --format '{{range $name, $network := .NetworkSettings.Networks}}{{printf "%s: %s\n" $name $network.IPAddress}}{{end}}' "$DB"
echo "Live connection ready. Save both networks and the alias in PostgreSQL Compose before recreating it."
echo "Compose metadata:"
docker inspect --format 'service={{index .Config.Labels "com.docker.compose.service"}}
files={{index .Config.Labels "com.docker.compose.project.config_files"}}
directory={{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$DB"
