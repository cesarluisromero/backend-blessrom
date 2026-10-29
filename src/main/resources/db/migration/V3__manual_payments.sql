CREATE TABLE order_payments (
  order_id uuid PRIMARY KEY REFERENCES orders,
  version bigint NOT NULL DEFAULT 0,
  status varchar(20) NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
  method varchar(30) NOT NULL CHECK (method IN ('YAPE','PLIN','BANK_TRANSFER','CASH_ON_DELIVERY')),
  reference varchar(100),
  proof_content_type varchar(30),
  proof_data bytea,
  submitted_at timestamptz NOT NULL DEFAULT now(),
  reviewed_at timestamptz,
  reviewed_by varchar(150),
  review_note varchar(300),
  CHECK ((proof_data IS NULL) = (proof_content_type IS NULL))
);

CREATE INDEX payments_status ON order_payments(status,submitted_at);
