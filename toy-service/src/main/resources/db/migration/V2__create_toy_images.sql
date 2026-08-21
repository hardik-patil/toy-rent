CREATE TABLE toy_images (
    id           VARCHAR(36)   PRIMARY KEY,
    toy_id       VARCHAR(36)   NOT NULL REFERENCES toys(id) ON DELETE CASCADE,
    url          TEXT          NOT NULL,
    is_primary   BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order   INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_toy_images_toy_id ON toy_images(toy_id);
