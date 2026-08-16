package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class GitHubReleaseEvidenceFormatter {

    private static final Pattern MARKDOWN_LINK = Pattern.compile(
            "\\[([^\\]]+)]\\((?i:https?://[^)]+)\\)");
    private static final Pattern BARE_HTTP_URL = Pattern.compile("(?i)https?://\\S+");

    public String format(GitHubReleaseQuery query, GitHubReleaseResult result) {
        StringBuilder evidence = new StringBuilder("""

                以下是 github_release_list 工具刚刚从 GitHub 官方 Releases API 获取的受控证据。
                只能依据这些证据陈述版本、日期和变化；每个关键事实都要附对应的官方 Release URL。
                Release 正文没有明确披露的内容必须说明“证据未明确披露”，不得自行补充。
                """);
        evidence.append("查询项目：").append(String.join(", ", query.projectIds())).append('\n');
        evidence.append("时间窗口：")
                .append(query.timeWindowDays() == null ? "最近可用版本" : "最近 " + query.timeWindowDays() + " 天")
                .append('\n');
        evidence.append("抓取时间：").append(result.fetchedAt()).append('\n');
        evidence.append("每项目证据条数：");
        for (String projectId : query.projectIds()) {
            long count = result.releases().stream()
                    .filter(release -> projectId.equals(release.projectId()))
                    .count();
            evidence.append(projectId).append('=').append(count).append(' ');
        }
        evidence.append("（回答中的数量必须使用这里的计数）\n");
        if (result.truncated()) {
            evidence.append("证据完整性：结果达到单项目上限，后续 Release 未进入本次证据；")
                    .append("不得声称这是时间窗口内的完整列表或据此计算精确发布频率。\n");
        }
        if (result.releases().isEmpty()) {
            evidence.append("查询范围内没有找到符合口径的已发布 Release。\n");
            return evidence.toString();
        }
        for (GitHubRelease release : result.releases()) {
            evidence.append("\n---\n")
                    .append("项目：").append(release.projectName()).append('\n')
                    .append("版本：").append(release.tagName()).append('\n')
                    .append("名称：").append(release.releaseName()).append('\n')
                    .append("发布日期：").append(release.publishedAt()).append('\n')
                    .append("预发布：").append(release.prerelease()).append('\n')
                    .append("官方 URL：").append(release.url()).append('\n')
                    .append("Release 正文摘录：\n")
                    .append(release.notesExcerpt().isBlank()
                            ? "（正文为空）"
                            : untrustedNotesWithoutLinks(release.notesExcerpt()))
                    .append('\n');
        }
        return evidence.toString();
    }

    private static String untrustedNotesWithoutLinks(String notes) {
        String withoutMarkdownTargets = MARKDOWN_LINK.matcher(notes)
                .replaceAll("$1 [external link omitted]");
        return BARE_HTTP_URL.matcher(withoutMarkdownTargets)
                .replaceAll("[external link omitted]");
    }
}
