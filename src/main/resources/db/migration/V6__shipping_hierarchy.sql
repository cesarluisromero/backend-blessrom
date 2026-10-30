ALTER TABLE shipping_rates ADD COLUMN department_name varchar(150);
ALTER TABLE shipping_rates ADD COLUMN province_name varchar(150);

UPDATE shipping_rates
SET department_name=CASE WHEN district_code LIKE '22%' THEN 'San Martín' ELSE 'Sin especificar' END,
    province_name=CASE WHEN district_code LIKE '2209%' THEN 'San Martín' ELSE 'Sin especificar' END;

ALTER TABLE shipping_rates ALTER COLUMN department_name SET NOT NULL;
ALTER TABLE shipping_rates ALTER COLUMN province_name SET NOT NULL;
CREATE INDEX shipping_rates_hierarchy ON shipping_rates(department_name,province_name,district_name) WHERE active=true;
