package com.jundaodsj.insightops.server.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectEventEvidenceServiceTest {
    @Test void detectsIssuePullRequestAndSecurityIntents(){
        assertThat(ProjectEventEvidenceService.eventTypes("最近有哪些 Issue 和 PR？"))
                .containsExactly("GITHUB_ISSUE","GITHUB_PULL_REQUEST");
        assertThat(ProjectEventEvidenceService.eventTypes("有没有 CVE 安全漏洞？"))
                .containsExactly("GITHUB_SECURITY_ADVISORY");
    }
    @Test void doesNotQueryEventsForOrdinaryDocumentationQuestion(){
        assertThat(ProjectEventEvidenceService.eventTypes("Spring AI 如何配置向量数据库？")).isEmpty();
    }
}
