CREATE TABLE order_returns (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE REFERENCES orders(id),
  version bigint NOT NULL DEFAULT 0,
  status varchar(20) NOT NULL DEFAULT 'REQUESTED'
    CHECK (status IN ('REQUESTED','APPROVED','REJECTED','RECEIVED','REFUNDED','EXCHANGED')),
  requested_resolution varchar(20) NOT NULL CHECK (requested_resolution IN ('REFUND','EXCHANGE')),
  reason varchar(1000) NOT NULL CHECK (char_length(btrim(reason)) >= 10),
  customer_subject varchar(150) NOT NULL,
  admin_note varchar(1000),
  resolution_reference varchar(150),
  restocked boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  resolved_at timestamptz
);

CREATE TABLE order_return_items (
  return_id uuid NOT NULL,
  variant_id uuid NOT NULL,
  quantity integer NOT NULL CHECK (quantity > 0),
  PRIMARY KEY(return_id,variant_id),
  FOREIGN KEY(return_id) REFERENCES order_returns(id),
  FOREIGN KEY(variant_id) REFERENCES variants(id)
);

CREATE INDEX order_returns_status ON order_returns(status,created_at);
