# Backend Blessrom

Quarkus 3.40.1.1 / Java 25 / PostgreSQL. Repositorio separado de frontend-blessrom.

Implementa catálogo con borradores y publicación, variantes, inventario, tarifas de envío, carrito de invitado y pedidos con reservas. Los pedidos quedan pendientes de pago. Detalles, API y limitaciones: [módulos comerciales](docs/commercial-modules.md).

## Desarrollo local (Windows / PowerShell 7)

Requiere Java 25 y PostgreSQL 17. scripts/dev.ps1 usa primero el JDK local de .tools/java25, si existe; en otro equipo configura JAVA_HOME. Maven Wrapper está incluido.

En una terminal, iniciar la instancia aislada de PostgreSQL (puerto 55432, escucha solo en localhost):

    pwsh -File scripts/postgres.ps1

En otra terminal, crear las bases de desarrollo/pruebas una sola vez e iniciar Quarkus:

    pwsh -File scripts/init-databases.ps1
    pwsh -File scripts/dev.ps1

PostgreSQL guarda sus datos en .tools/postgres-data. Los scripts generan contraseñas aleatorias en .tools/local-secrets.json, ignorado por Git. En desarrollo el usuario administrativo es admin y su contraseña es el campo AdminPassword de ese archivo. No publiques el archivo ni lo copies a producción.

- Swagger: http://localhost:8080/q/swagger-ui/
- API de sistema: http://localhost:8080/api/v1/system
- Catálogo: http://localhost:8080/api/v1/products
- Salud: http://localhost:8080/q/health/ready
- Angular tienda: origen permitido http://localhost:4200
- Angular administración: origen permitido http://localhost:4300

Los endpoints /api/v1/admin requieren autenticación HTTP Basic en desarrollo. Para las solicitudes públicas de carrito/pedido utiliza X-Cart-Token. Consulta ejemplos y contratos en docs/commercial-modules.md y Swagger.

## Datos de demostración

Con el backend local activo:

    pwsh -File scripts/seed-demo.ps1

Carga dos prendas DEMO y una tarifa ficticia para el código 999999, únicamente en localhost. Puede repetirse sin duplicar los productos. No representa precios, inventario ni cobertura comercial real.

## Pruebas y compilación JVM

Con PostgreSQL local activo:

    pwsh -File scripts/test.ps1

Ejecuta Maven verify con Java 25 contra blessrom_test, separada de blessrom_dev. Las pruebas crean datos sintéticos únicos y no limpian ni tocan la base de desarrollo. Flyway tiene clean deshabilitado. No apuntar TEST_DB_URL a una base real.

## Producción y nativo

Configurar DB_URL, DB_USER, DB_PASSWORD y OIDC_AUTH_SERVER_URL. El rol de administración es admin, leído de realm_access/roles en el JWT. Basic no está habilitado en producción. El servidor escucha por defecto en 127.0.0.1; configurar QUARKUS_HTTP_HOST según la red de despliegue y el proxy TLS.

Para generar Linux nativo con Mandrel JDK 25 desde una máquina Linux/CI con Docker:

    ./mvnw verify -Dnative -Dquarkus.native.container-build=true

La compilación nativa debe ejecutarse fuera del VPS inicial de 1 vCPU / 4 GB. Todavía falta comprobar esta entrega en modo nativo y configurar un entorno de prueba PostgreSQL/OIDC para las pruebas del ejecutable. No se afirma que la imagen nativa esté validada.

Antes de producción faltan, entre otros: pasarela y webhooks, consumo definitivo de inventario al confirmar pago, Keycloak/MFA real, archivos de imágenes, cuentas de cliente, backups y pruebas de carga. Ver el documento comercial.

No se ha modificado frontend-blessrom.

## Despliegue desde GitHub

Workflow y guía paso a paso: [GitHub Actions y VPS](docs/despliegue-github-actions.md). Incluye compilación nativa en GitHub, GHCR, copia previa de la base y verificación de salud. Activar DEPLOY_ENABLED solo después de configurar el VPS y Keycloak.
