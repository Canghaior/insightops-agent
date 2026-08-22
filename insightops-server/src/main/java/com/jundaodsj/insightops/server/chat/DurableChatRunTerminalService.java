package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.conversation.application.ChatCitation;
import com.jundaodsj.insightops.conversation.application.ChatRunStore;
import com.jundaodsj.insightops.conversation.application.DurableChatRunStore;
import com.jundaodsj.insightops.model.application.ModelUsage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Commits the fenced queue terminal state and user-visible chat result atomically. */
@Service
public class DurableChatRunTerminalService {

    private final DurableChatRunStore workStore;
    private final ChatRunStore chatRunStore;

    public DurableChatRunTerminalService(
            DurableChatRunStore workStore, ChatRunStore chatRunStore) {
        this.workStore = workStore;
        this.chatRunStore = chatRunStore;
    }

    @Transactional
    public boolean succeed(
            DurableChatRunStore.WorkLease lease,
            String answer,
            String provider,
            String model,
            ModelUsage usage,
            List<ChatCitation> citations,
            String eventJson,
            Instant finishedAt) {
        if (!workStore.markTerminal(
                lease.runId(), lease.leaseToken(), "SUCCEEDED", null,
                "completed", eventJson, finishedAt)) return false;
        chatRunStore.succeedRunWithCitations(
                lease.runId(), answer, provider, model, usage, citations, finishedAt);
        return true;
    }

    @Transactional
    public boolean cancel(
            DurableChatRunStore.WorkLease lease,
            String partialAnswer,
            String eventJson,
            Instant finishedAt) {
        if (!workStore.markTerminal(
                lease.runId(), lease.leaseToken(), "CANCELLED", null,
                "cancelled", eventJson, finishedAt)) return false;
        chatRunStore.cancelRun(lease.runId(), partialAnswer, finishedAt);
        return true;
    }

    @Transactional
    public boolean pause(
            DurableChatRunStore.WorkLease lease,
            String partialAnswer,
            String eventJson,
            Instant finishedAt) {
        if (!workStore.markTerminal(
                lease.runId(), lease.leaseToken(), "PAUSED", "RUN_PAUSED",
                "plan_paused", eventJson, finishedAt)) return false;
        chatRunStore.pauseRun(lease.runId(), partialAnswer, finishedAt);
        return true;
    }

    @Transactional
    public boolean fail(
            DurableChatRunStore.WorkLease lease,
            String partialAnswer,
            String failureCode,
            String eventJson,
            Instant finishedAt) {
        if (!workStore.markTerminal(
                lease.runId(), lease.leaseToken(), "FAILED", failureCode,
                "error", eventJson, finishedAt)) return false;
        chatRunStore.failRun(lease.runId(), partialAnswer, failureCode, finishedAt);
        return true;
    }
}
