ALTER TABLE order_returns
  ADD COLUMN provider_refund_id varchar(150),
  ADD COLUMN refund_amount numeric(14,2),
  ADD COLUMN refund_status varchar(50),
  ADD COLUMN refund_requested_at timestamptz;

ALTER TABLE order_returns
  ADD CONSTRAINT order_returns_refund_amount_positive
  CHECK (refund_amount IS NULL OR refund_amount > 0);

CREATE UNIQUE INDEX order_returns_provider_refund_unique
  ON order_returns(provider_refund_id)
  WHERE provider_refund_id IS NOT NULL;
