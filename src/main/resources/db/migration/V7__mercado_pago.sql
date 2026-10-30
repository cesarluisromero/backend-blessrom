ALTER TABLE order_payments DROP CONSTRAINT IF EXISTS order_payments_status_check;
ALTER TABLE order_payments DROP CONSTRAINT IF EXISTS order_payments_method_check;

ALTER TABLE order_payments
  ADD COLUMN provider_reference varchar(150),
  ADD COLUMN checkout_url text,
  ADD COLUMN external_payment_id varchar(150),
  ADD COLUMN provider_status varchar(50);

ALTER TABLE order_payments ADD CONSTRAINT order_payments_status_check
  CHECK (status IN ('PENDING_PROVIDER','SUBMITTED','APPROVED','REJECTED'));
ALTER TABLE order_payments ADD CONSTRAINT order_payments_method_check
  CHECK (method IN ('MERCADO_PAGO','YAPE','PLIN','BANK_TRANSFER','CASH_ON_DELIVERY'));

CREATE UNIQUE INDEX order_payments_external_payment
  ON order_payments(external_payment_id) WHERE external_payment_id IS NOT NULL;
