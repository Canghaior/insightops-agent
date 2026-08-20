package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.project.application.P0TrackedProjectCatalog;
import com.jundaodsj.insightops.project.application.AdminProjectStore;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubRepositoryReleaseQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReleaseQuestionRouter {

    private final AdminProjectStore projectStore;

    public ReleaseQuestionRouter() {
        this.projectStore = null;
    }

    @Autowired
    public ReleaseQuestionRouter(AdminProjectStore projectStore) {
        this.projectStore = projectStore;
    }

    private static final Pattern TIME_WINDOW =
            Pattern.compile("(?:最近|近)\\s*(\\d{1,3})\\s*天", Pattern.CASE_INSENSITIVE);

    public Optional<GitHubReleaseQuery> route(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> projectIds = projects(normalized);
        if (projectIds.isEmpty() || !requiresReleaseEvidence(normalized)) {
            return Optional.empty();
        }
        return Optional.of(query(normalized, projectIds));
    }

    public Optional<GitHubReleaseQuery> routeWithProjectContext(
            String question,
            String previousUserQuestions) {
        String previousNormalized = previousUserQuestions.toLowerCase(Locale.ROOT);
        Optional<GitHubReleaseQuery> direct = route(question);
        if (direct.isPresent()) {
            GitHubReleaseQuery query = direct.orElseThrow();
            return Optional.of(withInheritedTimeWindow(
                    query,
                    question.toLowerCase(Locale.ROOT),
                    previousNormalized));
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        if (!projects(normalized).isEmpty() || !requiresReleaseEvidence(normalized)) {
            return Optional.empty();
        }
        List<String> inheritedProjects = projects(previousNormalized);
        return inheritedProjects.isEmpty()
                ? Optional.empty()
                : Optional.of(withInheritedTimeWindow(
                        query(normalized, inheritedProjects),
                        normalized,
                        previousNormalized));
    }

    public Optional<ResolvedReleaseQuery> routeWithProjectContext(
            UUID workspaceId, String question, String previousUserQuestions) {
        if (projectStore == null) return Optional.empty();
        List<AdminProjectStore.ManagedProject> available = projectStore.list(workspaceId).stream()
                .filter(AdminProjectStore.ManagedProject::enabled)
                .toList();
        String normalized = question.toLowerCase(Locale.ROOT);
        String previousNormalized = previousUserQuestions.toLowerCase(Locale.ROOT);
        List<AdminProjectStore.ManagedProject> matched = projects(normalized, available);
        if (!matched.isEmpty() && requiresReleaseEvidence(normalized)) {
            GitHubReleaseQuery query = query(normalized, matched.stream()
                    .map(project -> project.projectId().toString()).toList());
            return Optional.of(resolve(withInheritedTimeWindow(query, normalized, previousNormalized), matched));
        }
        if (!matched.isEmpty() || !requiresReleaseEvidence(normalized)) return Optional.empty();
        List<AdminProjectStore.ManagedProject> inherited = projects(previousNormalized, available);
        if (inherited.isEmpty()) return Optional.empty();
        GitHubReleaseQuery query = withInheritedTimeWindow(
                query(normalized, inherited.stream().map(project -> project.projectId().toString()).toList()),
                normalized, previousNormalized);
        return Optional.of(resolve(query, inherited));
    }

    private ResolvedReleaseQuery resolve(GitHubReleaseQuery query,
                                         List<AdminProjectStore.ManagedProject> projects) {
        List<GitHubRepositoryReleaseQuery> repositories = projects.stream()
                .map(project -> new GitHubRepositoryReleaseQuery(
                        project.projectId().toString(), project.repositoryName(),
                        project.repositoryOwner(), project.repositoryName(),
                        query.timeWindowDays(), query.maxReleasesPerProject(),
                        query.includePrereleases()))
                .toList();
        return new ResolvedReleaseQuery(query, repositories);
    }

    private List<AdminProjectStore.ManagedProject> projects(
            String question, List<AdminProjectStore.ManagedProject> available) {
        return available.stream().filter(project -> aliases(project).stream()
                        .anyMatch(alias -> question.contains(alias)))
                .limit(3)
                .toList();
    }

    private List<String> aliases(AdminProjectStore.ManagedProject project) {
        List<String> aliases = new ArrayList<>();
        aliases.add(project.repositoryName().toLowerCase(Locale.ROOT));
        aliases.add((project.repositoryOwner() + "/" + project.repositoryName()).toLowerCase(Locale.ROOT));
        aliases.add(project.repositoryName().replace('-', ' ').replace('_', ' ').toLowerCase(Locale.ROOT));
        aliases.addAll(project.chatAliases());
        return aliases.stream().filter(alias -> alias != null && alias.length() >= 2).distinct().toList();
    }

    private GitHubReleaseQuery withInheritedTimeWindow(
            GitHubReleaseQuery current,
            String currentQuestion,
            String previousQuestion) {
        if (current.timeWindowDays() != null
                || !containsAny(currentQuestion, "刚才", "上述", "这些", "根据", "更活跃", "频率")) {
            return current;
        }
        Integer previousWindow = timeWindowDays(previousQuestion);
        if (previousWindow == null) {
            return current;
        }
        return new GitHubReleaseQuery(
                current.projectIds(),
                previousWindow,
                30,
                current.includePrereleases());
    }

    private GitHubReleaseQuery query(String normalized, List<String> projectIds) {
        Integer timeWindowDays = timeWindowDays(normalized);
        if (timeWindowDays == null
                && containsAny(normalized, "最近", "近期", "目前")
                && !containsAny(normalized, "最新", "上一个", "前一个")) {
            timeWindowDays = 90;
        }
        int maxReleases = timeWindowDays != null
                ? 30
                : containsAny(normalized, "最新", "上一个", "前一个") ? 2 : 5;
        return new GitHubReleaseQuery(
                projectIds,
                timeWindowDays,
                maxReleases,
                normalized.contains("预发布") || normalized.contains(" rc"));
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

    public record ResolvedReleaseQuery(
            GitHubReleaseQuery evidenceQuery,
            List<GitHubRepositoryReleaseQuery> repositories) {
        public ResolvedReleaseQuery {
            repositories = List.copyOf(repositories);
        }
    }
}
