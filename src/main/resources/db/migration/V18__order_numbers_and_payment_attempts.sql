CREATE SEQUENCE order_number_seq START WITH 1;
ALTER TABLE orders ADD COLUMN order_number varchar(24);
UPDATE orders SET order_number='BLS-' || lpad(nextval('order_number_seq')::text,6,'0');
ALTER TABLE orders ALTER COLUMN order_number SET DEFAULT 'BLS-' || lpad(nextval('order_number_seq')::text,6,'0');
ALTER TABLE orders ALTER COLUMN order_number SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_order_number_unique UNIQUE (order_number);

CREATE TABLE order_payment_attempts (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  version bigint NOT NULL,
  method varchar(30) NOT NULL,
  status varchar(20) NOT NULL,
  reference varchar(100),
  proof_content_type varchar(30),
  proof_data bytea,
  provider_reference varchar(150),
  provider_status varchar(150),
  submitted_at timestamptz NOT NULL,
  reviewed_at timestamptz,
  reviewed_by varchar(150),
  review_note varchar(300),
  archived_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_attempts_order ON order_payment_attempts(order_id,archived_at DESC);
