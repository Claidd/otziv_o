package com.hunt.otziv.u_users.api;

import com.hunt.otziv.u_users.readmodel.BoardIdentityReadContext;

/** Opens one synchronous board assembly after authorization; exposes no identity models or queries. */
public final class BoardIdentityReadScope implements AutoCloseable {
    private final Runnable cleanup;
    private BoardIdentityReadScope() { cleanup = BoardIdentityReadContext.begin(); }
    public static BoardIdentityReadScope open() { return new BoardIdentityReadScope(); }
    @Override public void close() { cleanup.run(); }
}
