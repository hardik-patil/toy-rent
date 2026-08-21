CREATE TABLE notifications (
    id             VARCHAR(36)    PRIMARY KEY,
    booking_id     VARCHAR(36)    REFERENCES bookings(id),
    customer_id    VARCHAR(36)    NOT NULL REFERENCES customers(id),
    type           VARCHAR(100)   NOT NULL,
    channel        VARCHAR(50)    NOT NULL,
    message        TEXT           NOT NULL,
    status         VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    sent_at        TIMESTAMP,
    failure_reason VARCHAR(255),
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notifications_booking_id  ON notifications(booking_id);
CREATE INDEX idx_notifications_customer_id ON notifications(customer_id);
CREATE INDEX idx_notifications_status      ON notifications(status);
