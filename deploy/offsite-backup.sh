#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT=/opt/blessrom
BACKUP_DIR="$ROOT/backups/daily"
ENV_FILE="$ROOT/secrets/offsite-backup.env"
LOCK_FILE=/run/lock/blessrom-offsite-backup.lock

test "$(id -u)" -eq 0 || { echo "Ejecuta como root" >&2; exit 1; }
command -v restic >/dev/null || { echo "restic no está instalado" >&2; exit 1; }
test -r "$ENV_FILE" || { echo "No se puede leer $ENV_FILE" >&2; exit 1; }

exec 9>"$LOCK_FILE"
flock -n 9 || { echo "Ya existe otro respaldo externo en ejecución"; exit 0; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${RESTIC_REPOSITORY:?Falta RESTIC_REPOSITORY}"
: "${RESTIC_PASSWORD:?Falta RESTIC_PASSWORD}"
: "${AWS_ACCESS_KEY_ID:?Falta AWS_ACCESS_KEY_ID}"
: "${AWS_SECRET_ACCESS_KEY:?Falta AWS_SECRET_ACCESS_KEY}"

latest=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' \
	-printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d' ' -f2-)
test -n "$latest" || { echo "No hay respaldo PostgreSQL para copiar" >&2; exit 1; }
test -r "$latest.sha256" || { echo "Falta $latest.sha256" >&2; exit 1; }

(cd "$BACKUP_DIR" && sha256sum --check "$(basename "$latest").sha256")

restic backup "$latest" "$latest.sha256" \
	--host "$(hostname -s)" \
	--tag blessrom \
	--tag postgresql

restic forget \
	--host "$(hostname -s)" \
	--tag blessrom \
	--tag postgresql \
	--group-by host,tags \
	--keep-daily 14 \
	--keep-weekly 8 \
	--keep-monthly 12

# El mantenimiento intensivo se ejecuta solo los domingos para cuidar el VPS.
if test "$(date +%u)" -eq 7; then
	restic prune
	restic check --read-data-subset=5%
fi

logger -t blessrom-offsite-backup -- "Respaldo externo completado: $latest"
printf 'Respaldo externo completado: %s\n' "$latest"
