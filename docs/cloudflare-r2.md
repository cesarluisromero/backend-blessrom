# Cloudflare R2 para imágenes de Blessrom

El backend no recibe los archivos. Autoriza cargas `PUT` de corta duración y Angular sube cuatro variantes WebP directamente a R2.

## Recursos

- Bucket privado: `blessrom-media-production`
- Dominio público: `media.blessrom.com`
- Token S3 limitado al bucket, con lectura y escritura de objetos

No copies credenciales de R2 al repositorio ni a GitHub. Guárdalas únicamente en `/opt/blessrom/backend.env` con permisos `600`.

## Variables del VPS

```env
R2_ENABLED=true
R2_ENDPOINT=https://ACCOUNT_ID.r2.cloudflarestorage.com
R2_BUCKET=blessrom-media-production
R2_ACCESS_KEY_ID=REEMPLAZAR_EN_EL_VPS
R2_SECRET_ACCESS_KEY=REEMPLAZAR_EN_EL_VPS
MEDIA_PUBLIC_BASE_URL=https://media.blessrom.com
R2_UPLOAD_EXPIRY_SECONDS=600
```

## CORS del bucket

Configura únicamente los orígenes administrativos que realizan cargas:

```json
[
  {
    "AllowedOrigins": [
      "https://admin.blessrom.com",
      "http://localhost:4201"
    ],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["Content-Type"],
    "ExposeHeaders": ["ETag"],
    "MaxAgeSeconds": 3600
  }
]
```

La tienda descarga las imágenes desde el dominio público y no necesita permisos de escritura.

## Flujo

1. El administrador guarda primero el producto.
2. Selecciona JPG, PNG, WebP o AVIF desde Angular.
3. El navegador genera `original`, `large`, `medium` y `thumbnail` en WebP.
4. Quarkus emite cuatro URLs firmadas válidas durante diez minutos.
5. Angular carga directamente a R2 y confirma los metadatos.
6. La imagen `medium` puede seleccionarse como portada y se guarda en el borrador.

HEIC/HEIF y TIFF dependen del soporte de decodificación del navegador. La interfaz informa al usuario si necesita convertirlos previamente; el archivo nunca se envía al VPS.
