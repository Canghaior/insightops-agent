package com.jundaodsj.insightops.identity.application;

import java.util.UUID;

public record ActorContext(UUID userId, UUID workspaceId) {

    public ActorContext {
        if (userId == null || workspaceId == null) {
            throw new IllegalArgumentException("userId and workspaceId are required");
        }
    }
}
