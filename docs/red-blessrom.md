# Red dedicada de Blessrom

## Estado y alcance

El repositorio configura al backend únicamente en la red externa blessrom_backend.
PostgreSQL compartido debe estar en dos redes:
- su red actual mcp-facebook_default, para conservar los servicios existentes;
- blessrom_backend, con alias DNS blessrom-db.

No se crea otra instancia de PostgreSQL ni se mueve la base blessrom. Esta separación de red no aísla procesos, recursos o administradores de la instancia PostgreSQL compartida. Keycloak se consulta por su URL HTTPS pública configurada; no necesita conectarse a blessrom_backend.

La preparación del repositorio NO significa que la red haya sido creada en el VPS. Para hacerlo se requiere ejecutar los pasos siguientes desde PuTTY. No hay credenciales SSH del VPS disponibles para esta sesión de Codex.

## 1. Conexión en vivo

Después de copiar deploy/prepare-network.sh al servidor:

~~~bash
bash prepare-network.sh
~~~

El script es repetible, agrega la red con alias y no desconecta redes ni reinicia contenedores. Si existe la conexión sin el alias esperado, falla para revisión, sin desconectar la base.

Equivalente para una conexión nueva, ejecutado directamente en PuTTY:

~~~bash
docker network inspect blessrom_backend >/dev/null 2>&1 || docker network create --driver bridge blessrom_backend
docker network connect --alias blessrom-db blessrom_backend mcp-facebook-identity-db-1
~~~

Si indica que ya está conectado, comprobar el alias con docker inspect; no ejecutar network disconnect sobre la base en uso.

## 2. Encontrar la configuración que administra PostgreSQL

~~~bash
docker inspect --format 'service={{index .Config.Labels "com.docker.compose.service"}}
files={{index .Config.Labels "com.docker.compose.project.config_files"}}
directory={{index .Config.Labels "com.docker.compose.project.working_dir"}}' mcp-facebook-identity-db-1
~~~

Usar esos nombres reales. No asumir una ruta o editar el Compose del backend pensando que eso cambia el contenedor PostgreSQL de otro proyecto.

## 3. Hacerlo persistente

Guardar una copia del archivo Compose original antes de editarlo. Si hay varios archivos Compose, revisar el orden y preservar la combinación usada al desplegar ese proyecto.

En el servicio PostgreSQL identificado por la etiqueta service, conservar TODA su configuración (volúmenes, credenciales, imágenes, etc.) y añadir la nueva red. Si anteriormente no tenía networks, su red era la implícita default, que debe mantenerse explícitamente:

~~~yaml
services:
  NOMBRE_REAL_DEL_SERVICIO_POSTGRES:
    # Conservar todas las propiedades existentes.
    networks:
      default: {}
      blessrom_backend:
        aliases:
          - blessrom-db

networks:
  # Conservar también cualquier otra red previamente definida.
  default: {}
  blessrom_backend:
    external: true
    name: blessrom_backend
~~~

Si ya había una lista de redes o aliases, integrarlos; no reemplazarlos. No duplicar las claves networks, services o default en el mismo nivel YAML.

Validar con docker compose usando los mismos archivos (-f) y directorio del proyecto original, con config --quiet (no imprimir config completo porque puede resolver secretos). El contenido guardado persistirá en las siguientes recreaciones hechas con esa misma configuración.

No hace falta ejecutar down/up para aplicar la conexión actual: ya se agregó en vivo en el paso 1. Una futura recreación de PostgreSQL causará su interrupción habitual y deberá hacerse en una ventana adecuada. No ejecutar docker compose down -v.

## 4. Configuración del backend en el VPS

En /opt/blessrom/backend.env cambiar únicamente DB_URL:

~~~dotenv
DB_URL=jdbc:postgresql://blessrom-db:5432/blessrom
~~~

Conservar DB_USER=blessrom_app y su contraseña. El archivo de ejemplo local ya está actualizado. Si todavía no existe backend.env en el VPS, crearlo siguiendo despliegue-github-actions.md.

## 5. Comprobar antes de activar Actions

~~~bash
docker inspect --format '{{range $name, $network := .NetworkSettings.Networks}}{{printf "%s: %s\n" $name $network.IPAddress}}{{end}}' mcp-facebook-identity-db-1
docker inspect --format '{{with index .NetworkSettings.Networks "blessrom_backend"}}{{json .Aliases}}{{end}}' mcp-facebook-identity-db-1
docker exec mcp-facebook-identity-db-1 pg_isready -U keycloak -d blessrom
~~~

Esperado: ambas redes, alias blessrom-db y accepting connections.
Cuando el backend esté desplegado, su red debe ser solamente blessrom_backend:

~~~bash
docker inspect --format '{{json .NetworkSettings.Networks}}' "$(docker ps -q --filter label=com.docker.compose.project=blessrom --filter label=com.docker.compose.service=backend)"
curl --fail http://127.0.0.1:18082/q/health/ready
~~~

deploy.sh verifica la conexión y el alias de PostgreSQL antes de iniciar el despliegue. No crea ni conecta redes automáticamente durante una actualización.

## Referencias

- https://docs.docker.com/reference/cli/docker/network/connect/
- https://docs.docker.com/compose/how-tos/networking/
