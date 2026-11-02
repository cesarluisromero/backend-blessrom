#!/usr/bin/env bash
set -euo pipefail

test "$(id -u)" -eq 0 || { echo "Ejecuta como root"; exit 1; }
SOURCE=$(cd -- "$(dirname -- "$0")" && pwd)
ROOT=/opt/blessrom

install -d -m 750 "$ROOT/ops" "$ROOT/monitoring" "$ROOT/backups/daily"
install -m 750 "$SOURCE/monitor.sh" "$ROOT/ops/monitor.sh"
install -m 750 "$SOURCE/backup.sh" "$ROOT/ops/backup.sh"
install -m 644 "$SOURCE/systemd/blessrom-monitor.service" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-monitor.timer" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-backup.service" /etc/systemd/system/
install -m 644 "$SOURCE/systemd/blessrom-backup.timer" /etc/systemd/system/

if test ! -e "$ROOT/monitoring.env"; then
	install -m 600 /dev/null "$ROOT/monitoring.env"
	cat >"$ROOT/monitoring.env" <<'EOF'
# Opcional: endpoint HTTPS que acepte POST de texto plano.
# ALERT_WEBHOOK_URL=
BACKUP_RETENTION_DAYS=14
EOF
fi

systemctl daemon-reload
systemctl enable --now blessrom-monitor.timer blessrom-backup.timer
systemctl start blessrom-monitor.service
systemctl start blessrom-backup.service
systemctl --no-pager --full status blessrom-monitor.timer blessrom-backup.timer

