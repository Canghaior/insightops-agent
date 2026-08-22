package com.jundaodsj.insightops.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class P23ChatReliabilityMonitoringContractTest {

    private static final Set<String> ALERTS = Set.of(
            "AgentChatQueueBacklog", "AgentChatQueueOldestWaitHigh",
            "AgentChatQueueExpiredLease", "AgentChatRecoveryStalled",
            "AgentChatReclaimLatencyHigh", "AgentChatLeaseLostBurst",
            "AgentChatDispatchErrors", "AgentChatSloSnapshotErrors");

    @Test
    void prometheusRulesCoverDurableChatQueueAndRecoverySlo() throws IOException {
        String rules = Files.readString(root().resolve("infra/monitoring/alerts.yml"));

        ALERTS.forEach(alert -> assertThat(rules).contains("alert: " + alert));
        assertThat(rules).contains(
                "insightops_agent_chat_queue_queued",
                "insightops_agent_chat_queue_oldest_queued_age_seconds",
                "insightops_agent_chat_queue_expired_leases",
                "insightops_agent_chat_queue_reclaim_delay_seconds_bucket",
                "insightops_agent_chat_queue_snapshot_errors_total");
    }

    @Test
    void grafanaDashboardShowsQueueRecoveryAndSseResume() throws IOException {
        Path dashboard = root().resolve(
                "infra/monitoring/grafana/provisioning/dashboards/json/insightops-overview.json");
        JsonNode json = new ObjectMapper().readTree(dashboard.toFile());
        Set<String> titles = new HashSet<>();
        json.path("panels").forEach(panel -> titles.add(panel.path("title").asText()));

        assertThat(titles).contains(
                "Durable chat queue state", "Durable chat queue age",
                "Durable chat recovery", "Durable chat SSE resume");
        assertThat(json.path("version").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void productionDrillRequiresStrongConfirmationAndChecksCostFencing() throws IOException {
        String drill = Files.readString(root().resolve("scripts/p2-3-chat-takeover-drill.sh"));

        assertThat(drill).contains(
                "--confirm-production-restart",
                "docker kill --signal=KILL",
                "status_before\" != \"RUNNING",
                "agent_plan_checkpoint",
                "event_type = 'run_recovered'",
                "entry_type in ('SETTLE', 'RELEASE')",
                "terminal_ledger_after\" != \"1\"");
        assertThat(drill).doesNotContain("down -v", "rm -rf");
    }

    @Test
    void applicationConfigurationMapsSnapshotIntervalToTheChatQueue() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(
                root().resolve("insightops-server/src/main/resources/application.yml")));
        Map<String, Object> insightops = map(document, "insightops");
        Map<String, Object> agent = map(insightops, "agent");
        Map<String, Object> chatQueue = map(agent, "chat-queue");

        assertThat(chatQueue.get("snapshot-interval-ms").toString())
                .isEqualTo("${AGENT_CHAT_QUEUE_SNAPSHOT_INTERVAL_MS:15000}");
        assertThat(map(insightops, "report")).doesNotContainKey("snapshot-interval-ms");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("infra/monitoring"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate project root");
    }
}
