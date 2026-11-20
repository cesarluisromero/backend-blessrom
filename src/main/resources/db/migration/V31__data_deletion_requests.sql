CREATE SEQUENCE data_deletion_request_seq START WITH 1;

CREATE TABLE data_deletion_requests (
    id UUID PRIMARY KEY,
    number BIGINT NOT NULL UNIQUE DEFAULT nextval('data_deletion_request_seq'),
    full_name VARCHAR(200) NOT NULL,
    document_type VARCHAR(20) NOT NULL,
    document_number VARCHAR(30) NOT NULL,
    whatsapp VARCHAR(30) NOT NULL,
    email VARCHAR(254) NOT NULL,
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'RECIBIDA' CHECK (status IN ('RECIBIDA','EN_REVISION','RESUELTA','RECHAZADA')),
    admin_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX data_deletion_requests_created ON data_deletion_requests(created_at DESC);

CREATE TABLE data_deletion_email_outbox (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES data_deletion_requests(id),
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
CREATE INDEX data_deletion_email_pending ON data_deletion_email_outbox(status,next_attempt_at) WHERE status='PENDING';
