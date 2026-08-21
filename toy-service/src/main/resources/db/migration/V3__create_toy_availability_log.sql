CREATE TABLE toy_availability_log (
    id            VARCHAR(36)   PRIMARY KEY,
    toy_id        VARCHAR(36)   NOT NULL REFERENCES toys(id),
    booking_id    VARCHAR(36),
    blocked_from  DATE          NOT NULL,
    blocked_to    DATE          NOT NULL,
    action        VARCHAR(50)   NOT NULL,
    reason        VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_avail_log_toy_id ON toy_availability_log(toy_id);
CREATE INDEX idx_avail_log_dates  ON toy_availability_log(toy_id, blocked_from, blocked_to);
