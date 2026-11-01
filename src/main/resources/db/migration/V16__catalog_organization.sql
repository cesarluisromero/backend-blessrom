ALTER TABLE categories ADD COLUMN parent_id uuid REFERENCES categories(id);
ALTER TABLE categories ADD COLUMN description varchar(500) NOT NULL DEFAULT '';
ALTER TABLE categories ADD COLUMN display_order integer NOT NULL DEFAULT 0;
ALTER TABLE categories ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE categories ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE categories ADD CONSTRAINT categories_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id);
CREATE INDEX categories_parent ON categories(parent_id, display_order, name);

CREATE TABLE collections (
    id uuid PRIMARY KEY,
    version bigint NOT NULL DEFAULT 0,
    name varchar(120) NOT NULL,
    slug varchar(120) NOT NULL UNIQUE,
    description varchar(500) NOT NULL DEFAULT '',
    starts_at timestamptz,
    ends_at timestamptz,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE attribute_definitions (
    id uuid PRIMARY KEY,
    version bigint NOT NULL DEFAULT 0,
    code varchar(40) NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    name varchar(80) NOT NULL,
    type varchar(20) NOT NULL CHECK (type IN ('TEXT','SIZE','COLOR')),
    filterable boolean NOT NULL DEFAULT true,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0
);

CREATE TABLE attribute_options (
    id uuid PRIMARY KEY,
    attribute_id uuid NOT NULL REFERENCES attribute_definitions(id) ON DELETE CASCADE,
    value varchar(100) NOT NULL,
    label varchar(100) NOT NULL,
    swatch varchar(20),
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    UNIQUE(attribute_id, value),
    CHECK (swatch IS NULL OR swatch ~ '^#[0-9A-Fa-f]{6}$')
);
CREATE INDEX attribute_options_definition ON attribute_options(attribute_id, display_order, label);

INSERT INTO attribute_definitions(id,code,name,type,display_order) VALUES
('10000000-0000-0000-0000-000000000001','talla','Talla','SIZE',10),
('10000000-0000-0000-0000-000000000002','color','Color','COLOR',20);

INSERT INTO attribute_options(id,attribute_id,value,label,swatch,display_order) VALUES
('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','XS','XS',NULL,10),
('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','S','S',NULL,20),
('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001','M','M',NULL,30),
('20000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000001','L','L',NULL,40),
('20000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000001','XL','XL',NULL,50),
('20000000-0000-0000-0000-000000000006','10000000-0000-0000-0000-000000000001','XXL','XXL',NULL,60),
('20000000-0000-0000-0000-000000000011','10000000-0000-0000-0000-000000000002','Azul','Azul','#24579B',10),
('20000000-0000-0000-0000-000000000012','10000000-0000-0000-0000-000000000002','Negro','Negro','#111111',20),
('20000000-0000-0000-0000-000000000013','10000000-0000-0000-0000-000000000002','Blanco','Blanco','#FFFFFF',30),
('20000000-0000-0000-0000-000000000014','10000000-0000-0000-0000-000000000002','Rosado','Rosado','#E9A6B4',40),
('20000000-0000-0000-0000-000000000015','10000000-0000-0000-0000-000000000002','Verde','Verde','#4F7E62',50),
('20000000-0000-0000-0000-000000000016','10000000-0000-0000-0000-000000000002','Arena','Arena','#CBAE7B',60);
