CREATE TABLE site_information (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 0,
    draft JSONB NOT NULL,
    published JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO site_information (id, draft, published) VALUES
(1,
 '{"address":"Av. Lima 920","phones":["51931635969","51949545854","51928093343"],"pages":{"quienes-somos":{"title":"Quiénes somos","body":""},"privacidad":{"title":"Privacidad","body":""},"terminos":{"title":"Términos","body":""},"eliminar-datos":{"title":"Eliminar datos","body":""}}}',
 '{"address":"Av. Lima 920","phones":["51931635969","51949545854","51928093343"],"pages":{"quienes-somos":{"title":"Quiénes somos","body":""},"privacidad":{"title":"Privacidad","body":""},"terminos":{"title":"Términos","body":""},"eliminar-datos":{"title":"Eliminar datos","body":""}}}');
