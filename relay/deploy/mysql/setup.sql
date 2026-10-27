-- us2us relay database and users, least privilege.
-- Run once as root:  sudo mysql < deploy/mysql/setup.sql
-- Replace both passwords first (e.g. `openssl rand -base64 24`).

CREATE DATABASE IF NOT EXISTS us2us CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- The relay itself: reads and writes rows, cannot change the schema.
CREATE USER IF NOT EXISTS 'us2us'@'localhost' IDENTIFIED WITH caching_sha2_password BY 'CHANGE_ME_RELAY_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON us2us.* TO 'us2us'@'localhost';

-- Migrations only: schema changes, used by deploy/runtime-migrate.mjs during deploys.
CREATE USER IF NOT EXISTS 'us2us_migrate'@'localhost' IDENTIFIED WITH caching_sha2_password BY 'CHANGE_ME_MIGRATE_PASSWORD';
-- REFERENCES is needed to create the community foreign keys; never grant it to the relay.
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON us2us.* TO 'us2us_migrate'@'localhost';

FLUSH PRIVILEGES;
