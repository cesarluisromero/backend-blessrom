ALTER TABLE media_assets
    ADD COLUMN home_role varchar(10),
    ADD COLUMN upload_expires_at timestamptz,
    ADD CONSTRAINT media_assets_home_role_check CHECK (
        home_role IS NULL OR (page_key IS NOT DISTINCT FROM 'home' AND home_role IN ('DESKTOP','MOBILE'))
    );

ALTER TABLE media_assets DROP CONSTRAINT media_assets_status_check;
ALTER TABLE media_assets ADD CONSTRAINT media_assets_status_check
    CHECK (status IN ('PENDING','STAGED','READY','DELETED','PURGE_PENDING'));

CREATE TABLE storefront_slide_purges (
    id uuid PRIMARY KEY,
    desktop_asset_id uuid NOT NULL,
    page_version bigint NOT NULL CHECK (page_version >= 0),
    idempotency_key varchar(200) NOT NULL,
    requested_by varchar(255) NOT NULL,
    asset_ids jsonb NOT NULL CHECK (jsonb_typeof(asset_ids) = 'array' AND jsonb_array_length(asset_ids) BETWEEN 1 AND 2),
    object_keys jsonb NOT NULL CHECK (jsonb_typeof(object_keys) = 'array'),
    status varchar(12) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    error_message varchar(500),
    next_attempt_at timestamptz NOT NULL,
    lease_token uuid,
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (requested_by,idempotency_key)
);

CREATE INDEX storefront_slide_purges_queue
    ON storefront_slide_purges(next_attempt_at,created_at)
    WHERE status IN ('QUEUED','RUNNING');
