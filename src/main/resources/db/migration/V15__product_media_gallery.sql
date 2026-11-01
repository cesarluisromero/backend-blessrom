ALTER TABLE media_assets ADD COLUMN is_primary boolean NOT NULL DEFAULT false;

UPDATE media_assets asset
SET is_primary=true
WHERE asset.id IN (
    SELECT DISTINCT ON (product_id) id
    FROM media_assets
    WHERE status='READY'
    ORDER BY product_id,display_order,created_at
);

CREATE UNIQUE INDEX media_assets_one_primary
    ON media_assets(product_id)
    WHERE is_primary AND status='READY';
