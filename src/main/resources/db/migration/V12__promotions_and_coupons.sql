CREATE TABLE promotions (
  id uuid PRIMARY KEY,
  version bigint NOT NULL DEFAULT 0,
  name varchar(150) NOT NULL,
  code varchar(40) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]{3,40}$'),
  discount_type varchar(10) NOT NULL CHECK (discount_type IN ('PERCENT','FIXED')),
  discount_value numeric(14,2) NOT NULL CHECK (discount_value > 0),
  minimum_subtotal numeric(14,2) NOT NULL DEFAULT 0 CHECK (minimum_subtotal >= 0),
  usage_limit integer CHECK (usage_limit IS NULL OR usage_limit > 0),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL CHECK (ends_at > starts_at),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (discount_type <> 'PERCENT' OR discount_value <= 100)
);

DO $$
DECLARE total_constraint record;
BEGIN
  FOR total_constraint IN
    SELECT conname
    FROM pg_constraint
    WHERE conrelid = 'orders'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%total%'
      AND pg_get_constraintdef(oid) LIKE '%subtotal%'
      AND pg_get_constraintdef(oid) LIKE '%shipping%'
  LOOP
    EXECUTE format('ALTER TABLE orders DROP CONSTRAINT %I', total_constraint.conname);
  END LOOP;
END $$;
ALTER TABLE orders
  ADD COLUMN discount numeric(14,2) NOT NULL DEFAULT 0 CHECK (discount >= 0 AND discount <= subtotal),
  ADD COLUMN promotion_id uuid REFERENCES promotions(id),
  ADD COLUMN coupon_code varchar(40),
  ADD CONSTRAINT orders_total_check CHECK (total = subtotal - discount + shipping);

CREATE TABLE coupon_redemptions (
  promotion_id uuid NOT NULL REFERENCES promotions(id),
  order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (promotion_id,order_id)
);

CREATE INDEX promotions_active_period ON promotions(active,starts_at,ends_at);
