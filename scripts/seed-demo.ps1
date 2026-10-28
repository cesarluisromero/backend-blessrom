param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$blessromUri=[uri]$BaseUrl
if (-not $blessromUri.IsLoopback) { throw 'Los datos demo solo pueden cargarse en localhost.' }
$blessromRoot=Split-Path $PSScriptRoot -Parent
$blessromSecrets=Get-Content (Join-Path $blessromRoot '.tools/local-secrets.json') | ConvertFrom-Json
$blessromBasic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$($blessromSecrets.AdminPassword)"))
$blessromHeaders=@{ Authorization="Basic $blessromBasic" }
function Invoke-BlessromDemo([string]$Method,[string]$Path,$Body) {
    $blessromArgs=@{ Method=$Method; Uri="$BaseUrl/api/v1$Path"; Headers=$blessromHeaders;NoProxy=$true;TimeoutSec=20 }
    if ($null -ne $Body) { $blessromArgs.ContentType='application/json'; $blessromArgs.Body=($Body | ConvertTo-Json -Depth 15) }
    Invoke-RestMethod @blessromArgs
}
$blessromCategories=Invoke-BlessromDemo GET '/categories' $null
$blessromCategory=$blessromCategories | Where-Object slug -EQ 'ropa-demo'
if (-not $blessromCategory) { $blessromCategory=Invoke-BlessromDemo POST '/admin/categories' @{name='Ropa de demostración';slug='ropa-demo'} }
$blessromProducts=(Invoke-BlessromDemo GET '/admin/products?size=100' $null).items
foreach ($blessromSample in @(
    @{slug='polo-azul-demo';name='Polo azul Blessrom (DEMO)';sku='DEMO-POLO-AZUL-M';price=59.90;color='Azul'},
    @{slug='camisa-lino-demo';name='Camisa de lino (DEMO)';sku='DEMO-CAMISA-ARENA-M';price=99.90;color='Arena'}
)) {
    if ($blessromProducts | Where-Object slug -EQ $blessromSample.slug) { continue }
    $blessromDraft=@{
        name=$blessromSample.name; description='Producto ficticio para probar el flujo comercial. Precio y stock de demostración.'
        categoryId=$blessromCategory.id;imageUrl=$null
        variants=@(@{sku=$blessromSample.sku;attributes=@{talla='M';color=$blessromSample.color};price=$blessromSample.price;enabled=$true})
    }
    $blessromProduct=Invoke-BlessromDemo POST '/admin/products' @{slug=$blessromSample.slug;draft=$blessromDraft}
    $blessromVariant=$blessromProduct.draft.variants[0].id
    Invoke-BlessromDemo POST "/admin/inventory/$blessromVariant/adjustments" @{version=0;delta=10;reason='Inventario ficticio DEMO'} | Out-Null
    Invoke-BlessromDemo POST "/admin/products/$($blessromProduct.id)/publish" @{version=0} | Out-Null
}
$blessromRates=Invoke-BlessromDemo GET '/admin/shipping-rates' $null
if (-not ($blessromRates | Where-Object district_code -EQ '999999')) {
    Invoke-BlessromDemo POST '/admin/shipping-rates' @{districtCode='999999';districtName='Distrito ficticio DEMO';amount=10;active=$true} | Out-Null
}
Write-Output 'Catálogo DEMO disponible. La tarifa 999999 es ficticia; no representa cobertura de envíos real.'


