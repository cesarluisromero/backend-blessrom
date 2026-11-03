#!/usr/bin/env bash
set -uo pipefail

ROOT=/opt/blessrom
STATE_DIR="$ROOT/monitoring"
STATE_FILE="$STATE_DIR/backend.state"
ENV_FILE="$ROOT/monitoring.env"
HEALTH_URL=${HEALTH_URL:-http://127.0.0.1:18082/q/health/ready}
PUBLIC_URL=${PUBLIC_URL:-https://api.blessrom.com/api/v1/system}
BACKEND_CONTAINER=${BACKEND_CONTAINER:-blessrom-backend-1}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-mcp-facebook-identity-db-1}
POSTGRES_USER=${POSTGRES_USER:-keycloak}
POSTGRES_DATABASE=${POSTGRES_DATABASE:-blessrom}
DISK_WARNING_PERCENT=${DISK_WARNING_PERCENT:-85}
MEMORY_WARNING_PERCENT=${MEMORY_WARNING_PERCENT:-90}
LOAD_WARNING_PER_CPU=${LOAD_WARNING_PER_CPU:-3}
BACKUP_MAX_AGE_MINUTES=${BACKUP_MAX_AGE_MINUTES:-1800}
CERTIFICATE_WARNING_DAYS=${CERTIFICATE_WARNING_DAYS:-14}
CERTIFICATE_DOMAINS=${CERTIFICATE_DOMAINS:-"blessrom.com www.blessrom.com api.blessrom.com admin.blessrom.com"}

if test -r "$ENV_FILE"; then
	# shellcheck disable=SC1090
	set -a; source "$ENV_FILE"; set +a
fi

mkdir -p "$STATE_DIR"
issues=()
issue_codes=()

add_issue() {
	issue_codes+=("$1")
	issues+=("$2")
}

check_url() {
	local label=$1
	local url=$2
	local timeout=$3
	if ! curl --fail --silent --show-error --max-time "$timeout" "$url" >/dev/null 2>&1; then
		add_issue "url:$label" "$label no responde: $url"
	fi
}

check_url "Health local" "$HEALTH_URL" 10
check_url "API publica" "$PUBLIC_URL" 15

if ! docker inspect --format '{{.State.Running}}' "$BACKEND_CONTAINER" 2>/dev/null | grep -qx true; then
	add_issue backend-container "El contenedor $BACKEND_CONTAINER no esta en ejecucion"
fi

if ! docker exec "$POSTGRES_CONTAINER" pg_isready \
	-U "$POSTGRES_USER" -d "$POSTGRES_DATABASE" >/dev/null 2>&1; then
	add_issue postgres "PostgreSQL no esta disponible en $POSTGRES_CONTAINER/$POSTGRES_DATABASE"
fi

disk_percent=$(df -P / | awk 'NR==2 {gsub(/%/, "", $5); print $5}')
if [[ "$disk_percent" =~ ^[0-9]+$ ]] && (( disk_percent >= DISK_WARNING_PERCENT )); then
	add_issue disk "Disco raiz al ${disk_percent}% (umbral ${DISK_WARNING_PERCENT}%)"
fi

memory_percent=$(free | awk '/^Mem:/ {printf "%d", ($2-$7)*100/$2}')
if [[ "$memory_percent" =~ ^[0-9]+$ ]] && (( memory_percent >= MEMORY_WARNING_PERCENT )); then
	add_issue memory "Memoria al ${memory_percent}% (umbral ${MEMORY_WARNING_PERCENT}%)"
fi

cpu_count=$(nproc 2>/dev/null || printf '1')
load_one=$(awk '{print $1}' /proc/loadavg)
load_scaled=$(awk -v load="$load_one" -v cpus="$cpu_count" 'BEGIN {printf "%d", load*100/cpus}')
load_limit=$((LOAD_WARNING_PER_CPU * 100))
if (( load_scaled >= load_limit )); then
	add_issue cpu-load "Carga ${load_one} con ${cpu_count} CPU (umbral ${LOAD_WARNING_PER_CPU} por CPU)"
fi

latest_backup=$(find "$ROOT/backups" -maxdepth 2 -type f \
	\( -name '*.dump' -o -name '*.sql.gz' -o -name '*.tar.gz' \) \
	-printf '%T@ %p\n' 2>/dev/null | sort -nr | awk 'NR==1 {$1=""; sub(/^ /, ""); print}')
if [[ -z "$latest_backup" ]]; then
	add_issue backup-missing "No se encontro un respaldo local en $ROOT/backups"
else
	backup_epoch=$(stat -c %Y "$latest_backup" 2>/dev/null || printf '0')
	backup_age_minutes=$(( ($(date +%s) - backup_epoch) / 60 ))
	if (( backup_age_minutes > BACKUP_MAX_AGE_MINUTES )); then
		add_issue backup-old "El ultimo respaldo local tiene ${backup_age_minutes} minutos (maximo ${BACKUP_MAX_AGE_MINUTES})"
	fi
fi

check_service_result() {
	local unit=$1
	local result
	result=$(systemctl show "$unit" --property=Result --value 2>/dev/null || true)
	if [[ -n "$result" && "$result" != success ]]; then
		add_issue "service:$unit" "La ultima ejecucion de $unit termino con resultado: $result"
	fi
}

check_service_result blessrom-backup.service
if systemctl list-unit-files blessrom-offsite-backup.service >/dev/null 2>&1; then
	check_service_result blessrom-offsite-backup.service
fi

now_epoch=$(date +%s)
for domain in $CERTIFICATE_DOMAINS; do
	expires=$(timeout 15 openssl s_client -servername "$domain" -connect "$domain:443" </dev/null 2>/dev/null \
		| openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2-)
	if [[ -z "$expires" ]]; then
		add_issue "tls-unavailable:$domain" "No se pudo verificar el certificado TLS de $domain"
		continue
	fi
	expires_epoch=$(date -d "$expires" +%s 2>/dev/null || printf '0')
	remaining_days=$(( (expires_epoch - now_epoch) / 86400 ))
	if (( remaining_days <= CERTIFICATE_WARNING_DAYS )); then
		add_issue "tls-expiring:$domain" "El certificado TLS de $domain vence en ${remaining_days} dias"
	fi
done

notify_email() {
	local subject=$1
	local message=$2
	local from
	local -a tls=()
	if [[ -z "${ALERT_EMAIL_TO:-}" || ! -r "$ROOT/backend.env" ]]; then
		return 0
	fi
	set -a; source "$ROOT/backend.env"; set +a
	if [[ -z "${SMTP_HOST:-}" || -z "${SMTP_USERNAME:-}" || -z "${SMTP_PASSWORD:-}" ]]; then
		return 0
	fi
	from=${SMTP_FROM:-$SMTP_USERNAME}
	if [[ "${SMTP_START_TLS:-REQUIRED}" == REQUIRED ]]; then
		tls+=(--ssl-reqd)
	fi
	printf 'From: %s\r\nTo: %s\r\nSubject: %s\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n%s\r\n' \
		"$from" "$ALERT_EMAIL_TO" "$subject" "$message" \
		| curl --fail --silent --show-error --max-time 30 \
			--url "smtp://${SMTP_HOST}:${SMTP_PORT:-587}" \
			--user "${SMTP_USERNAME}:${SMTP_PASSWORD}" \
			"${tls[@]}" --mail-from "$from" --mail-rcpt "$ALERT_EMAIL_TO" \
			--upload-file - >/dev/null || true
}

notify() {
	local subject=$1
	local message=$2
	logger -t blessrom-monitor -- "$subject: $message"
	if [[ -n "${ALERT_WEBHOOK_URL:-}" ]]; then
		curl --fail --silent --show-error --max-time 15 \
			-H 'Content-Type: text/plain; charset=utf-8' \
			--data-binary "$subject: $message" "$ALERT_WEBHOOK_URL" >/dev/null || true
	fi
	notify_email "$subject" "$message"
}

previous=$(cat "$STATE_FILE" 2>/dev/null || printf 'UNKNOWN')
if ((${#issues[@]} > 0)); then
	issue_list=$(printf -- '- %s\n' "${issues[@]}")
	fingerprint=$(printf '%s\n' "${issue_codes[@]}" | sort | sha256sum | awk '{print $1}')
	if [[ "$previous" != "ALERT:$fingerprint" ]]; then
		notify "ALERTA Blessrom" "$(printf 'Blessrom presenta %d alerta(s) en %s:\n%s' "${#issues[@]}" "$(hostname)" "$issue_list")"
	fi
	printf 'ALERT:%s\n' "$fingerprint" >"$STATE_FILE"
	printf '%s' "$issue_list" >&2
	exit 1
fi

if [[ "$previous" == ALERT:* ]]; then
	notify "RECUPERADO Blessrom" "Todos los controles operativos estan correctos en $(hostname)."
fi
printf 'UP\n' >"$STATE_FILE"
printf 'Blessrom operativo: API, base de datos, recursos, respaldos y TLS correctos.\n'

