package com.jundaodsj.insightops.server.chat;

import com.jundaodsj.insightops.tool.application.github.GitHubRelease;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseQuery;
import com.jundaodsj.insightops.tool.application.github.GitHubReleaseResult;
import org.springframework.stereotype.Component;

@Component
public class GitHubReleaseEvidenceFormatter {

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
                    .append(release.notesExcerpt().isBlank() ? "（正文为空）" : release.notesExcerpt())
                    .append('\n');
        }
        return evidence.toString();
    }
}
