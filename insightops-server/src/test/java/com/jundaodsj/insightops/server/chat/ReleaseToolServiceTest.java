package com.jundaodsj.insightops.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.tool.application.AgentToolExecutionStore;
import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseToolServiceTest {

    @Test
    void shouldExecuteAndAuditReleaseTool() {
        RecordingStore store = new RecordingStore();
        ReleaseToolService service = new ReleaseToolService(
                new ReleaseQuestionRouter(),
                query -> new GitHubReleaseResult(List.of(new GitHubRelease(
                        "spring-ai",
                        "Spring AI",
                        "v2.0.0",
                        "Spring AI 2.0",
                        Instant.parse("2026-08-15T00:00:00Z"),
                        "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0",
                        false,
                        "Tool Calling improvements")), Instant.parse("2026-08-16T00:00:00Z")),
                store,
                new GitHubReleaseEvidenceFormatter(),
                new ObjectMapper().findAndRegisterModules());
        List<String> progress = new ArrayList<>();

        var evidence = service.execute(
                UUID.randomUUID(),
                "Spring AI 最新版本有哪些 Tool Calling 变化？",
                new ReleaseToolService.ToolProgressListener() {
                    @Override
                    public void onStarted(UUID toolCallId, String toolName) {
                        progress.add("started:" + toolName);
                    }

                    @Override
                    public void onCompleted(UUID toolCallId, String toolName, int releaseCount) {
                        progress.add("completed:" + releaseCount);
                    }
                });

        assertThat(evidence).isPresent();
        assertThat(evidence.orElseThrow().systemPromptAppendix())
                .contains("v2.0.0", "Tool Calling improvements");
        assertThat(evidence.orElseThrow().sourceUrls()).hasSize(1);
        assertThat(progress).containsExactly("started:github_release_list", "completed:1");
        assertThat(store.status).isEqualTo("SUCCEEDED");
        assertThat(store.requestPayload).contains("spring-ai");
        assertThat(store.resultPayload).contains("v2.0.0");
    }

    private static final class RecordingStore implements AgentToolExecutionStore {

        private String status;
        private String requestPayload;
        private String resultPayload;

        @Override
        public void startTool(
                UUID runId,
                UUID stepId,
                UUID toolCallId,
                int stepNo,
                String toolName,
                String idempotencyKey,
                String requestPayload,
                Instant startedAt) {
            this.status = "RUNNING";
            this.requestPayload = requestPayload;
        }

        @Override
        public void succeedTool(
                UUID runId,
                UUID stepId,
                UUID toolCallId,
                String resultPayload,
                long durationMs,
                Instant finishedAt) {
            this.status = "SUCCEEDED";
            this.resultPayload = resultPayload;
        }

        @Override
        public void failTool(
                UUID stepId,
                UUID toolCallId,
                String errorCode,
                long durationMs,
                Instant finishedAt) {
            this.status = "FAILED";
        }
    }
}
