-- Bulk-seeds the toys table to a realistic volume for load testing the catalogue
-- browse endpoint (GET /api/v1/toys?category=&ageGroup=). Ad hoc data, NOT a Flyway
-- migration — run directly against the live pod, never committed as V*__*.sql.
-- See learning/composite-index-load-testing.md for why 50k rows (not the 8-row
-- V4 seed) is needed before the missing composite index becomes measurable.
--
-- Usage:
--   kubectl cp loadtest/seed_toys_bulk.sql infra/postgres-0:/tmp/seed_toys_bulk.sql
--   kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -f /tmp/seed_toys_bulk.sql
--
-- To undo (back to the original 8-row V4 seed):
--   kubectl exec -n infra postgres-0 -- psql -U toyuser -d toydb -c \
--     "DELETE FROM toys WHERE id LIKE 'toy-bulk-%';"

INSERT INTO toys (
    id, name, description, brand, category, age_group, condition, status,
    mrp, weekly_price, monthly_price, deposit_amount, is_active, created_at, updated_at
)
SELECT
    'toy-bulk-' || i,
    'Load Test Toy ' || i,
    'Synthetic row generated for load testing — not real inventory',
    (ARRAY['LEGO','Fisher-Price','Hot Wheels','Mattel','Nerf','Melissa & Doug','Ravensburger'])[1 + floor(random() * 7)::int],
    -- Real category/age_group values only — matches the 8 in V4__seed_sample_toys.sql,
    -- same pool loadtest/data/browse_params.csv already draws from.
    (ARRAY['BUILDING_BLOCKS','INFANT_TOYS','PLAYSETS','DOLLHOUSES','OUTDOOR_TOYS','PRETEND_PLAY','REMOTE_CONTROL','PUZZLES_GAMES'])[1 + floor(random() * 8)::int],
    (ARRAY['0-2','3-6','3-7','5-8','6-12','6-99','8-12','9-12'])[1 + floor(random() * 8)::int],
    (ARRAY['NEW','GOOD','FAIR','POOR'])[1 + floor(random() * 4)::int],
    -- Skewed toward AVAILABLE (matches real inventory shape — most stock isn't mid-rental).
    (ARRAY['AVAILABLE','AVAILABLE','AVAILABLE','AVAILABLE','RENTED','CLEANING'])[1 + floor(random() * 6)::int],
    round((500 + random() * 12000)::numeric, 2),
    round((99 + random() * 600)::numeric, 2),
    round((349 + random() * 2000)::numeric, 2),
    round((300 + random() * 2200)::numeric, 2),
    -- ~95% active, matching is_active's real-world skew.
    random() > 0.05,
    now(),
    now()
FROM generate_series(1, 50000) AS i;

-- Sanity check
SELECT count(*) AS total_rows FROM toys;
SELECT count(*) AS bulk_rows FROM toys WHERE id LIKE 'toy-bulk-%';
