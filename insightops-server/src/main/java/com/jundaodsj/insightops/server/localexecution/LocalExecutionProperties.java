package com.jundaodsj.insightops.server.localexecution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

@ConfigurationProperties("insightops.local-execution")
public record LocalExecutionProperties(
        Path workspacesRoot,
        Path dataRoot,
        Path mavenRepository,
        String mavenImage,
        Duration buildTimeout,
        UUID legacyWorkspaceId) {

    public LocalExecutionProperties {
        mavenImage = mavenImage == null ? "maven:3.9.11-eclipse-temurin-21" : mavenImage;
        if (!mavenImage.matches("[A-Za-z0-9_./:@-]{1,240}") || mavenImage.startsWith("-")) {
            throw new IllegalArgumentException("本地执行的 Maven 镜像名称无效。");
        }
        buildTimeout = buildTimeout == null ? Duration.ofMinutes(3) : buildTimeout;
        if (buildTimeout.compareTo(Duration.ofSeconds(5)) < 0 || buildTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("本地构建超时须介于 5 秒和 15 分钟之间。");
        }
    }
}
