CREATE TABLE activity_events (
    id uuid PRIMARY KEY,
    event_type varchar(60) NOT NULL,
    entity_type varchar(30) NOT NULL,
    entity_id uuid,
    label varchar(200) NOT NULL,
    actor varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX activity_recent ON activity_events(created_at DESC,id DESC);
CREATE INDEX orders_created_status ON orders(created_at DESC,status,id);
CREATE INDEX returns_pending ON order_returns(status,created_at DESC,id);
