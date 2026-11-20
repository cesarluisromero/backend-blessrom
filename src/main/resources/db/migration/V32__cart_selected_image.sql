ALTER TABLE cart_items ADD COLUMN media_asset_id uuid REFERENCES media_assets(id) ON DELETE SET NULL;
