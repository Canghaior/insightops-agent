package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Replays database-backed chat events; subscribers may connect to any Server instance. */
@Service
public class DurableChatStreamService {

    static final String RUN_ID_HEADER = "X-InsightOps-Run-Id";

    private final DurableChatRunStore store;
    private final DurableChatRunProperties properties;
    private final Executor executor;
    private final ObjectMapper json;
    private final DurableChatRunMetrics metrics;

    public DurableChatStreamService(
            DurableChatRunStore store,
            DurableChatRunProperties properties,
            @Qualifier("durableChatStreamExecutor") Executor executor,
            ObjectMapper json,
            DurableChatRunMetrics metrics) {
        this.store = store;
        this.properties = properties;
        this.executor = executor;
        this.json = json;
        this.metrics = metrics;
    }

    public SseEmitter open(ActorContext actor, UUID runId, long afterSequence) {
        if (store.findOwned(actor, runId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found");
        }
        metrics.streamOpened(afterSequence > 0);
        SseEmitter emitter = new RunAwareSseEmitter(
                runId, Duration.ofMinutes(15).toMillis());
        AtomicBoolean connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        emitter.onTimeout(() -> {
            connected.set(false);
            emitter.complete();
        });
        executor.execute(() -> replay(actor, runId, Math.max(0, afterSequence), emitter, connected));
        return emitter;
    }

    private void replay(
            ActorContext actor, UUID runId, long initialSequence,
            SseEmitter emitter, AtomicBoolean connected) {
        long cursor = initialSequence;
        try {
            while (connected.get()) {
                var events = store.events(actor, runId, cursor, 200);
                for (DurableChatRunStore.StoredEvent event : events) {
                    ObjectNode payload = payload(event, runId);
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(event.eventType())
                            .data(payload));
                    cursor = event.sequence();
                }
                metrics.replayed(events.size());
                DurableChatRunStore.WorkView work = store.findOwned(actor, runId).orElse(null);
                if (work == null || (work.terminal() && events.isEmpty())) break;
                Thread.sleep(properties.eventPollInterval().toMillis());
            }
            if (connected.get()) emitter.complete();
        }
        catch (IOException | IllegalStateException ignored) {
            connected.set(false);
            metrics.streamDisconnected();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            connected.set(false);
            metrics.streamDisconnected();
        }
        catch (RuntimeException exception) {
            metrics.streamDisconnected();
            if (connected.get()) emitter.completeWithError(exception);
        }
    }

    static final class RunAwareSseEmitter extends SseEmitter {

        private final UUID runId;

        RunAwareSseEmitter(UUID runId, long timeout) {
            super(timeout);
            this.runId = runId;
        }

        @Override
        protected void extendResponse(ServerHttpResponse outputMessage) {
            super.extendResponse(outputMessage);
            outputMessage.getHeaders().set(RUN_ID_HEADER, runId.toString());
            outputMessage.getHeaders().setCacheControl("no-cache, no-store, max-age=0");
            outputMessage.getHeaders().set("X-Accel-Buffering", "no");
        }
    }

    private ObjectNode payload(DurableChatRunStore.StoredEvent event, UUID runId) {
        try {
            JsonNode parsed = json.readTree(event.payloadJson());
            ObjectNode payload = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed : json.createObjectNode();
            payload.put("type", event.eventType());
            payload.put("runId", runId.toString());
            payload.put("sequence", event.sequence());
            payload.put("timestamp", event.createdAt().toString());
            return payload;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Stored chat event is invalid", exception);
        }
    }
}
