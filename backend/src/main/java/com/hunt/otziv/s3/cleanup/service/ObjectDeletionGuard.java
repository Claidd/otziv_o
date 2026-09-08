package com.hunt.otziv.s3.cleanup.service;

/** Storage calls reference owners before deleting an immutable object. */
public interface ObjectDeletionGuard {
    boolean mayDelete(String bucket, String key);
}
