ALTER TABLE categories ADD COLUMN selectable boolean NOT NULL DEFAULT true;
ALTER TABLE collections ADD COLUMN selectable boolean NOT NULL DEFAULT true;
ALTER TABLE attribute_options ADD COLUMN selectable boolean NOT NULL DEFAULT true;
ALTER TABLE attribute_options ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN deleting boolean NOT NULL DEFAULT false;
CREATE TABLE product_media_purges (
 id uuid PRIMARY KEY, product_id uuid NOT NULL, asset_id uuid,
 actor varchar(255) NOT NULL, idempotency_key varchar(200) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 keys jsonb NOT NULL, asset_ids jsonb NOT NULL,
 attempts integer NOT NULL DEFAULT 0, error_message varchar(500),
 next_attempt_at timestamptz NOT NULL, lease_expires_at timestamptz, lease_token uuid,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(actor,idempotency_key)
);
CREATE INDEX product_media_purges_queue ON product_media_purges(status,next_attempt_at);
