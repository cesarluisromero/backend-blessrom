# Operación y observabilidad

El VPS ejecuta tres temporizadores de bajo consumo, adecuados para el servidor de
1 vCPU y 4 GB de RAM:

- `blessrom-monitor.timer`: comprueba cada cinco minutos el health check local,
  la API pública, los contenedores de backend y PostgreSQL, disco, memoria,
  carga, antigüedad del último respaldo y vencimiento TLS. Registra incidencias
  y recuperaciones y evita repetir una alerta mientras el estado no cambie.
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

Para alertas, edite `/opt/blessrom/monitoring.env`. Puede usar un endpoint HTTPS
que acepte texto mediante POST, el SMTP ya configurado en
`/opt/blessrom/backend.env`, o ambos:

```dotenv
ALERT_WEBHOOK_URL=https://endpoint-privado.example/alertas
ALERT_EMAIL_TO=soporte@blessrom.com
BACKUP_RETENTION_DAYS=14
DISK_WARNING_PERCENT=85
MEMORY_WARNING_PERCENT=90
LOAD_WARNING_PER_CPU=3
BACKUP_MAX_AGE_MINUTES=1800
CERTIFICATE_WARNING_DAYS=14
CERTIFICATE_DOMAINS="blessrom.com www.blessrom.com api.blessrom.com admin.blessrom.com"
```

El correo reutiliza `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`,
`SMTP_FROM` y `SMTP_START_TLS` de `backend.env`; no copie la contraseña a
`monitoring.env`. Si no configura ningún canal, las incidencias permanecen
visibles en el journal de systemd.

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

Una ejecución manual del monitor puede terminar con código `1` cuando encuentra
una incidencia; en ese caso el detalle queda en el journal:

```bash
sudo systemctl start blessrom-monitor.service
sudo systemctl status blessrom-monitor.service --no-pager
sudo journalctl -u blessrom-monitor.service -n 100 --no-pager
```

Para inspeccionar las copias externas sin mostrar secretos:

```bash
sudo systemctl start blessrom-offsite-backup.service
sudo bash -c 'set -a; source /opt/blessrom/secrets/offsite-backup.env; set +a; restic snapshots'
```

No muestre ni registre el contenido de `offsite-backup.env`. La siguiente medida
operativa es practicar periódicamente una restauración en una base temporal.
