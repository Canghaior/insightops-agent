package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.agent.application.AgentRunQuery.RunToolCall;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Text actually returned by a successful tool for a source cited in the frozen answer. */
final class ClaimPassages {
    record Passage(String label, String url, String version, String heading, String text) {}
    private ClaimPassages() {}

    static List<Passage> from(RunToolCall call, List<ChatCitation> citations, ObjectMapper json) {
        if (!"SUCCEEDED".equals(call.status())) return List.of();
        JsonNode payload = json.valueToTree(call.resultPayload());
        if (payload == null || !payload.isObject()) return List.of();
        List<Passage> passages = new ArrayList<>();
        for (var cite : citations) {
            if (cite.url() == null || cite.label() == null) continue;
            if ("GITHUB_RELEASE".equals(cite.sourceType()) && "github_release_list".equals(call.toolName())) {
                for (var row : payload.path("releases")) if (cite.url().equals(row.path("url").asText())
                        && Objects.equals(cite.versionLabel(), row.path("tagName").asText())) {
                    add(passages, cite, row.path("notesExcerpt"));
                }
            } else if ("GITHUB_ISSUE".equals(cite.sourceType()) && "github_issue_read".equals(call.toolName())) {
                var issue = payload.path("issue");
                if (cite.url().equals(issue.path("url").asText())) add(passages, cite, issue.path("body"));
            } else if ("knowledge_hybrid_search".equals(call.toolName())) {
                // Two chunks can share a URL, section and version. A relabeled citation alone
                // cannot establish which chunk was supplied under that label.
                long sameKey = citations.stream().filter(c -> Objects.equals(c.url(), cite.url())
                        && Objects.equals(c.heading(), cite.heading())
                        && Objects.equals(c.versionLabel(), cite.versionLabel())).count();
                if (sameKey != 1) continue;
                for (var row : payload.path("sources")) if (cite.url().equals(row.path("sourceUrl").asText())
                        && Objects.toString(cite.heading(), "").equals(row.path("heading").asText())
                        && Objects.toString(cite.versionLabel(), "未标注").equals(row.path("version").asText())) {
                    add(passages, cite, row.path("passage"));
                }
            }
        }
        return passages.stream().distinct().toList();
    }

    private static void add(List<Passage> passages, ChatCitation cite, JsonNode body) {
        if (!body.isTextual() || body.asText().isBlank()) return;
        var value = body.asText().strip();
        passages.add(new Passage(cite.label(), cite.url(), cite.versionLabel(), cite.heading(),
                value.substring(0, Math.min(value.length(), 3000))));
    }
}
