#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT=/opt/blessrom
RELEASE=$(cd -- "$(dirname -- "$0")" && pwd)
IMAGE=${1:?Pass the immutable GHCR image reference}
[[ "$RELEASE" =~ ^/opt/blessrom/releases/[a-f0-9]{40}$ ]] || {
	echo "Invalid release path"
	exit 1
}
[[ "$IMAGE" =~ ^ghcr.io/cesarluisromero/backend-blessrom@sha256:[a-f0-9]{64}$ ]] || {
	echo "Invalid image reference"
	exit 1
}
test -s "$ROOT/backend.env"
if grep -q 'REEMPLAZAR' "$ROOT/backend.env"; then
	echo "Complete backend.env before deploying."
	exit 1
fi
for key in DB_URL DB_USER DB_PASSWORD OIDC_AUTH_SERVER_URL; do
	grep -q "^$key=." "$ROOT/backend.env" || {
		echo "Missing $key"
		exit 1
	}
done
command -v curl >/dev/null
command -v flock >/dev/null
docker compose version
docker network inspect blessrom_backend >/dev/null
# Fail before backup/recreation if the shared database is not attached correctly.
DB_ALIASES=$(docker inspect --format '{{with index .NetworkSettings.Networks "blessrom_backend"}}{{range .Aliases}}{{println .}}{{end}}{{end}}' mcp-facebook-identity-db-1)
if ! grep -Fxq 'blessrom-db' <<<"$DB_ALIASES"; then
	echo "PostgreSQL must join blessrom_backend with alias blessrom-db. See docs/red-blessrom.md."
	exit 1
fi
if ! grep -Fxq 'DB_URL=jdbc:postgresql://blessrom-db:5432/blessrom' "$ROOT/backend.env"; then
	echo "Set DB_URL=jdbc:postgresql://blessrom-db:5432/blessrom in backend.env."
	exit 1
fi

exec 9>"$ROOT/deploy.lock"
flock -w 120 9 || {
	echo "Another deployment is active."
	exit 1
}
PREVIOUS=""
if test -L "$ROOT/current"; then PREVIOUS=$(readlink -f "$ROOT/current"); fi
# Do not overwrite the current manifest when manually rerunning the same commit.
if [[ "$PREVIOUS" == "$RELEASE" ]]; then
	grep -Fxq "BLESSROM_IMAGE=$IMAGE" "$RELEASE/image.env" || {
		echo "This commit is already deployed with another digest. Create a new commit."
		exit 1
	}
fi
printf 'BLESSROM_IMAGE=%s\n' "$IMAGE" >"$RELEASE/image.env"

compose() {
	local dir=$1
	shift
	docker compose --project-name blessrom --env-file "$dir/image.env" -f "$dir/compose.yaml" "$@"
}
healthy() {
	curl --fail --silent --max-time 5 http://127.0.0.1:8082/q/health/ready >/dev/null &&
		curl --fail --silent --max-time 5 http://127.0.0.1:8082/api/v1/system >/dev/null
}
wait_healthy() {
	for ((attempt = 1; attempt <= 90; attempt++)); do
		if healthy; then return 0; fi
		sleep 2
	done
	return 1
}

# Pull before touching the running service.
compose "$RELEASE" config --quiet
compose "$RELEASE" pull backend
mkdir -p "$ROOT/backups"
BACKUP=$(mktemp "$ROOT/backups/blessrom-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.dump")
if ! docker exec mcp-facebook-identity-db-1 pg_dump -U keycloak -d blessrom -Fc >"$BACKUP"; then
	rm -f "$BACKUP"
	echo "Backup failed. Existing application was not changed."
	exit 1
fi
test -s "$BACKUP"
echo "Database backup: $BACKUP"

if compose "$RELEASE" up -d --no-deps backend && wait_healthy; then
	ln -sfn "$RELEASE" "$ROOT/current.next"
	mv -Tf "$ROOT/current.next" "$ROOT/current"
	echo "Deployment healthy: $IMAGE"
else
	echo "Deployment failed."
	compose "$RELEASE" logs --tail 100 backend || true
	if [[ "$PREVIOUS" =~ ^/opt/blessrom/releases/[a-f0-9]{40}$ ]] && test -f "$PREVIOUS/image.env"; then
		echo "Restoring previous application image. Database migrations are NOT reverted."
		compose "$PREVIOUS" up -d --no-deps --pull never backend
		wait_healthy || echo "Previous image is unhealthy: manual intervention required."
	else
		compose "$RELEASE" stop backend || true
		echo "First deployment failed; backend stopped."
	fi
	exit 1
fi
