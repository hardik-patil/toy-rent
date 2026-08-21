CREATE TABLE monthly_reports (
    id                VARCHAR(36)    PRIMARY KEY,
    month             INT            NOT NULL,
    year              INT            NOT NULL,
    total_bookings    INT            NOT NULL DEFAULT 0,
    total_revenue     NUMERIC(10,2)  NOT NULL DEFAULT 0,
    total_deposits    NUMERIC(10,2)  NOT NULL DEFAULT 0,
    pending_returns   INT            NOT NULL DEFAULT 0,
    top_toy_id        VARCHAR(36),
    top_toy_name      VARCHAR(255),
    pdf_storage_path  TEXT,
    status            VARCHAR(50)    NOT NULL DEFAULT 'GENERATING',
    generated_at      TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    UNIQUE (month, year)
);
CREATE INDEX idx_reports_month_year ON monthly_reports(year, month);
