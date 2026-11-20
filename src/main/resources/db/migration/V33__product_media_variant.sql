ALTER TABLE media_assets ADD COLUMN variant_sku varchar(80);
CREATE INDEX media_assets_variant_sku ON media_assets(product_id,variant_sku) WHERE status='READY' AND variant_sku IS NOT NULL;
