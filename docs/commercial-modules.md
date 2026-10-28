# Módulos comerciales — primera entrega

## Arquitectura

Monolito modular Quarkus / Java 25. Servicios separados de catálogo, inventario, envíos, carrito y pedidos. PostgreSQL conserva el estado; Flyway aplica migraciones. Esta entrega usa JDBC con Agroal y transacciones JTA explícitas para controlar el orden de bloqueo y la concurrencia del inventario. No utiliza Hibernate/Panache.

El pool está limitado a ocho conexiones por instancia. Las reservas sobreviven a reinicios. El proceso periódico puede ejecutarse en varias instancias: cada liberación bloquea el pedido y verifica su estado dentro de la transacción. Antes de aumentar réplicas, dimensionar el límite total de conexiones y probar carga. El frontend sigue en su repositorio separado.

## Comportamiento implementado

- Categorías: creación y consulta.
- Productos: creación de borrador, edición con versión optimista, publicación y archivo lógico. Cada publicación crea una revisión inmutable; editar un borrador no altera el escaparate.
- Variantes: SKU único, atributos (talla/color/capacidad/etc.), precio en PEN y activación. Se conservan las identidades y los históricos al retirar variantes.
- Imágenes: URL HTTPS en el borrador. Todavía no hay carga de archivos ni almacenamiento de imágenes.
- Inventario: un almacén lógico, existencias y reservas, ajustes con motivo/usuario y movimientos de auditoría.
- Envíos: tarifa administrable por código UBIGEO de seis dígitos. No se presupone cobertura nacional; solo se permiten distritos con tarifa activa. La validación comprueba el formato, no un padrón oficial de UBIGEO.
- Carrito de invitado: token aleatorio que se entrega una vez; la base guarda solo su hash. Caduca en 30 días.
- Cotización: recalcula precios publicados y envío. Una huella permite detectar cambios antes de comprar.
- Checkout: transacción completa, stock bloqueado en orden estable, reserva de 30 minutos y clave de idempotencia. Un carrito genera un solo pedido.
- Pedido: copia histórica de producto, SKU, atributos, importes y datos de entrega. Consulta con el token original del carrito; acceso por 30 días.
- Cancelación y vencimiento liberan stock una sola vez. El proceso revisa vencimientos cada 30 segundos, en lotes de 100.
- Administración: exige rol admin. Basic con contraseña aleatoria solo en desarrollo; en producción exige JWT de OIDC/Keycloak.

Estado del pedido: PENDING_PAYMENT → CANCELLED o EXPIRED. Todavía no se acepta ni confirma ningún pago. No hay un endpoint que permita al navegador marcar pedidos como pagados.

## API

Prefijo /api/v1. Swagger local: http://localhost:8080/q/swagger-ui/

| Área | Operaciones |
|---|---|
| Catálogo público | GET /categories, GET /products?page=0&size=20&q=polo&category=UUID, GET /products/{id} |
| Administración catálogo | POST /admin/categories; GET/POST /admin/products; GET /admin/products/{id}; PUT /admin/products/{id}/draft; POST /admin/products/{id}/publish; DELETE /admin/products/{id}?version=N |
| Inventario | GET /admin/inventory/{variantId}; POST /admin/inventory/{variantId}/adjustments; GET /admin/inventory/{variantId}/movements |
| Envíos | GET /shipping-rates; GET/POST /admin/shipping-rates; PUT /admin/shipping-rates/{id} |
| Carrito | POST /carts; GET /carts/{id}; PUT/DELETE /carts/{id}/items/{variantId} |
| Cotización | GET /carts/{id}/quote?district=220901 |
| Checkout | POST /carts/{id}/checkout |
| Pedido | GET /orders/{id}; POST /orders/{id}/cancel |
| Administración pedidos | GET /admin/orders; GET /admin/orders/{id} |

El carrito y el pedido requieren X-Cart-Token. Checkout requiere además Idempotency-Key (8–100 caracteres: letras, números, guion o guion bajo). Repetir exactamente el mismo cuerpo y clave devuelve el mismo pedido (200); la primera creación devuelve 201. Una solicitud distinta sobre ese carrito devuelve 409.

Ediciones de producto, publicación, ajustes y edición de tarifa reciben version. Usar la versión devuelta por la última consulta. Conflicto de versión: 409 VERSION_CONFLICT. Los errores de negocio incluyen code y message. UUID no encontrado o token incorrecto: 404. Validación del cuerpo: 400.

## Ejemplo de flujo

1. Crear categoría con name y slug.
2. Crear producto con slug y draft: name, description, categoryId, imageUrl, variants.
3. Cada variante incluye sku, attributes, price y enabled. El detalle administrativo devuelve el UUID de la variante.
4. Agregar inventario con version, delta y reason.
5. Publicar producto enviando version.
6. Configurar tarifa: districtCode, districtName, amount, active.
7. Crear carrito; conservar accessToken como secreto del comprador.
8. Agregar variante enviando quantity.
9. Solicitar cotización y mostrar subtotal, shipping y total.
10. Enviar checkout: recipient, email, phone, address, districtCode y quoteFingerprint (fingerprint de la cotización).
11. Si devuelve QUOTE_CHANGED, mostrar la nueva cotización y solicitar confirmación antes de reintentar.

No guardar tokens en URLs ni en logs. Los importes se calculan con BigDecimal. Algunos DTO devuelven importes como texto decimal y las consultas JDBC como números JSON; Angular debe tratarlos como valores monetarios, sin recalcular el cobro.

## Modelo persistente

categories → products (categoría incluida en el contenido versionado).
products → product_drafts / product_revisions / variants.
variants → inventory / inventory_movements.
carts → cart_items → variants.
orders → order_items / order_history; orders referencia un único carrito.
shipping_rates identifica la tarifa vigente por distrito.

El contenido editorial y atributos se guardan como JSONB; stock, relaciones de carrito/pedido e importes históricos se mantienen en columnas tipadas. La categoría se verifica en el servicio al guardar borradores. No hay eliminación física de categorías ni productos. La reserva activa se representa por estado/fecha del pedido y cantidades en order_items, sin una tabla de reservas adicional en esta etapa.

## Pendientes para producción

Pasarela de pago por confirmar, credenciales y webhooks verificados, conciliación e idempotencia de eventos; transición a pagado y consumo definitivo de stock; cuentas y fusión de carritos; configuración real de Keycloak/MFA; archivos de imágenes; comprobantes e impuestos según reglas del negocio; políticas de devoluciones y despacho; copias de seguridad, retención de datos, observabilidad y límites de solicitudes.

El backend no aplica cargos reales ni envía correos. No se han conectado las aplicaciones Angular ni el asistente de IA. No ejecutar esta instancia de desarrollo como tienda pública.

## Compilación nativa y VPS

Se mantiene el perfil native con Mandrel Java 25. Compilar y probar el ejecutable Linux en CI u otra máquina con recursos; no en el VPS de 1 vCPU / 4 GB. Esta entrega debe verificarse también en modo nativo antes del despliegue. La verificación JVM no certifica todavía la imagen nativa.

