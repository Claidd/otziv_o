-- Удаление каскадного обновления для order_worker и добавление столбца order_worker_changed

-- Удаление каскадного обновления для order_worker
ALTER TABLE orders
DROP FOREIGN KEY order_worker;

-- Добавление ограничения для order_worker с отключением каскадного обновления
ALTER TABLE orders
ADD CONSTRAINT order_worker
FOREIGN KEY (order_worker)
REFERENCES workers (worker_id)
ON DELETE CASCADE
ON UPDATE NO ACTION;


ALTER TABLE reviews
DROP FOREIGN KEY review_worker;

-- Добавление ограничения для review_worker с отключением каскадного обновления
ALTER TABLE reviews
ADD CONSTRAINT review_worker
FOREIGN KEY (review_worker)
REFERENCES workers (worker_id)
ON DELETE CASCADE
ON UPDATE NO ACTION;





