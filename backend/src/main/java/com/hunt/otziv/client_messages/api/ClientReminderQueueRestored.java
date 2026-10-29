package com.hunt.otziv.client_messages.api;

/** A future queue was actually restored; subscribers act only after its transaction commits. */
public record ClientReminderQueueRestored(java.time.LocalDate controlDate) { }
