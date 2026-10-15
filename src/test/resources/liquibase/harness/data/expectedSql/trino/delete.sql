CREATE TABLE trino_harness.delete_t (id INT, txt VARCHAR(64))
INSERT INTO trino_harness.delete_t (id, txt) VALUES (1, 'остаётся')
INSERT INTO trino_harness.delete_t (id, txt) VALUES (2, 'удаляется')
DELETE FROM trino_harness.delete_t WHERE id = 2