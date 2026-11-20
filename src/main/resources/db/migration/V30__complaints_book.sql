CREATE SEQUENCE complaint_number_seq START WITH 1;

CREATE TABLE complaints_book (
    id UUID PRIMARY KEY,
    number BIGINT NOT NULL UNIQUE DEFAULT nextval('complaint_number_seq'),
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('RECLAMO','QUEJA')),
    status VARCHAR(20) NOT NULL DEFAULT 'RECIBIDO' CHECK (status IN ('RECIBIDO','EN_REVISION','RESPONDIDO')),
    consumer JSONB NOT NULL,
    purchase JSONB NOT NULL,
    detail TEXT NOT NULL,
    request_text TEXT NOT NULL,
    provider JSONB NOT NULL,
    response_text TEXT,
    response_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX complaints_book_created ON complaints_book(created_at DESC);

CREATE TABLE complaint_email_outbox (
    id UUID PRIMARY KEY,
    complaint_id UUID NOT NULL REFERENCES complaints_book(id),
    recipient VARCHAR(254) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);
CREATE INDEX complaint_email_pending ON complaint_email_outbox(status,next_attempt_at) WHERE status='PENDING';
