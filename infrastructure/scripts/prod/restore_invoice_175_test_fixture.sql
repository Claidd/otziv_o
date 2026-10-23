-- Isolated disposable MySQL test schema. NEVER run on a live application database.
CREATE TABLE order_statuses(order_status_id BIGINT PRIMARY KEY,order_status_title VARCHAR(64));
CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_status BIGINT,row_version BIGINT,
 order_sum DECIMAL(12,2),order_status_changed_at DATETIME(6),order_pay_day DATE,protected_note TEXT);
CREATE TABLE common_billing_accounts(account_id BIGINT PRIMARY KEY,enabled BOOLEAN,protected_note TEXT);
CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY,account_id BIGINT,status VARCHAR(32),
 amount_kopecks BIGINT,paid_kopecks BIGINT,previous_status VARCHAR(32),close_reason VARCHAR(32),closed_by VARCHAR(160),
 closed_at DATETIME(6),supersedes_invoice_id BIGINT,sent_at DATETIME(6),paper_invoice_issued_at DATETIME(6),
 tbank_order_id VARCHAR(36),tbank_payment_id VARCHAR(64),tbank_terminal_key VARCHAR(64),tbank_payment_amount_kopecks BIGINT,
 payment_url VARCHAR(1024),payment_message_operation_id VARCHAR(128),payment_message_operation_kind VARCHAR(32),
 payment_message_confirmed BOOLEAN DEFAULT 0,payment_route_type VARCHAR(32),payment_route_profile_id BIGINT,
 contractor_allocation_id BIGINT,payment_route_manual_task_id BIGINT,payment_route_selected_at DATETIME(6),
 last_error VARCHAR(512),next_reminder_at DATETIME(6),updated_at DATETIME(6),token VARCHAR(96),protected_note TEXT);
CREATE TABLE common_invoice_orders(invoice_order_id BIGINT PRIMARY KEY,invoice_id BIGINT,order_id BIGINT,
 active_membership BOOLEAN,amount_kopecks BIGINT,ready BOOLEAN,paid BOOLEAN,unpaid BOOLEAN DEFAULT 0,
 paid_at DATETIME(6),source_payment_link_id BIGINT,payment_method VARCHAR(32),
 archive_source_order_status_title VARCHAR(64) DEFAULT 'На проверке',updated_at DATETIME(6),protected_note TEXT);
CREATE TABLE payment_links(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(32),payment_method VARCHAR(32),
 amount_kopecks BIGINT,confirmed_amount_kopecks BIGINT,reserved_amount_kopecks BIGINT,contractor_allocation_id BIGINT,
 paid_at DATETIME(6),provider_terminal_status VARCHAR(32),bank_init_nonce VARCHAR(36),bank_init_lease_until DATETIME(6),
 bank_cancel_nonce VARCHAR(36),bank_cancel_lease_until DATETIME(6),bank_cancel_origin_status VARCHAR(32),
 return_recovery_processed_at DATETIME(6),last_error VARCHAR(512),row_version BIGINT,updated_at DATETIME(6),protected_note TEXT,
 INDEX ix_order(order_id,id));
CREATE TABLE contractor_payment_allocations(id BIGINT PRIMARY KEY,mode VARCHAR(16),source_type VARCHAR(32),
 source_id BIGINT,order_id BIGINT,attempt_no INT,common_invoice_id BIGINT,manual_payment_task_id BIGINT,
 recipient_type VARCHAR(32),recipient_profile_id BIGINT,status VARCHAR(32),amount_kopecks BIGINT,
 confirmed_kopecks BIGINT,returned_kopecks BIGINT,needs_return_amount BIGINT,reconcile_claim_token VARCHAR(36),
 reconcile_lease_until DATETIME(6),reconcile_attempts INT,last_reconciled_at DATETIME(6),updated_at DATETIME(6),protected_note TEXT);
CREATE TABLE common_invoice_payment_refs(id BIGINT PRIMARY KEY,invoice_id BIGINT,INDEX ix_invoice(invoice_id));
CREATE TABLE common_invoice_message_queue(operation_id VARCHAR(160) PRIMARY KEY,invoice_id BIGINT,state VARCHAR(24),INDEX ix_invoice(invoice_id));
CREATE TABLE payment_link_return_reconciliation_outbox(outbox_id BIGINT PRIMARY KEY,payment_link_id BIGINT,status VARCHAR(16),INDEX ix_link(payment_link_id));
CREATE TABLE common_invoice_payment_notification_outbox(delivery_id BIGINT PRIMARY KEY,invoice_id BIGINT,INDEX ix_invoice(invoice_id));
CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,order_id BIGINT,delivery_status VARCHAR(32),
 locked_until DATETIME(6),delivery_token VARCHAR(64),delivery_envelope MEDIUMTEXT,sent_count INT,INDEX ix_order(order_id));
CREATE TABLE manager_daily_control_concrete_items(control_entity_id BIGINT PRIMARY KEY,entity_type VARCHAR(40),entity_id BIGINT);
CREATE TABLE manager_client_message_queue(operation_id VARCHAR(160) PRIMARY KEY,card_id BIGINT,state VARCHAR(24),
 error_code VARCHAR(100),claim_token VARCHAR(36),lease_until DATETIME(6),attempts INT,INDEX ix_card(card_id));
CREATE TABLE review_recovery_tasks(review_recovery_task_id BIGINT PRIMARY KEY,review_recovery_task_order BIGINT,review_recovery_task_status VARCHAR(32));
CREATE TABLE business_audit_events(event_id BIGINT AUTO_INCREMENT PRIMARY KEY,created_at DATETIME(6) NOT NULL,
 actor VARCHAR(150) NOT NULL,source VARCHAR(80) NOT NULL,action VARCHAR(80) NOT NULL,entity_type VARCHAR(40) NOT NULL,
 entity_id VARCHAR(80),order_id BIGINT,review_id BIGINT,old_value TEXT,new_value TEXT,details TEXT,
 INDEX ix_action(action,created_at)) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO order_statuses VALUES(1,'Архив'),(2,'Оплачено'),(3,'На проверке');
INSERT INTO orders VALUES
 (25645,1,11,1500,'2026-07-30 21:31:26',NULL,'first original order'),
 (25646,1,17,1500,'2026-08-30 10:00:00',NULL,'second original order'),
 (25647,2,23,1500,'2026-09-08 17:41:11.858199','2026-09-08','paid original order');
INSERT INTO common_billing_accounts VALUES(80,0,'disabled original account');
INSERT INTO common_invoices(invoice_id,account_id,status,amount_kopecks,paid_kopecks,previous_status,close_reason,closed_by,
 closed_at,next_reminder_at,updated_at,token,protected_note)
 VALUES(175,80,'PARTIALLY_PAID',450000,150000,'COLLECTING','MANUAL_ARCHIVE','vika','2026-07-30 21:31:26.321616',
 '2026-10-24 10:00:00','2026-09-08 17:41:11.858199','private-token-preserved','historical invoice');
INSERT INTO common_invoice_orders(invoice_order_id,invoice_id,order_id,active_membership,amount_kopecks,ready,paid,
 paid_at,source_payment_link_id,payment_method,updated_at,protected_note) VALUES
 (594,175,25645,1,150000,0,0,NULL,NULL,NULL,'2026-07-30 21:31:26','unpublished position A'),
 (595,175,25646,1,150000,0,0,NULL,NULL,NULL,'2026-07-30 21:31:26','unpublished position B'),
 (596,175,25647,1,150000,1,1,'2026-09-08 17:41:11.858199',7406,'TBANK','2026-09-08 17:41:11.858199','real paid position');
INSERT INTO payment_links(id,order_id,status,payment_method,amount_kopecks,confirmed_amount_kopecks,reserved_amount_kopecks,
 contractor_allocation_id,paid_at,provider_terminal_status,row_version,updated_at,protected_note)
 VALUES(7406,25647,'CONFIRMED','SBP_QR',150000,150000,150000,1145,'2026-09-08 17:41:11.858199','CONFIRMED',7,
 '2026-09-08 17:41:11.858199','bank evidence must survive');
INSERT INTO contractor_payment_allocations VALUES(1145,'LIVE','PAYMENT_LINK',7406,25647,1,NULL,NULL,'OWNER',NULL,
 'CONFIRMED',150000,150000,0,0,NULL,NULL,0,'2026-10-23 17:36:00','2026-10-23 17:36:00','financial allocation evidence');
INSERT INTO business_audit_events(event_id,created_at,actor,source,action,entity_type,entity_id,order_id,old_value,new_value,details) VALUES
 (41615,'2026-07-30 21:31:26','vika','common_billing','order_status_changed','order','25645',25645,'На проверке','Архив','original 1'),
 (41616,'2026-07-30 21:31:26','vika','common_billing','order_status_changed','order','25646',25646,'На проверке','Архив','original 2'),
 (41617,'2026-07-30 21:31:26','vika','common_billing','order_status_changed','order','25647',25647,'На проверке','Архив','original 3'),
 (41657,'2026-07-30 22:06:00','vika','manager_board','order_status_changed','order','25647',25647,'Архив','На проверке','restore 1'),
 (41658,'2026-07-30 22:06:00','vika','manager_board','order_status_changed','order','25646',25646,'Архив','На проверке','restore 2'),
 (84423,'2026-08-30 10:00:00','system','scheduled','order_status_changed','order','25646',25646,'На проверке','Архив','automatic archive');
