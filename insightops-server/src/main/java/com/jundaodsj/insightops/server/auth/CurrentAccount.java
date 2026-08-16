package com.jundaodsj.insightops.server.auth;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import jakarta.servlet.http.HttpServletRequest;

public final class CurrentAccount {

    public static final String ATTRIBUTE = CurrentAccount.class.getName();

    private CurrentAccount() {
    }

    public static AccountWorkspaceStore.AccountRecord account(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof AccountWorkspaceStore.AccountRecord account) {
            return account;
        }
        throw new IllegalStateException("Authenticated account is missing from request");
    }

    public static ActorContext actor(HttpServletRequest request) {
        return account(request).actor();
    }
}
