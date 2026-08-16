package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReleaseQuestionRouter {

    private static final Pattern TIME_WINDOW =
            Pattern.compile("(?:最近|近)\\s*(\\d{1,3})\\s*天", Pattern.CASE_INSENSITIVE);

    public Optional<GitHubReleaseQuery> route(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> projectIds = projects(normalized);
        if (projectIds.isEmpty() || !requiresReleaseEvidence(normalized)) {
            return Optional.empty();
        }
        Integer timeWindowDays = timeWindowDays(question);
        int maxReleases = timeWindowDays != null
                ? 10
                : containsAny(normalized, "最新", "上一个", "前一个") ? 2 : 5;
        return Optional.of(new GitHubReleaseQuery(
                projectIds,
                timeWindowDays,
                maxReleases,
                normalized.contains("预发布") || normalized.contains(" rc")));
    }

    private List<String> projects(String question) {
        List<String> projects = new ArrayList<>();
        for (var project : P0TrackedProjectCatalog.all()) {
            if (project.aliases().stream().anyMatch(question::contains)) {
                projects.add(project.id());
            }
        }
        return projects;
    }

    private boolean requiresReleaseEvidence(String question) {
        if (containsAny(question,
                "release", "版本", "发布", "升级", "breaking", "更新日志")) {
            return true;
        }
        boolean recent = containsAny(question, "最近", "近期", "最新", "上一个", "前一个", "目前");
        boolean change = containsAny(question,
                "变化", "功能", "方向", "修复", "安全", "稳定", "频繁", "最好");
        return recent && change;
    }

    private Integer timeWindowDays(String question) {
        Matcher matcher = TIME_WINDOW.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        int days = Integer.parseInt(matcher.group(1));
        return Math.max(1, Math.min(days, 365));
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
