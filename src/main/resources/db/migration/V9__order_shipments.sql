CREATE TABLE order_shipments (
  order_id uuid PRIMARY KEY REFERENCES orders(id),
  version bigint NOT NULL DEFAULT 0,
  carrier varchar(120) NOT NULL CHECK (btrim(carrier) <> ''),
  tracking_number varchar(120) NOT NULL CHECK (tracking_number ~ '^[A-Za-z0-9._/-]{3,120}$'),
  estimated_delivery date NOT NULL,
  notes varchar(500),
  shipped_at timestamptz,
  delivered_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(150) NOT NULL
);
