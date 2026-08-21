CREATE TABLE bookings (
    id                  VARCHAR(36)    PRIMARY KEY,
    toy_id              VARCHAR(36)    NOT NULL,
    customer_id         VARCHAR(36)    NOT NULL REFERENCES customers(id),
    start_date          DATE           NOT NULL,
    end_date            DATE           NOT NULL,
    rental_type         VARCHAR(20)    NOT NULL,
    rental_amount       NUMERIC(10,2)  NOT NULL,
    deposit_amount      NUMERIC(10,2)  NOT NULL,
    total_amount        NUMERIC(10,2)  NOT NULL,
    status              VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    payment_status      VARCHAR(50)    NOT NULL DEFAULT 'PENDING',
    delivery_flat       VARCHAR(100),
    delivery_building   VARCHAR(255),
    delivery_area       VARCHAR(100),
    delivery_city       VARCHAR(100),
    delivery_pincode    VARCHAR(10),
    cancelled_by        VARCHAR(50),
    cancel_reason       TEXT,
    cancelled_at        TIMESTAMP,
    returned_at         TIMESTAMP,
    return_condition    VARCHAR(50),
    damage_notes        TEXT,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_bookings_toy_id      ON bookings(toy_id);
CREATE INDEX idx_bookings_customer_id ON bookings(customer_id);
CREATE INDEX idx_bookings_status      ON bookings(status);
CREATE INDEX idx_bookings_dates       ON bookings(start_date, end_date);
CREATE INDEX idx_bookings_end_date    ON bookings(end_date) WHERE status = 'ACTIVE';
CREATE INDEX idx_bookings_admin       ON bookings(status, start_date, end_date);
