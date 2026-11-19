CREATE TABLE storefront_menu (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 0,
    draft JSONB,
    published JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO storefront_menu (id) VALUES (1);
