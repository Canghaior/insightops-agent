package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.server.api.ChatStreamController.ChatSseEvent;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DurableChatRunCoordinator {

    private final DurableChatRunStore store;
    private final DurableChatStreamService streams;
    private final DurableChatRunProperties properties;
    private final ObjectMapper json;

    public DurableChatRunCoordinator(
            DurableChatRunStore store,
            DurableChatStreamService streams,
            DurableChatRunProperties properties,
            ObjectMapper json) {
        this.store = store;
        this.streams = streams;
        this.properties = properties;
        this.json = json;
    }

    public boolean enabled() { return properties.isEnabled(); }

    public void enqueue(
            ActorContext actor,
            UUID runId,
            UUID sessionId,
            String traceId,
            boolean systemAdmin,
            AgentToolDefinition.AccessLevel accessLevel,
            String userPrompt,
            String contextualPrompt,
            UUID resumeCheckpointId,
            Instant createdAt) {
        ChatSseEvent started = new ChatSseEvent(
                "started", runId.toString(), sessionId, 0, createdAt, traceId,
                null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), List.of(), null);
        store.enqueue(new DurableChatRunStore.WorkDraft(
                        runId, actor.workspaceId(), actor.userId(), sessionId, traceId,
                        systemAdmin, accessLevel.name(), userPrompt, contextualPrompt,
                        resumeCheckpointId, properties.safeMaxAttempts(), createdAt),
                json(started));
    }

    public SseEmitter enqueueAndOpen(
            ActorContext actor,
            UUID runId,
            UUID sessionId,
            String traceId,
            boolean systemAdmin,
            AgentToolDefinition.AccessLevel accessLevel,
            String userPrompt,
            String contextualPrompt,
            UUID resumeCheckpointId,
            Instant createdAt) {
        ChatSseEvent started = new ChatSseEvent(
                "started", runId.toString(), sessionId, 0, createdAt, traceId,
                null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), List.of(), null);
        store.enqueue(new DurableChatRunStore.WorkDraft(
                        runId, actor.workspaceId(), actor.userId(), sessionId, traceId,
                        systemAdmin, accessLevel.name(), userPrompt, contextualPrompt,
                        resumeCheckpointId, properties.safeMaxAttempts(), createdAt),
                json(started));
        return streams.open(actor, runId, 0);
    }

    public SseEmitter open(ActorContext actor, UUID runId, long afterSequence) {
        return streams.open(actor, runId, afterSequence);
    }

    public DurableChatStreamService.ReplayBatch readBatch(
            ActorContext actor, UUID runId, long afterSequence) {
        return streams.readBatch(actor, runId, afterSequence);
    }

    public boolean requestCancel(ActorContext actor, UUID runId) {
        return store.requestCancel(actor, runId, Instant.now());
    }

    public boolean ownsWork(ActorContext actor, UUID runId) {
        return store.ownsWork(actor, runId);
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("CHAT_EVENT_SERIALIZATION_FAILED", exception);
        }
    }
}
