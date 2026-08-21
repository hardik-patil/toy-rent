-- password_hash below is BCrypt for plaintext "password" — dev/local seed data only
INSERT INTO customers (id, name, phone, email, password_hash, area, flat, building, city, pincode, is_active) VALUES
('cust-0001', 'Priya Deshmukh', '9821012345', 'priya.deshmukh@example.com', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', 'Kharghar', 'B-204', 'Neelkanth Heights', 'Navi Mumbai', '410210', TRUE);
