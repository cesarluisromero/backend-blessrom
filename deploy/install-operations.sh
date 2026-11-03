#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "Ejecuta como root"; exit 1; }
SOURCE=$(cd -- "$(dirname -- "$0")" && pwd)
ROOT=/opt/blessrom

install -d -m 750 "$ROOT/ops" "$ROOT/monitoring" "$ROOT/backups/daily" "$ROOT/secrets"
install -m 750 "$SOURCE/monitor.sh" "$ROOT/ops/monitor.sh"
install -m 750 "$SOURCE/backup.sh" "$ROOT/ops/backup.sh"
install -m 750 "$SOURCE/offsite-backup.sh" "$ROOT/ops/offsite-backup.sh"
install -m 644 "$SOURCE/systemd/blessrom-monitor.service" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-monitor.timer" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-backup.service" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-backup.timer" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-offsite-backup.service" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-offsite-backup.timer" /etc/systemd/system/

if test ! -e "$ROOT/monitoring.env"; then
	install -m 600 /dev/null "$ROOT/monitoring.env"
	cat >"$ROOT/monitoring.env" <<'EOF'
# Opcional: endpoint HTTPS que acepte POST de texto plano.
# ALERT_WEBHOOK_URL=
# Opcional: usa la cuenta SMTP configurada en backend.env.
# ALERT_EMAIL_TO=soporte@blessrom.com
BACKUP_RETENTION_DAYS=14
DISK_WARNING_PERCENT=85
MEMORY_WARNING_PERCENT=90
LOAD_WARNING_PER_CPU=3
BACKUP_MAX_AGE_MINUTES=1800
CERTIFICATE_WARNING_DAYS=14
CERTIFICATE_DOMAINS="blessrom.com www.blessrom.com api.blessrom.com admin.blessrom.com"
EOF
fi

ensure_monitor_setting() {
	local key=$1
	local value=$2
	grep -q "^${key}=" "$ROOT/monitoring.env" || printf '%s=%s\n' "$key" "$value" >>"$ROOT/monitoring.env"
}

ensure_monitor_setting BACKUP_RETENTION_DAYS 14
ensure_monitor_setting DISK_WARNING_PERCENT 85
ensure_monitor_setting MEMORY_WARNING_PERCENT 90
ensure_monitor_setting LOAD_WARNING_PER_CPU 3
ensure_monitor_setting BACKUP_MAX_AGE_MINUTES 1800
ensure_monitor_setting CERTIFICATE_WARNING_DAYS 14
ensure_monitor_setting CERTIFICATE_DOMAINS '"blessrom.com www.blessrom.com api.blessrom.com admin.blessrom.com"'
chmod 600 "$ROOT/monitoring.env"

systemctl daemon-reload
systemctl enable --now blessrom-monitor.timer blessrom-backup.timer blessrom-offsite-backup.timer
systemctl start blessrom-monitor.service || true
systemctl start blessrom-backup.service
systemctl --no-pager --full status blessrom-monitor.timer blessrom-backup.timer blessrom-offsite-backup.timer

