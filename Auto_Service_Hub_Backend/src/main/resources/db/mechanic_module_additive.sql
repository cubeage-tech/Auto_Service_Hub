-- Manual, additive MySQL schema update for the mechanic module.
-- This project has no Flyway/Liquibase runner; apply this script explicitly
-- before deploying with application-prod.yml (ddl-auto=validate).
-- Existing attendance, task, feedback, and legacy job-card rows are retained.

SET @mechanic_schema = DATABASE();

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'attendance' AND COLUMN_NAME = 'mechanic_id') = 0,
    'ALTER TABLE attendance ADD COLUMN mechanic_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks' AND COLUMN_NAME = 'job_card_id') = 0,
    'ALTER TABLE job_tasks ADD COLUMN job_card_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks' AND COLUMN_NAME = 'mechanic_id') = 0,
    'ALTER TABLE job_tasks ADD COLUMN mechanic_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks' AND COLUMN_NAME = 'completed_at') = 0,
    'ALTER TABLE job_tasks ADD COLUMN completed_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks' AND COLUMN_NAME = 'completed_by_user_id') = 0,
    'ALTER TABLE job_tasks ADD COLUMN completed_by_user_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'feedback' AND COLUMN_NAME = 'job_card_id') = 0,
    'ALTER TABLE feedback ADD COLUMN job_card_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @mechanic_schema AND TABLE_NAME = 'feedback' AND COLUMN_NAME = 'mechanic_id') = 0,
    'ALTER TABLE feedback ADD COLUMN mechanic_id BIGINT NULL',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

CREATE TABLE IF NOT EXISTS job_card_mechanics (
    job_card_id BIGINT NOT NULL,
    mechanic_id BIGINT NOT NULL,
    PRIMARY KEY (job_card_id, mechanic_id),
    CONSTRAINT fk_job_card_mechanics_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id),
    CONSTRAINT fk_job_card_mechanics_mechanic FOREIGN KEY (mechanic_id) REFERENCES mechanics (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS job_card_required_skills (
    job_card_id BIGINT NOT NULL,
    required_skill VARCHAR(255) NOT NULL,
    PRIMARY KEY (job_card_id, required_skill),
    CONSTRAINT fk_job_card_required_skills_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS job_card_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    version BIGINT NULL,
    from_status VARCHAR(255) NULL,
    to_status VARCHAR(255) NOT NULL,
    job_card_id BIGINT NOT NULL,
    changed_by_user_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_job_card_status_history_card_created (job_card_id, created_at),
    CONSTRAINT fk_job_card_status_history_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id),
    CONSTRAINT fk_job_card_status_history_user FOREIGN KEY (changed_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS work_notes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NULL,
    version BIGINT NULL,
    content TEXT NOT NULL,
    job_card_id BIGINT NOT NULL,
    job_task_id BIGINT NULL,
    author_user_id BIGINT NOT NULL,
    mechanic_id BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_work_notes_card_created (job_card_id, created_at),
    CONSTRAINT fk_work_notes_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id),
    CONSTRAINT fk_work_notes_task FOREIGN KEY (job_task_id) REFERENCES job_tasks (id),
    CONSTRAINT fk_work_notes_author FOREIGN KEY (author_user_id) REFERENCES users (id),
    CONSTRAINT fk_work_notes_mechanic FOREIGN KEY (mechanic_id) REFERENCES mechanics (id)
) ENGINE=InnoDB;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'attendance'
       AND COLUMN_NAME = 'mechanic_id' AND REFERENCED_TABLE_NAME = 'mechanics') = 0,
    'ALTER TABLE attendance ADD CONSTRAINT fk_attendance_mechanic FOREIGN KEY (mechanic_id) REFERENCES mechanics (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks'
       AND COLUMN_NAME = 'job_card_id' AND REFERENCED_TABLE_NAME = 'job_cards') = 0,
    'ALTER TABLE job_tasks ADD CONSTRAINT fk_job_tasks_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks'
       AND COLUMN_NAME = 'mechanic_id' AND REFERENCED_TABLE_NAME = 'mechanics') = 0,
    'ALTER TABLE job_tasks ADD CONSTRAINT fk_job_tasks_mechanic FOREIGN KEY (mechanic_id) REFERENCES mechanics (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'job_tasks'
       AND COLUMN_NAME = 'completed_by_user_id' AND REFERENCED_TABLE_NAME = 'users') = 0,
    'ALTER TABLE job_tasks ADD CONSTRAINT fk_job_tasks_completed_by FOREIGN KEY (completed_by_user_id) REFERENCES users (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'feedback'
       AND COLUMN_NAME = 'job_card_id' AND REFERENCED_TABLE_NAME = 'job_cards') = 0,
    'ALTER TABLE feedback ADD CONSTRAINT fk_feedback_job_card FOREIGN KEY (job_card_id) REFERENCES job_cards (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

SET @mechanic_sql = IF(
    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
     WHERE CONSTRAINT_SCHEMA = @mechanic_schema AND TABLE_NAME = 'feedback'
       AND COLUMN_NAME = 'mechanic_id' AND REFERENCED_TABLE_NAME = 'mechanics') = 0,
    'ALTER TABLE feedback ADD CONSTRAINT fk_feedback_mechanic FOREIGN KEY (mechanic_id) REFERENCES mechanics (id)',
    'SELECT 1'
);
PREPARE mechanic_stmt FROM @mechanic_sql;
EXECUTE mechanic_stmt;
DEALLOCATE PREPARE mechanic_stmt;

-- Copy legacy single-mechanic assignments without changing the legacy column.
INSERT INTO job_card_mechanics (job_card_id, mechanic_id)
SELECT jc.id, jc.mechanic_id
FROM job_cards jc
WHERE jc.mechanic_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM job_card_mechanics jcm
      WHERE jcm.job_card_id = jc.id AND jcm.mechanic_id = jc.mechanic_id
  );