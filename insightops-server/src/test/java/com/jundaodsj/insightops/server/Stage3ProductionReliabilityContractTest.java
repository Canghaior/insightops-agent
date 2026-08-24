package com.jundaodsj.insightops.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Stage3ProductionReliabilityContractTest {

    @Test
    void prometheusRoutesProjectHealthAlertsThroughAlertmanager() throws IOException {
        String compose = read("infra/compose.prod.yml");
        String prometheus = read("infra/monitoring/prometheus.yml");
        String alertmanager = read("infra/monitoring/alertmanager.yml");
        String alerts = read("infra/monitoring/alerts.yml");
        String metrics = read("insightops-server/src/main/java/com/jundaodsj/insightops/"
                + "server/knowledge/KnowledgeOperationalMetrics.java");

        assertThat(compose).contains(
                "prom/alertmanager:v0.33.1",
                "127.0.0.1:${ALERTMANAGER_PORT:-9093}:9093",
                "../.secrets/alertmanager-webhook-url",
                "alertmanager-data:/alertmanager");
        assertThat(prometheus).contains("alertmanagers:", "targets: [alertmanager:9093]");
        assertThat(alertmanager).contains(
                "url_file: /run/insightops-secrets/alertmanager-webhook-url",
                "send_resolved: true", "max_alerts: 20");
        assertThat(alertmanager).doesNotContain("https://", "http_headers:");
        assertThat(alerts).contains(
                "alert: ProjectCollectionFailures",
                "alert: ProjectCollectionStale",
                "insightops_project_collection_failed",
                "insightops_project_collection_stale");
        assertThat(metrics).contains(
                "insightops.project.collection.failed",
                "insightops.project.collection.stale",
                "sync_interval_hours + 6");
    }

    @Test
    void stabilityGateAuditsAllTenProjectsAndHistoricalRuns() throws IOException {
        String script = read("scripts/production-stability-report.sh");

        assertThat(script).contains(
                "spring-projects', 'spring-ai",
                "langchain4j', 'langchain4j",
                "langgenius', 'dify",
                "alibaba', 'spring-ai-alibaba",
                "quarkiverse', 'quarkus-langchain4j",
                "modelcontextprotocol', 'java-sdk",
                "openai', 'openai-java",
                "anthropics', 'anthropic-sdk-java",
                "googleapis', 'java-genai",
                "ollama', 'ollama",
                "job.job_type='GITHUB_RELEASE_SYNC'",
                "minimum_success_percent",
                "project_count != 10",
                "GITHUB_TOKEN is required");
        assertThat(script).doesNotContain("set -x", "AUTH_BOOTSTRAP_PASSWORD");
    }

    @Test
    void encryptedBackupAndRecoveryDrillCannotTargetProductionVolumes() throws IOException {
        String bootstrap = read("scripts/ensure-prod-reliability-secrets.sh");
        String create = read("scripts/create-offsite-backup.sh");
        String restore = read("scripts/restore-offsite-drill.sh");
        String acceptance = read("scripts/stage3-production-acceptance.sh");
        String workflow = read(".github/workflows/stage3-production-reliability.yml");
        String githubToken = read("scripts/configure-prod-github-token.sh");

        assertThat(bootstrap).contains(
                "openssl rand -hex 24",
                "https://ntfy.sh/${topic}?template=alertmanager&firebase=no",
                "openssl rand -hex 32",
                "chmod 600 \"$SECRET_DIR/offsite-backup-passphrase\"");
        assertThat(create).contains(
                "aes-256-cbc", "-pbkdf2", "-iter 200000",
                "portable_manifest", "OFFSITE_PACKAGE=");
        assertThat(restore).contains(
                "--confirm-isolated-recovery",
                "Recovery package must be an existing file inside",
                "pgvector/pgvector:0.8.5-pg18",
                "insightops_restore",
                "select storage_key, sha256 from knowledge_upload",
                "ISOLATED_RECOVERY=PASS");
        assertThat(acceptance).contains(
                "InsightOpsStage3Canary",
                "json?poll=1&since=",
                "production-stability-report.sh\" 72 95",
                "create-offsite-backup.sh",
                "restore-offsite-drill.sh");
        assertThat(githubToken).contains(
                "must be supplied through standard input",
                "printf 'GITHUB_TOKEN=%s\\n' \"$github_token\"",
                "chmod 600 \"$temporary\"",
                "up -d --force-recreate worker",
                "Worker is healthy");
        assertThat(workflow).contains(
                "environment: production",
                "PRODUCTION_GITHUB_TOKEN: ${{ secrets.PRODUCTION_GITHUB_TOKEN }}",
                "printf '%s\\n' \"$PRODUCTION_GITHUB_TOKEN\" |",
                "configure-prod-github-token.sh --from-stdin",
                "actions/upload-artifact@v4",
                "retention-days: 30",
                "actions/download-artifact@v4",
                "recovery-imports/$ACCEPTANCE_MARKER");
        assertThat(workflow).doesNotContain(
                "OFFSITE_BACKUP_PASSPHRASE", "offsite-backup-passphrase", ".env.prod");
        assertThat(restore).doesNotContain(
                "infra/compose.prod.yml", "docker compose", "postgres-data",
                "knowledge-uploads", "down -v");
        assertThat(create).doesNotContain("set -x");
        assertThat(restore).doesNotContain("set -x");
        assertThat(acceptance).doesNotContain("set -x");
        assertThat(githubToken).doesNotContain("set -x");
        assertThat(githubToken).doesNotContain("awk -v", "replacement=$github_token");
    }

    private String read(String relative) throws IOException {
        return Files.readString(root().resolve(relative));
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
