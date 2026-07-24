SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cities'
      AND COLUMN_NAME = 'latitude'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE cities ADD COLUMN latitude DECIMAL(10, 7) NULL',
    'SELECT ''cities.latitude exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cities'
      AND COLUMN_NAME = 'longitude'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE cities ADD COLUMN longitude DECIMAL(10, 7) NULL',
    'SELECT ''cities.longitude exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'cities'
      AND COLUMN_NAME = 'distance_matrix_ready'
);
SET @sql = IF(@column_exists = 0,
    'ALTER TABLE cities ADD COLUMN distance_matrix_ready BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT ''cities.distance_matrix_ready exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS city_distances (
    city_distance_id BIGINT NOT NULL AUTO_INCREMENT,
    from_city_id BIGINT NOT NULL,
    to_city_id BIGINT NOT NULL,
    distance_km INT NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (city_distance_id),
    UNIQUE KEY uk_city_distances_pair (from_city_id, to_city_id),
    INDEX idx_city_distances_from_distance (from_city_id, distance_km),
    INDEX idx_city_distances_to (to_city_id),
    CONSTRAINT fk_city_distances_from_city FOREIGN KEY (from_city_id) REFERENCES cities (city_id) ON DELETE CASCADE,
    CONSTRAINT fk_city_distances_to_city FOREIGN KEY (to_city_id) REFERENCES cities (city_id) ON DELETE CASCADE
) ENGINE=InnoDB;
