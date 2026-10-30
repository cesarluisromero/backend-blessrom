CREATE TABLE customer_profiles (
  subject varchar(255) PRIMARY KEY,
  email varchar(254) UNIQUE,
  first_name varchar(100),
  last_name varchar(100),
  phone varchar(30),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE customer_addresses (
  id uuid PRIMARY KEY,
  customer_subject varchar(255) NOT NULL REFERENCES customer_profiles(subject) ON DELETE CASCADE,
  version bigint NOT NULL DEFAULT 0,
  label varchar(60) NOT NULL,
  recipient varchar(150) NOT NULL,
  phone varchar(30) NOT NULL,
  address varchar(500) NOT NULL,
  district_code varchar(6) NOT NULL,
  district_name varchar(150) NOT NULL,
  is_default boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT customer_address_district CHECK (district_code ~ '^[0-9]{6}$')
);

CREATE UNIQUE INDEX customer_default_address ON customer_addresses(customer_subject) WHERE is_default;
CREATE INDEX customer_addresses_owner ON customer_addresses(customer_subject,created_at);

ALTER TABLE orders ADD COLUMN customer_subject varchar(255) REFERENCES customer_profiles(subject);
CREATE INDEX orders_customer ON orders(customer_subject,created_at DESC) WHERE customer_subject IS NOT NULL;
