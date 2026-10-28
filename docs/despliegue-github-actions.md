# Desplegar backend-blessrom con GitHub Actions

Repositorio: https://github.com/cesarluisromero/backend-blessrom
Rama de despliegue: main.

## Qué quedó preparado

Al abrir un PR hacia main se ejecutan las pruebas JVM contra un PostgreSQL desechable de GitHub. Al subir a main (o ejecutar Run workflow desde main), se realizan esas pruebas, se compila Java 25 a nativo Linux amd64 con Mandrel, se crea una imagen Docker, se comprueba su arranque nativo con PostgreSQL y descubrimiento OIDC, y se publica en GHCR.

El job deploy se ejecuta solamente cuando la variable de repositorio DEPLOY_ENABLED vale true. Descarga la imagen por digest, hace un pg_dump de blessrom, actualiza el contenedor y comprueba salud. Si falla, intenta arrancar la versión anterior de la aplicación. NO revierte cambios de esquema.

El VPS no compila Java. El contenedor tiene límite inicial de 512 MB, 0.75 CPU y cinco conexiones JDBC. Son límites iniciales: hay que medirlos junto con Keycloak y los demás servicios del VPS.

No se ha ejecutado aún este workflow en GitHub ni se ha desplegado desde esta sesión. La compilación nativa y las comprobaciones de runtime se ejecutarán por primera vez en Actions. No confundir la validación estática del YAML con una compilación nativa satisfactoria.

## 1. Revisar VPS y localizar Keycloak desde PuTTY

Ejecutar como root:

~~~bash
docker compose version
uname -m
docker network inspect blessrom_backend --format '{{.Name}}'
docker port mcp-facebook-identity-1
docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' mcp-facebook-identity-1 \
  | grep -E '^KC_(HOSTNAME|HOSTNAME_ADMIN|HOSTNAME_STRICT|PROXY_HEADERS|HTTP_ENABLED)='
curl --fail --silent --show-error http://127.0.0.1:8180/realms/master/.well-known/openid-configuration \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["issuer"])'
~~~

La arquitectura debe ser x86_64 (amd64). El Compose preparado requiere versión 2.30.0 o superior por env_file.format=raw. No reinstalar Docker ni reiniciar los contenedores existentes si falta una actualización: revisar primero la instalación.

Esos comandos no muestran contraseñas. La URL issuer ayuda a identificar el hostname actual de Keycloak. No cambiar KC_HOSTNAME de una instancia compartida con Facebook sin revisar sus clientes.

Si Keycloak solo está accesible en localhost, agregar en PuTTY un túnel:
- Source port: 18180
- Destination: 127.0.0.1:8180
- Local / Auto, sin aceptar conexiones de otros equipos.
Abrir http://127.0.0.1:18180/admin/ desde la computadora.

Si Keycloak redirige a un dominio configurado o exige HTTPS, usar el dominio real y su proxy; no desactivar esa protección para corregir una redirección. Hace falta la cuenta administrativa de Keycloak que se creó al instalarlo. La contraseña root del VPS y la de PostgreSQL no son esa cuenta. No imprimir variables de entorno completas porque pueden contener secretos.

## 2. Crear un realm independiente de Blessrom

Desde la consola administrativa de Keycloak:
1. Create realm → blessrom → Create.
2. Realm roles → Create role → admin.
3. Clients → Create client → OpenID Connect → Client ID blessrom-backend.
4. Para este cliente de API no habilitar flujos de usuario ni service accounts. El backend verifica Bearer JWT; no utiliza contraseña Basic en producción.
5. Crear un usuario propio para administrar Blessrom y asignarle el rol de realm admin. Configurar contraseña y MFA antes de exposición pública.
6. El cliente Angular para login, su PKCE, orígenes y redirect URIs se configurarán al conectar el frontend.

No usar el realm master para los usuarios de la tienda. No modificar el realm del sistema Facebook.

OIDC_AUTH_SERVER_URL debe apuntar al realm blessrom en la URL real de Keycloak accesible desde el contenedor backend. Comprobar que termina en /realms/blessrom y que su documento /.well-known/openid-configuration responde correctamente. Preferir el hostname canónico con HTTPS. Si se usa temporalmente el DNS interno del contenedor, revisar que issuer y endpoints anunciados sigan siendo accesibles y que coincidan con los tokens; no inventar una URL ni desactivar la comprobación de issuer.

## 3. Crear usuario de despliegue en el VPS

En PuTTY como root:

~~~bash
id blessrom-deploy || adduser --disabled-password --gecos "" blessrom-deploy
usermod -aG docker blessrom-deploy
install -d -m 700 -o blessrom-deploy -g blessrom-deploy /home/blessrom-deploy/.ssh
install -d -m 750 -o blessrom-deploy -g blessrom-deploy /opt/blessrom
install -d -m 700 -o blessrom-deploy -g blessrom-deploy /opt/blessrom/backups /opt/blessrom/releases
command -v curl
command -v flock
~~~

El grupo docker concede control amplio del host, equivalente a root. Esta cuenta y la clave son exclusivas para el despliegue; limitar quién puede modificar main y quién accede a los secretos del environment production.

## 4. Crear una clave SSH exclusiva para GitHub Actions

En PowerShell de tu computadora:

~~~powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.ssh" | Out-Null
ssh-keygen -t ed25519 -C "github-actions-blessrom" -f "$env:USERPROFILE\.ssh\blessrom_actions"
~~~

Si ese archivo ya existe, no sobrescribirlo: conservarlo o usar otro nombre. Para esta clave de automatización, dejar la passphrase vacía cuando la solicite. No reutilizar la clave personal de PuTTY.

Ver la clave PÚBLICA:

~~~powershell
Get-Content "$env:USERPROFILE\.ssh\blessrom_actions.pub"
~~~

En PuTTY, abrir:

~~~bash
nano /home/blessrom-deploy/.ssh/authorized_keys
~~~

Agregar una línea con el contenido de la clave pública precedido por restrict y un espacio:

~~~text
restrict ssh-ed25519 CLAVE_PUBLICA_COMPLETA github-actions-blessrom
~~~

Conservar cualquier clave existente. Guardar con Ctrl+O, Enter, Ctrl+X. Luego:

~~~bash
chown blessrom-deploy:blessrom-deploy /home/blessrom-deploy/.ssh/authorized_keys
chmod 600 /home/blessrom-deploy/.ssh/authorized_keys
~~~

La clave privada (archivo sin .pub) se copia únicamente al secreto VPS_SSH_KEY de GitHub. No subirla al repositorio ni pegarla en el chat.

## 5. Obtener la clave pública del servidor (known_hosts)

En la sesión PuTTY ya autenticada:

~~~bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
awk '{print "77.37.43.158 " $1 " " $2}' /etc/ssh/ssh_host_ed25519_key.pub
~~~

La segunda línea de salida completa será VPS_KNOWN_HOSTS. La IP corresponde a la que mostraste; sustituirla si el servidor cambió. Si el puerto SSH no es 22, usar [77.37.43.158]:PUERTO como primer campo.

El workflow usa StrictHostKeyChecking=yes. No utiliza ssh-keyscan sin verificación ni desactiva la validación del servidor.

## 6. Configurar la conexión de producción, solo en el VPS

En PuTTY:

~~~bash
umask 077
nano /opt/blessrom/backend.env
~~~

Contenido (reemplazar contraseña y URL):

~~~dotenv
DB_URL=jdbc:postgresql://blessrom-db:5432/blessrom
DB_USER=blessrom_app
DB_PASSWORD=TU_CONTRASENA_REAL_DE_BLESSROM_APP
OIDC_AUTH_SERVER_URL=URL_REAL_DE_KEYCLOAK/realms/blessrom
QUARKUS_DATASOURCE_JDBC_MAX_SIZE=5
QUARKUS_DATASOURCE_JDBC_LOGIN_TIMEOUT=15S
~~~

No escribir comillas alrededor de los valores: Compose usa formato raw y conserva caracteres como $ literalmente. No pegar una contraseña con saltos de línea.

~~~bash
chown blessrom-deploy:blessrom-deploy /opt/blessrom/backend.env
chmod 600 /opt/blessrom/backend.env
~~~

El backend usará DNS Docker, no la IP 172.19.0.4 ni el túnel de tu computadora. La red externa blessrom_backend debe existir y PostgreSQL debe estar conectado con el alias blessrom-db. Antes del primer despliegue, seguir [Separación de redes](red-blessrom.md) y guardar la conexión en el Compose que administra PostgreSQL. El backend no se conecta a mcp-facebook_default.

No colocar DB_PASSWORD en el YAML, en Git ni en los comandos de despliegue. El archivo del VPS es persistente y no se sobrescribe en cada actualización.

## 7. Configurar GitHub

En el repositorio backend-blessrom:
1. Settings → Environments → New environment → production.
2. Limitar Deployment branches a main.
3. Crear los siguientes Environment secrets:

| Nombre | Valor |
|---|---|
| VPS_HOST | 77.37.43.158, si sigue siendo la IP de tu VPS |
| VPS_PORT | 22, o tu puerto SSH real |
| VPS_USER | blessrom-deploy |
| VPS_SSH_KEY | Contenido completo de la clave privada blessrom_actions, incluyendo BEGIN/END |
| VPS_KNOWN_HOSTS | Línea completa obtenida desde PuTTY en el paso 5 |

4. Settings → Secrets and variables → Actions → Variables → New repository variable:
   DEPLOY_ENABLED = false al principio; cambiar a true cuando VPS, SSH y Keycloak estén listos.

La variable debe ser de REPOSITORIO, no de environment, porque se evalúa al decidir si comienza el job.

GITHUB_TOKEN lo proporciona Actions automáticamente. No necesitas crear un PAT permanente para GHCR: el workflow transmite por SSH un token temporal para descargar la imagen y elimina el archivo de login al terminar. Si el paquete ya existía con permisos propios, conceder al repositorio acceso al paquete desde Package settings → Manage Actions access.

No se guardan credenciales del VPS en los jobs de pruebas ni se proporcionan a PR externos.

## 8. Subir el backend a GitHub

En PowerShell:

~~~powershell
Set-Location "D:\Proyecto Blessrom\backend-blessrom"
git status --short
git add .
git diff --cached --stat
~~~

Comprobar que NO aparecen .tools, .env, contraseñas ni claves SSH. .gitignore excluye los archivos locales de herramientas y secretos. En este repositorio todavía hay archivos del backend inicial sin commit, por eso se debe subir el proyecto completo y no solo el workflow.

Luego:

~~~powershell
git commit -m "Add commercial backend and native deployment pipeline"
git push origin main
~~~

Si Git pide nombre, correo o autenticación, configurar tu cuenta habitual; no forzar un push ni reescribir historial para resolverlo.

En Actions → Backend CI and VPS deployment ver:
- test: pruebas JVM en PostgreSQL aislado.
- native: compilación Mandrel, prueba de arranque nativo y publicación en GHCR.
- deploy: omitido mientras DEPLOY_ENABLED=false.

La compilación nativa puede tardar varios minutos. Si hay falta de memoria en el runner, usar uno mayor o ajustar el presupuesto de compilación; no trasladar la compilación al VPS de 4 GB.

## 9. Primer despliegue

Cuando pasos 1–7 estén completos:
1. Cambiar la variable de repositorio DEPLOY_ENABLED a true.
2. Actions → Backend CI and VPS deployment → Run workflow → main.
3. Esperar a que terminen los tres jobs.

Antes de cada actualización, se guarda una copia de blessrom en /opt/blessrom/backups. No se respalda ni modifica la base keycloak. Revisar espacio y establecer después retención y copias fuera del VPS: una copia en el mismo disco no protege frente a pérdida del servidor.

El deploy puede causar una interrupción breve mientras se recrea el único contenedor. No es un despliegue sin interrupciones.

## 10. Verificar en PuTTY

~~~bash
docker ps --filter label=com.docker.compose.project=blessrom
curl --fail http://127.0.0.1:18082/q/health/ready
curl --fail http://127.0.0.1:18082/api/v1/system
~~~

Salud esperada: UP. El servicio se reinicia automáticamente con Docker si el proceso o el VPS reinician, siempre que Docker esté habilitado al iniciar.

Para ver logs:

~~~bash
cd /opt/blessrom/current
docker compose -p blessrom --env-file image.env -f compose.yaml logs --tail 100 backend
~~~

Desde tu computadora, agregar otro túnel PuTTY:
- Source port: 18082
- Destination: 127.0.0.1:18082

Abrir http://127.0.0.1:18082/api/v1/system. El puerto local 18082 evita confundir el backend del VPS con el backend de desarrollo local en 8082.

Swagger no se incluye por defecto en producción. La API administrativa requiere Bearer JWT del realm blessrom con rol admin; la contraseña Basic de desarrollo no funciona aquí.

Después de verificar el servicio desplegado, puedes detener el Quarkus local y cerrar el túnel de PostgreSQL. El backend del VPS se conectará directamente a la base por Docker.

## 11. Dominio y HTTPS después

Mantener blessrom.com en su sitio actual. La propuesta es usar api.blessrom.com para el backend. Todavía no se han hecho cambios DNS ni instalado/modificado un proxy.

Para publicar la API:
- comprobar qué proxy utiliza actualmente el VPS;
- crear el subdominio y un certificado TLS;
- enrutar el proxy a 127.0.0.1:18082 (o a la red Docker si el proxy es un contenedor);
- configurar el dominio canónico de Keycloak, sus clientes Angular y CORS;
- validar tokens, MFA y acceso administrativo.

No abrir el puerto 5432 a Internet. La primera entrega de este pipeline mantiene 18082 ligado a localhost del VPS.

## Recuperación

El script intenta restaurar la imagen anterior si falla la salud. Las migraciones Flyway pueden haber cambiado la base: las versiones anterior y nueva deben ser compatibles durante el despliegue. Para cambios destructivos, preparar una migración por etapas y un procedimiento de restauración probado.

No ejecutar un pg_restore automáticamente sobre producción. Ante un fallo, revisar logs y el backup creado antes de decidir una restauración.

No ejecutar docker system prune ni eliminar imágenes/backups de otros proyectos para liberar espacio. Mantener al menos la imagen actual y la anterior; revisar las copias antiguas con una política explícita.

## Referencias

- https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images
- https://quarkus.io/guides/building-native-image/
- https://www.keycloak.org/server/containers
- https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/

