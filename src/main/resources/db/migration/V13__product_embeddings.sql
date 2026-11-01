CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_embeddings (
    product_id uuid PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
    revision_id uuid NOT NULL REFERENCES product_revisions(id) ON DELETE CASCADE,
    embedding_model varchar(100) NOT NULL,
    embedding vector(1536) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX product_embeddings_hnsw_cosine
    ON product_embeddings USING hnsw (embedding vector_cosine_ops);
