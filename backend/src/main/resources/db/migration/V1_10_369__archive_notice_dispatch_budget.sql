ALTER TABLE review_archive_notifications
  ADD COLUMN dispatch_reserved_at DATETIME(6) NULL,
  ADD KEY ix_archive_notice_budget(channel, state, dispatch_reserved_at);
