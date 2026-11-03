CREATE TABLE storefront_pages (
    page_key varchar(40) PRIMARY KEY CHECK (page_key = 'home'),
    version bigint NOT NULL DEFAULT 0,
    draft_content jsonb,
    published_revision_id uuid,
    published_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (draft_content IS NULL OR jsonb_typeof(draft_content) = 'object')
);

CREATE TABLE storefront_revisions (
    id uuid PRIMARY KEY,
    page_key varchar(40) NOT NULL REFERENCES storefront_pages(page_key),
    content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(255) NOT NULL,
    UNIQUE (id, page_key)
);

ALTER TABLE storefront_pages ADD CONSTRAINT storefront_published_revision
    FOREIGN KEY (published_revision_id, page_key) REFERENCES storefront_revisions(id, page_key);

INSERT INTO storefront_pages(page_key) VALUES ('home');

ALTER TABLE media_assets ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE media_assets ADD COLUMN page_key varchar(40) REFERENCES storefront_pages(page_key);
ALTER TABLE media_assets ADD CONSTRAINT media_assets_one_owner
    CHECK ((product_id IS NOT NULL) <> (page_key IS NOT NULL));
ALTER TABLE media_assets ADD CONSTRAINT storefront_media_not_product_primary
    CHECK (page_key IS NULL OR NOT is_primary);
CREATE INDEX media_assets_page ON media_assets(page_key, status, created_at);
CREATE INDEX storefront_revisions_page ON storefront_revisions(page_key, created_at);
