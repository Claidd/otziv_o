-- Deleting a product must never delete the order lines (and through them the reviews) that sell it.
-- The original order_details.order_detail_product -> products key cascades deletes (V1_1_0). Replace every key on
-- that column whose delete rule is not RESTRICT by one canonical RESTRICT key. The name on a server may differ
-- from the original one, so keys are found by column, not by name. Foreign key checks stay on.

-- 1. Orphan order lines would make the new key fail half way: stop before any ALTER.
DELIMITER $$

DROP PROCEDURE IF EXISTS preflight_order_details_product_orphans $$
CREATE PROCEDURE preflight_order_details_product_orphans()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM order_details od
        LEFT JOIN products p ON p.product_id = od.order_detail_product
        WHERE od.order_detail_product IS NOT NULL
          AND p.product_id IS NULL
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V1_10_409 order lines reference missing products';
    END IF;
END $$

DELIMITER ;

CALL preflight_order_details_product_orphans();
DROP PROCEDURE preflight_order_details_product_orphans;

-- 2. Drop every key on the column that does not already restrict deletes.
SET @cascading_fks = (
    SELECT GROUP_CONCAT(
        CONCAT('DROP FOREIGN KEY `', REPLACE(kcu.constraint_name, '`', '``'), '`')
        ORDER BY kcu.constraint_name SEPARATOR ', '
    )
    FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema
     AND rc.table_name = kcu.table_name
     AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE()
      AND kcu.table_name = 'order_details'
      AND kcu.column_name = 'order_detail_product'
      AND kcu.referenced_table_name = 'products'
      AND rc.delete_rule <> 'RESTRICT'
);
SET @sql = IF(@cascading_fks IS NULL, 'SELECT ''order_details.order_detail_product has no cascading key''',
    CONCAT('ALTER TABLE order_details ', @cascading_fks));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Add the canonical key unless an equivalent RESTRICT key is already there (a repeated run changes nothing).
SET @restrict_fk_exists = (
    SELECT COUNT(*)
    FROM information_schema.key_column_usage kcu
    JOIN information_schema.referential_constraints rc
      ON rc.constraint_schema = kcu.constraint_schema
     AND rc.table_name = kcu.table_name
     AND rc.constraint_name = kcu.constraint_name
    WHERE kcu.table_schema = DATABASE()
      AND kcu.table_name = 'order_details'
      AND kcu.column_name = 'order_detail_product'
      AND kcu.referenced_table_schema = DATABASE()
      AND kcu.referenced_table_name = 'products'
      AND kcu.referenced_column_name = 'product_id'
      AND rc.delete_rule = 'RESTRICT'
);
SET @sql = IF(@restrict_fk_exists = 0,
    'ALTER TABLE order_details ADD CONSTRAINT fk_order_details_product FOREIGN KEY (order_detail_product) REFERENCES products (product_id) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT ''order_details.order_detail_product already restricts deletes''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
