# Operación y observabilidad

El VPS ejecuta tres temporizadores de bajo consumo, adecuados para el servidor de
1 vCPU y 4 GB de RAM:

- `blessrom-monitor.timer`: comprueba cada cinco minutos el health check local y
  la API pública. Registra caídas, recuperaciones y puede enviar un webhook.
- `blessrom-backup.timer`: crea diariamente un respaldo PostgreSQL en formato
  custom, lo valida con `pg_restore --list`, calcula SHA-256 y conserva 14 días.
- `blessrom-offsite-backup.timer`: a las 04:00 cifra y copia a R2 el último
  respaldo local validado. Conserva 14 diarios, 8 semanales y 12 mensuales. Los
  domingos ejecuta mantenimiento y comprueba una muestra del repositorio.

## Instalación

Los archivos de `deploy/` deben estar disponibles en el VPS. Como `root`:

```bash
bash /ruta/al/repositorio/deploy/install-operations.sh
```

Para alertas, edite `/opt/blessrom/monitoring.env` y configure un endpoint HTTPS
que acepte texto mediante POST:

```dotenv
ALERT_WEBHOOK_URL=https://endpoint-privado.example/alertas
BACKUP_RETENTION_DAYS=14
```

## Verificación

```bash
systemctl list-timers 'blessrom-*'
systemctl status blessrom-monitor.timer blessrom-backup.timer blessrom-offsite-backup.timer
journalctl -u blessrom-monitor.service -n 50 --no-pager
journalctl -u blessrom-backup.service -n 50 --no-pager
journalctl -u blessrom-offsite-backup.service -n 50 --no-pager
ls -lh /opt/blessrom/backups/daily
sha256sum -c /opt/blessrom/backups/daily/*.sha256
```

Para inspeccionar las copias externas sin mostrar secretos:

```bash
sudo systemctl start blessrom-offsite-backup.service
sudo bash -c 'set -a; source /opt/blessrom/secrets/offsite-backup.env; set +a; restic snapshots'
```

No muestre ni registre el contenido de `offsite-backup.env`. La siguiente medida
operativa es practicar periódicamente una restauración en una base temporal.
