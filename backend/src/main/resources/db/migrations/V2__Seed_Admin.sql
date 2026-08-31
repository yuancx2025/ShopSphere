-- Seed a default admin so catalog mutations can be tested.
-- Login: admin@shopsphere.com / admin123
INSERT INTO users (first_name, last_name, email, password, role) VALUES
('Admin', 'User', 'admin@shopsphere.com', '$2a$10$ZJvbYH1UYarOF/oKpTGPL.n9/DneXT5RR7kjnjKyXx1BptilVeksu', 'ADMIN');
