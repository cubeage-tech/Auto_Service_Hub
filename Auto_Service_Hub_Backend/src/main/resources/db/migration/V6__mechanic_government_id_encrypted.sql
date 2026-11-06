-- V6: Add encrypted Government ID storage to the mechanics table.
--
-- The column stores the AES-GCM ciphertext (base64-encoded IV + ciphertext)
-- produced by GovernmentIdEncryptionService.  Plaintext is never written here.
--
-- TEXT is used instead of VARCHAR(255) because the base64-encoded output of
-- AES-GCM with a 12-byte IV and a 12-digit input is ~72 characters, but TEXT
-- gives headroom if the input length or encoding changes without a migration.
--
-- NULL: existing rows retain NULL until a Government ID is voluntarily submitted.
-- The application never backfills, estimates, or fabricates values.
ALTER TABLE mechanics
    ADD COLUMN government_id_encrypted TEXT NULL;
