ALTER TABLE collections ADD COLUMN featured boolean NOT NULL DEFAULT false;
ALTER TABLE collections ADD COLUMN cover_image_url varchar(2000);
ALTER TABLE collections ADD CONSTRAINT collections_cover_https
    CHECK (cover_image_url IS NULL OR cover_image_url ~ '^https://[^[:space:]]+$');

CREATE INDEX collections_storefront
    ON collections(featured, active, display_order, name);
