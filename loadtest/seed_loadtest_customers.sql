-- Re-hashes the seeded login customer at a lower BCrypt cost, and (optionally) bulk-seeds
-- extra customers so the login test isn't 60 threads hammering one identical row. Ad hoc
-- load-test data, NOT a Flyway migration — run directly against the live pod, never
-- committed as V*__*.sql.
--
-- Why: bottleneck #1 (see learning/bottleneck-faced-resolved.md). BCrypt verification is
-- un-parallelisable CPU; a burst of concurrent logins on a ~1-vCPU-throttled pod pushed
-- login p99 past the 10s client timeout. BCryptPasswordEncoder.matches() takes its cost
-- from each stored hash's "$2a$NN$" prefix, so lowering security.bcrypt.strength in
-- application.yml has NO effect on rows that were seeded at cost 10 (V6__seed_sample_customer.sql)
-- — those rows must be re-hashed here at the matching cost.
--
-- The hash below is BCrypt cost 8 of the plaintext "password" (same secret as the V6 seed).
-- Keep security.bcrypt.strength / BCRYPT_STRENGTH == 8 so newly registered users match.
--
-- Usage:
--   kubectl cp loadtest/seed_loadtest_customers.sql infra/postgres-0:/tmp/seed_loadtest_customers.sql
--   kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -f /tmp/seed_loadtest_customers.sql
--
-- To undo (restore the original cost-10 hash for cust-0001, drop the bulk rows):
--   kubectl exec -n infra postgres-0 -- psql -U bookinguser -d bookingdb -c \
--     "UPDATE customers SET password_hash='$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG' WHERE id='cust-0001'; \
--      DELETE FROM customers WHERE id LIKE 'cust-lt-%';"

\set bcrypt_password_hash '$2a$08$o0KBGPMtPLaUkh9FKqI14.qEWj6cgJlbnVKXmoQYgwRwn74b2kXxW'

-- 1. Re-hash the existing V6 seed customer (phone 9821012345) at cost 8.
UPDATE customers
SET password_hash = :'bcrypt_password_hash',
    updated_at    = now()
WHERE id = 'cust-0001';

-- 2. Bulk customers: cust-lt-00001 .. cust-lt-00200, phones 9990000001 .. 9990000200,
--    all with the same cost-8 "password" hash. Point the JMeter plan's CUST_PHONE at a
--    CSV of these (or __Random) to spread logins across distinct rows.
INSERT INTO customers (id, name, phone, email, password_hash, area, city, is_active, created_at, updated_at)
SELECT
    'cust-lt-' || lpad(i::text, 5, '0'),
    'Load Test Customer ' || i,
    '999' || lpad(i::text, 7, '0'),
    'loadtest+' || i || '@example.com',
    :'bcrypt_password_hash',
    'Kharghar',
    'Navi Mumbai',
    TRUE,
    now(),
    now()
FROM generate_series(1, 200) AS i
ON CONFLICT (phone) DO NOTHING;

-- Sanity check
SELECT id, phone, left(password_hash, 7) AS hash_cost FROM customers WHERE id = 'cust-0001';
SELECT count(*) AS loadtest_customers FROM customers WHERE id LIKE 'cust-lt-%';
