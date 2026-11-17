CREATE TABLE home_product_sections (
    id SMALLINT PRIMARY KEY CHECK (id=1),
    version BIGINT NOT NULL DEFAULT 0,
    draft JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(draft)='array'),
    published JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(published)='array'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO home_product_sections(id) VALUES (1);
