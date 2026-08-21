CREATE TABLE payments (
    id                   VARCHAR(36)    PRIMARY KEY,
    booking_id           VARCHAR(36)    NOT NULL REFERENCES bookings(id),
    customer_id          VARCHAR(36)    NOT NULL REFERENCES customers(id),
    amount               NUMERIC(10,2)  NOT NULL,
    type                 VARCHAR(50)    NOT NULL,
    method               VARCHAR(50)    NOT NULL,
    status               VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    razorpay_order_id    VARCHAR(255),
    razorpay_payment_id  VARCHAR(255),
    razorpay_signature   VARCHAR(500),
    failure_reason       VARCHAR(255),
    failure_code         VARCHAR(100),
    refund_id            VARCHAR(255),
    refunded_at          TIMESTAMP,
    created_at           TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_booking_id ON payments(booking_id);
CREATE INDEX idx_payments_status     ON payments(status);
