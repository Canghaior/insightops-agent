package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableChatStreamServiceTest {

    @Test
    void exposesDurableRunIdentityAndDisablesProxyBuffering() {
        UUID runId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);
        var emitter = new DurableChatStreamService.RunAwareSseEmitter(runId, 15_000L);

        emitter.extendResponse(response);

        assertEquals(runId.toString(), headers.getFirst(
                DurableChatStreamService.RUN_ID_HEADER));
        assertEquals("no", headers.getFirst("X-Accel-Buffering"));
        assertTrue(headers.getCacheControl().contains("no-cache"));
        assertTrue(headers.getCacheControl().contains("no-store"));
        assertTrue(headers.getCacheControl().contains("no-transform"));
    }

    @Test
    void sendsHeartbeatWhileNonTerminalRunHasNoEvents() throws Exception {
        UUID runId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        ActorContext actor = new ActorContext(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"));
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        DurableChatRunProperties properties = new DurableChatRunProperties();
        properties.setEventPollMs(50);
        properties.setStreamHeartbeatMs(250);
        DurableChatRunMetrics metrics = mock(DurableChatRunMetrics.class);
        DurableChatStreamService service = new DurableChatStreamService(
                store, properties, Runnable::run, new ObjectMapper(), metrics);
        DurableChatRunStore.WorkView running = new DurableChatRunStore.WorkView(
                runId, "RUNNING", 1, 3, "server-1", Instant.now(),
                Instant.now().plusSeconds(30), null, null, null, Instant.now());
        when(store.events(eq(actor), eq(runId), eq(0L), eq(200))).thenReturn(List.of());
        when(store.findOwned(actor, runId)).thenReturn(Optional.of(running));
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean connected = new AtomicBoolean(true);
        doAnswer(invocation -> {
            connected.set(false);
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        service.replay(actor, runId, 0L, emitter, connected);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void readsDurableEventsAsJsonBatchForNonStreamingRecovery() {
        UUID runId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        ActorContext actor = new ActorContext(
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("77777777-7777-4777-8777-777777777777"));
        DurableChatRunStore store = mock(DurableChatRunStore.class);
        DurableChatRunProperties properties = new DurableChatRunProperties();
        DurableChatRunMetrics metrics = mock(DurableChatRunMetrics.class);
        DurableChatStreamService service = new DurableChatStreamService(
                store, properties, Runnable::run, new ObjectMapper(), metrics);
        DurableChatRunStore.WorkView succeeded = new DurableChatRunStore.WorkView(
                runId, "SUCCEEDED", 1, 3, "server-1", Instant.now(),
                null, null, null, null, Instant.now());
        when(store.findOwned(actor, runId)).thenReturn(Optional.of(succeeded));
        when(store.events(actor, runId, 0L, 200)).thenReturn(List.of(
                new DurableChatRunStore.StoredEvent(
                        1, "started", "{}", Instant.parse("2026-08-24T08:00:00Z")),
                new DurableChatRunStore.StoredEvent(
                        2, "completed", "{\"provider\":\"deepseek\"}",
                        Instant.parse("2026-08-24T08:00:01Z"))));

        DurableChatStreamService.ReplayBatch batch = service.readBatch(actor, runId, 0L);

        assertEquals("SUCCEEDED", batch.status());
        assertTrue(batch.terminal());
        assertEquals(2L, batch.lastSequence());
        assertEquals(List.of("started", "completed"), batch.events().stream()
                .map(event -> event.path("type").asText()).toList());
        assertEquals("deepseek", batch.events().getLast().path("provider").asText());
    }
}
