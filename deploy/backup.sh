#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT=/opt/blessrom
BACKUP_DIR="$ROOT/backups/daily"
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-14}
DB_CONTAINER=${DB_CONTAINER:-mcp-facebook-identity-db-1}
DB_NAME=${DB_NAME:-blessrom}
DB_USER=${DB_USER:-keycloak}

mkdir -p "$BACKUP_DIR"
backup=$(mktemp "$BACKUP_DIR/blessrom-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX.dump")
cleanup() { test ! -e "$backup" || rm -f "$backup"; }
trap cleanup ERR INT TERM

docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc >"$backup"
test -s "$backup"

# A backup is accepted only if PostgreSQL can parse its complete catalog.
docker exec -i "$DB_CONTAINER" pg_restore --list <"$backup" >/dev/null
sha256sum "$backup" >"$backup.sha256"
chmod 600 "$backup" "$backup.sha256"

find "$BACKUP_DIR" -type f \( -name '*.dump' -o -name '*.dump.sha256' \) \
	-mtime "+$RETENTION_DAYS" -delete

trap - ERR INT TERM
logger -t blessrom-backup -- "Backup validado: $backup"
printf 'Backup validado: %s\n' "$backup"

