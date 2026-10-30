CREATE TABLE notification_outbox (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders,
  event varchar(40) NOT NULL,
  recipient varchar(254) NOT NULL,
  subject varchar(200) NOT NULL,
  text_body text NOT NULL,
  html_body text NOT NULL,
  status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error varchar(500),
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz
);

CREATE INDEX notification_delivery ON notification_outbox(status,next_attempt_at) WHERE status='PENDING';
CREATE INDEX notification_order ON notification_outbox(order_id,created_at);
