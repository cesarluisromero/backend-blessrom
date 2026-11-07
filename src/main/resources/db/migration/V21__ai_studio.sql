ALTER TABLE media_assets DROP CONSTRAINT media_assets_status_check;
ALTER TABLE media_assets ADD CONSTRAINT media_assets_status_check
    CHECK(status IN ('PENDING','STAGED','READY','DELETED'));

CREATE TABLE ai_studio_uploads (
    id uuid PRIMARY KEY,
    product_id uuid REFERENCES products(id),
    object_key varchar(700) NOT NULL UNIQUE,
    original_filename varchar(255) NOT NULL,
    content_type varchar(80) NOT NULL CHECK(content_type IN ('image/jpeg','image/png','image/webp','image/avif')),
    byte_size bigint NOT NULL CHECK(byte_size BETWEEN 1 AND 20971520),
    sha256 varchar(64),
    status varchar(20) NOT NULL DEFAULT 'UPLOADING' CHECK(status IN ('UPLOADING','READY','EXPIRED')),
    created_by varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '30 days'
);

CREATE TABLE ai_studio_jobs (
    id uuid PRIMARY KEY,
    job_type varchar(20) NOT NULL CHECK(job_type IN ('CUTOUT','COVER','COPY_REWRITE','CUTOUT_REFINEMENT','COVER_REFINEMENT')),
    product_id uuid REFERENCES products(id),
    page_key varchar(50),
    request jsonb NOT NULL,
    request_hash varchar(64) NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED','RUNNING','REVIEW_REQUIRED','PARTIAL','FAILED','CANCELLED','APPLIED')),
    attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 9),
    lease_token uuid,
    lease_expires_at timestamptz,
    error_code varchar(80),
    error_message varchar(500),
    created_by varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(created_by,idempotency_key)
);

CREATE INDEX ai_studio_jobs_queue ON ai_studio_jobs(status,created_at);
CREATE INDEX ai_studio_jobs_product ON ai_studio_jobs(product_id,created_at DESC);

CREATE TABLE ai_studio_results (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES ai_studio_jobs(id) ON DELETE CASCADE,
    slot integer NOT NULL CHECK(slot BETWEEN 0 AND 2),
    revision integer NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 3),
    status varchar(30) NOT NULL DEFAULT 'GENERATED' CHECK(status IN ('GENERATED','REJECTED','APPROVED','STAGED','APPLIED','FAILED')),
    image_approved boolean NOT NULL DEFAULT false,
    copy_approved boolean NOT NULL DEFAULT false,
    content jsonb,
    desktop_objects jsonb,
    mobile_objects jsonb,
    private_keys jsonb NOT NULL DEFAULT '{}'::jsonb,
    feedback varchar(1000),
    adopted_asset_id uuid REFERENCES media_assets(id),
    adopted_mobile_asset_id uuid REFERENCES media_assets(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(job_id,slot,revision)
);

CREATE INDEX ai_studio_results_job ON ai_studio_results(job_id,slot,revision DESC);
