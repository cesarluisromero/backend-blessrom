# Operación y observabilidad

El VPS ejecuta dos temporizadores de bajo consumo, adecuados para el servidor de
1 vCPU y 4 GB de RAM:

- `blessrom-monitor.timer`: comprueba cada cinco minutos el health check local y
  la API pública. Registra caídas, recuperaciones y puede enviar un webhook.
- `blessrom-backup.timer`: crea diariamente un respaldo PostgreSQL en formato
  custom, lo valida con `pg_restore --list`, calcula SHA-256 y conserva 14 días.

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
systemctl status blessrom-monitor.timer blessrom-backup.timer
journalctl -u blessrom-monitor.service -n 50 --no-pager
journalctl -u blessrom-backup.service -n 50 --no-pager
ls -lh /opt/blessrom/backups/daily
sha256sum -c /opt/blessrom/backups/daily/*.sha256
```

Una copia local no protege frente a la pérdida completa del VPS. Como siguiente
nivel, sincronice de forma cifrada los respaldos validados a almacenamiento
externo y realice periódicamente una restauración en una base temporal.
