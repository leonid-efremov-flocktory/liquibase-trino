CREATE SCHEMA IF NOT EXISTS iceberg_catalog.trino_harness
DROP TABLE IF EXISTS iceberg_catalog.trino_harness.authors
CREATE TABLE iceberg_catalog.trino_harness.authors (
id INTEGER NOT NULL,
first_name VARCHAR NOT NULL,
last_name VARCHAR NOT NULL,
email VARCHAR NOT NULL,
birthdate DATE NOT NULL,
added TIMESTAMP NOT NULL
) WITH (format = 'PARQUET')
INSERT INTO iceberg_catalog.trino_harness.authors VALUES (1, 'Eileen', 'Lubowitz', 'ppaucek@example.org', DATE '1991-03-04', TIMESTAMP '2004-05-30 02:08:25')
INSERT INTO iceberg_catalog.trino_harness.authors VALUES (2, 'Tamia', 'Mayert', 'shansen@example.org', DATE '2016-03-27', TIMESTAMP '2014-03-21 02:52:00')
DROP TABLE IF EXISTS iceberg_catalog.trino_harness.posts
CREATE TABLE iceberg_catalog.trino_harness.posts (
id INTEGER NOT NULL,
author_id INTEGER NOT NULL,
title VARCHAR NOT NULL,
description VARCHAR NOT NULL,
content VARCHAR NOT NULL,
inserted_date DATE
) WITH (format = 'PARQUET')
INSERT INTO iceberg_catalog.trino_harness.posts VALUES (1, 1, 'temporibus', 'voluptatum', 'Fugit non et doloribus repudiandae.', DATE '2015-11-18')
INSERT INTO iceberg_catalog.trino_harness.posts VALUES (2, 2, 'ea', 'aut', 'Tempora molestias maiores provident molestiae sint possimus quasi.', DATE '1975-06-08')