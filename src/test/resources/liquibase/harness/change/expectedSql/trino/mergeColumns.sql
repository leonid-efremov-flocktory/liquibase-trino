CREATE TABLE trino_harness.full_name_table (first_name VARCHAR(50), last_name VARCHAR(50))
INSERT INTO trino_harness.full_name_table (first_name) VALUES ('John')
UPDATE trino_harness.full_name_table SET last_name = 'Doe' WHERE first_name='John'
INSERT INTO trino_harness.full_name_table (first_name) VALUES ('Jane')
UPDATE trino_harness.full_name_table SET last_name = 'Doe' WHERE first_name='Jane'
ALTER TABLE trino_harness.full_name_table ADD COLUMN full_name VARCHAR(255)
UPDATE trino_harness.full_name_table SET full_name = CONCAT(first_name, CONCAT(' ', last_name))
ALTER TABLE trino_harness.full_name_table DROP COLUMN first_name
ALTER TABLE trino_harness.full_name_table DROP COLUMN last_name