package com.hunt.otziv.p_products.api;

import org.springframework.security.core.Authentication;

/** Orders owns authorization, canonical locks and reference retirement. */
public interface ReviewPhotoRecords {
    /** Join a fresh READ_COMMITTED transaction; actor/scope is checked under canonical locks. */
    void requireAccess(long reviewId, Long expectedOrderId, Authentication actor);
    /** The same transaction must atomically attach storage metadata and enqueue the displaced object. */
    String replace(long reviewId, Long expectedOrderId, String url, Authentication actor);
    boolean retireForDeletion(String url);

    /** Transport-neutral missing review or parent relationship. HTTP adapters map this to 404. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
    }
}
