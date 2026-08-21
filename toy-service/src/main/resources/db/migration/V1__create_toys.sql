CREATE TABLE toys (
    id                VARCHAR(36)     PRIMARY KEY,
    name              VARCHAR(255)    NOT NULL,
    description       TEXT,
    brand             VARCHAR(100),
    category          VARCHAR(100)    NOT NULL,
    age_group         VARCHAR(50)     NOT NULL,
    condition         VARCHAR(50)     NOT NULL DEFAULT 'GOOD',
    status            VARCHAR(50)     NOT NULL DEFAULT 'AVAILABLE',
    mrp               NUMERIC(10,2)   NOT NULL,
    weekly_price      NUMERIC(10,2)   NOT NULL,
    monthly_price     NUMERIC(10,2)   NOT NULL,
    deposit_amount    NUMERIC(10,2)   NOT NULL,
    is_active         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_toys_category    ON toys(category);
CREATE INDEX idx_toys_age_group   ON toys(age_group);
CREATE INDEX idx_toys_status      ON toys(status);
CREATE INDEX idx_toys_is_active   ON toys(is_active);
