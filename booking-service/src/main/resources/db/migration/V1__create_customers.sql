CREATE TABLE customers (
    id             VARCHAR(36)    PRIMARY KEY,
    name           VARCHAR(255)   NOT NULL,
    phone          VARCHAR(15)    NOT NULL UNIQUE,
    email          VARCHAR(255)   UNIQUE,
    password_hash  VARCHAR(255)   NOT NULL,
    area           VARCHAR(100),
    flat           VARCHAR(100),
    building       VARCHAR(255),
    city           VARCHAR(100)   NOT NULL DEFAULT 'Navi Mumbai',
    pincode        VARCHAR(10),
    is_active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_customers_email ON customers(email);
