CREATE TABLE media_assets (
    id uuid PRIMARY KEY,
    product_id uuid NOT NULL REFERENCES products(id),
    status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','READY','DELETED')),
    original_filename varchar(255) NOT NULL,
    alt_text varchar(300),
    display_order integer NOT NULL DEFAULT 0 CHECK(display_order>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    ready_at timestamptz
);

CREATE TABLE media_objects (
    asset_id uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    variant varchar(20) NOT NULL CHECK(variant IN ('original','large','medium','thumbnail')),
    object_key varchar(700) NOT NULL UNIQUE,
    width integer CHECK(width>0),
    height integer CHECK(height>0),
    byte_size bigint CHECK(byte_size>0),
    PRIMARY KEY(asset_id,variant)
);

CREATE INDEX media_assets_product ON media_assets(product_id,status,display_order,created_at);
