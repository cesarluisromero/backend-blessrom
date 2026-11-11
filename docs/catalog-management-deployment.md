# Administración de catálogo y eliminación de productos

Desplegar backend con Flyway V24, seguido de administración y tienda. La carga de imágenes manual y el worker IA existente siguen usando las mismas credenciales.

V24 agrega `selectable` a categorías, colecciones y opciones, versiones de opciones, bloqueo `products.deleting` y la cola `product_media_purges`.

## Eliminación

- Archivar conserva su ruta `DELETE /api/v1/admin/products/{id}?version=...`.
- Eliminar definitivamente usa `DELETE /api/v1/admin/products/{id}/permanent?version=...` y `Idempotency-Key`. Productos con pedidos, stock/reservas o IA procesando se bloquean.
- Eliminar una imagen conserva la ruta `/products/{id}/media/{assetId}`. El nuevo panel envía versión e idempotencia; clientes anteriores siguen siendo compatibles.
- Ambas operaciones responden 202 con `{id,status,attempts,errorMessage}`. Consultar `/api/v1/admin/purges/{id}`; reintentar fallos con `POST /api/v1/admin/purges/{id}/retry`.
- Los archivos dejan de mostrarse al aceptar la operación. La eliminación física espera a que caduquen los PUT firmados, verifica ausencia en R2 y luego elimina las filas. Puede permanecer en cola hasta una hora por cargas antiguas.
- Scheduler activo: cada 15 segundos, hasta cinco intentos. Los fallos siguen visibles y se pueden reintentar desde Productos. No eliminar manualmente las filas pendientes de la cola.

## Caché de Cloudflare

Si el dominio público de medios tiene caché, configurar en el entorno del backend:

```env
CLOUDFLARE_CACHE_PURGE_ENABLED=true
CLOUDFLARE_ZONE_ID=<identificador de la zona de media.blessrom.com>
CLOUDFLARE_CACHE_PURGE_TOKEN=<token con permiso de purgar caché en esa zona>
```

El valor predeterminado es `false` para instalaciones sin caché. La clave R2 debe permitir DELETE y HEAD. Un fallo de purga de caché conserva la operación para reintento.

## Comprobación de despliegue

1. Confirmar Flyway V24 y arranque sin errores.
2. Abrir un pedido y comprobar listado de cinco; revisar categorías y opciones.
3. Verificar miniaturas en administración y carrito.
4. Usar un producto de prueba sin ventas ni stock para eliminar una imagen y luego el producto; esperar `SUCCEEDED` y comprobar ausencia de objetos y filas.
5. Confirmar que productos vendidos no se pueden eliminar y que las portadas IA independientes siguen disponibles.

Validación local: compilación y pruebas dirigidas. La comprobación real PostgreSQL/pgvector, R2 y ejecutable nativo debe realizarse en CI/entorno aislado; Docker local no estaba ejecutándose.
