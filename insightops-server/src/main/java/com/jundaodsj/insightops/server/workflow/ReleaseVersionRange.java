package com.jundaodsj.insightops.server.workflow;

import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit numeric ordering, including Spring's M/RC and common SemVer prereleases. */
public final class ReleaseVersionRange {
    private static final Pattern VERSION = Pattern.compile(
            "(?i)^v?(\\d{1,8})\\.(\\d{1,8})(?:\\.(\\d{1,8}))?(?:[-.]?(SNAPSHOT|alpha|beta|M|RC)[.-]?(\\d{1,8})?)?(?:\\+[-.a-zA-Z0-9]+)?$");
    private ReleaseVersionRange() { }
    public static Version parse(String raw) {
        var match = VERSION.matcher(raw == null ? "" : raw.strip());
        if (!match.matches()) throw new IllegalArgumentException("版本格式暂不支持，请使用如 1.0.0、v1.1.0 或 1.1.0-RC1。");
        int stage = match.group(4) == null ? 5 : switch (match.group(4).toLowerCase(Locale.ROOT)) {
            case "snapshot" -> 0; case "alpha" -> 1; case "beta" -> 2; case "m" -> 3; default -> 4;
        };
        return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)), stage,
                match.group(5) == null ? 0 : Integer.parseInt(match.group(5)));
    }
    public static Selection select(GitHubReleaseResult fetched, String current, String target, int limit) {
        Version from = parse(current), to = parse(target);
        if (from.compareTo(to) >= 0) throw new IllegalArgumentException("目标版本必须高于当前版本。");
        boolean hasCurrent = false, hasTarget = false, unknown = false;
        var targetPublishedAt = fetched.releases().stream().filter(item -> {
            try { return parse(item.tagName()).compareTo(to) == 0; }
            catch (IllegalArgumentException exception) { return false; }
        }).map(GitHubRelease::publishedAt).filter(Objects::nonNull).findFirst().orElse(null);
        int laterMaintenance = 0;
        List<GitHubRelease> selected = new ArrayList<>();
        for (var release : fetched.releases()) {
            try {
                Version version = parse(release.tagName());
                hasCurrent |= version.compareTo(from) == 0;
                hasTarget |= version.compareTo(to) == 0;
                if (version.compareTo(from) > 0 && version.compareTo(to) <= 0) {
                    if (targetPublishedAt != null && release.publishedAt() != null
                            && release.publishedAt().isAfter(targetPublishedAt)) laterMaintenance++;
                    else selected.add(release);
                }
            } catch (IllegalArgumentException exception) { unknown = true; }
        }
        selected.sort(Comparator.comparing(item -> parse(item.tagName())));
        List<String> warnings = new ArrayList<>();
        if (!hasCurrent) warnings.add("未在已扫描 Release 中找到当前版本 " + current + "，范围起点尚未核实。");
        if (!hasTarget) warnings.add("未在已扫描 Release 中找到目标版本 " + target + "，目标版本尚未核实。");
        if (fetched.truncated()) warnings.add("Release 扫描达到上限，可能缺少更早发布的版本。");
        if (unknown) warnings.add("部分 Release 标签无法解析，未纳入版本范围，需人工核对。");
        if (selected.size() > limit) warnings.add("范围内 Release 超过 " + limit + " 条，仅返回靠近目标版本的记录。");
        if (selected.isEmpty()) warnings.add("指定版本范围内没有可用 Release 证据。");
        boolean complete = warnings.isEmpty();
        boolean truncated = fetched.truncated() || selected.size() > limit;
        if (laterMaintenance > 0) warnings.add("已排除 " + laterMaintenance + " 条晚于目标 Release 发布的维护版本；这些变更不能视为已包含在目标版本中。");
        if (selected.stream().anyMatch(item -> item.notesExcerpt() == null || item.notesExcerpt().isBlank()
                || item.notesExcerpt().endsWith("…"))) warnings.add("部分 Release 正文为空或已截断，详细变更需返回原文核查。");
        return new Selection(selected.size() > limit ? selected.subList(selected.size() - limit, selected.size()) : selected,
                List.copyOf(warnings), complete, truncated, hasCurrent, hasTarget);
    }
    public record Selection(List<GitHubRelease> releases, List<String> warnings, boolean complete, boolean truncated,
                            boolean currentVersionFound, boolean targetVersionFound) { }
    public record Version(int major, int minor, int patch, int stage, int revision) implements Comparable<Version> {
        @Override public int compareTo(Version other) {
            int[] left = {major, minor, patch, stage, revision};
            int[] right = {other.major, other.minor, other.patch, other.stage, other.revision};
            for (int i = 0; i < left.length; i++) if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
            return 0;
        }
    }
}
