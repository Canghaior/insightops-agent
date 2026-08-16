package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.model.application.ChatStreamSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamSessionRegistryTest {

    @Test
    void shouldCancelAttachedSessionAndNotifyClient() {
        ChatStreamSessionRegistry registry = new ChatStreamSessionRegistry();
        AtomicBoolean providerCancelled = new AtomicBoolean();
        AtomicBoolean clientNotified = new AtomicBoolean();
        registry.register("run-1", () -> clientNotified.set(true));
        registry.attach("run-1", session(providerCancelled));

        assertThat(registry.cancel("run-1")).isTrue();

        assertThat(providerCancelled).isTrue();
        assertThat(clientNotified).isTrue();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void shouldCancelLateProviderSessionAfterClientDisconnects() {
        ChatStreamSessionRegistry registry = new ChatStreamSessionRegistry();
        AtomicBoolean providerCancelled = new AtomicBoolean();
        AtomicBoolean clientNotified = new AtomicBoolean();
        registry.register("run-2", () -> clientNotified.set(true));

        assertThat(registry.disconnect("run-2")).isTrue();
        registry.attach("run-2", session(providerCancelled));

        assertThat(providerCancelled).isTrue();
        assertThat(clientNotified).isFalse();
    }

    private static ChatStreamSession session(AtomicBoolean cancelled) {
        return new ChatStreamSession() {
            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
    }
}
