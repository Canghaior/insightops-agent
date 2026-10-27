package com.jundaodsj.insightops.server.workflow;
import com.jundaodsj.insightops.tool.application.github.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReleaseVersionRangeTest {
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    @Test void ordersNumericallyAndExcludesCurrentButIncludesTarget() {
        var result = ReleaseVersionRange.select(new GitHubReleaseResult(
                List.of(release("v1.10.0"), release("v1.2.0"), release("v1.1.0"), release("v1.2.0-RC1")), Instant.now(), false),
                "1.1.0", "1.2.0", 30);
        assertThat(result.releases()).extracting(GitHubRelease::tagName).containsExactly("v1.2.0-RC1", "v1.2.0");
        assertThat(result.complete()).isTrue();
        assertThat(result.currentVersionFound()).isTrue();
        assertThat(result.targetVersionFound()).isTrue();
    }
    @Test void neverTreatsMissingBoundariesOrTruncationAsComplete() {
        var result = ReleaseVersionRange.select(new GitHubReleaseResult(List.of(release("1.2.0")), Instant.now(), true), "1.0.0", "1.2.0", 30);
        assertThat(result.complete()).isFalse();
        assertThat(result.currentVersionFound()).isFalse();
        assertThat(result.targetVersionFound()).isTrue();
        assertThat(result.warnings()).anyMatch(item -> item.contains("当前版本")).anyMatch(item -> item.contains("上限"));
    }
    @Test void rejectsDowngradesAndUnknownVersionFormats() {
        assertThatThrownBy(() -> ReleaseVersionRange.parse("latest")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ReleaseVersionRange.parse("1.0.0-M2")).isLessThan(ReleaseVersionRange.parse("1.0.0-RC1"));
        assertThatThrownBy(() -> ReleaseVersionRange.select(new GitHubReleaseResult(List.of(), Instant.now(), false), "2.0.0", "1.0.0", 30))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingBoundaryDoesNotClaimTheResultHitTheCountLimit() {
        var result = ReleaseVersionRange.select(new GitHubReleaseResult(List.of(release("1.1.0")), NOW, false), "1.0.0", "1.1.0", 30);
        assertThat(result.complete()).isFalse();
        assertThat(result.truncated()).isFalse();
    }
    @Test void excludesMaintenanceReleasesPublishedAfterTheTarget() {
        var later = new GitHubRelease("project", "Spring AI", "1.0.9", "1.0.9", NOW.plusSeconds(60),
                "https://github.com/spring-projects/spring-ai/releases/tag/1.0.9", false, "later changes");
        var result = ReleaseVersionRange.select(new GitHubReleaseResult(List.of(release("1.0.0"), release("1.1.0"), later), NOW, false), "1.0.0", "1.1.0", 30);
        assertThat(result.releases()).extracting(GitHubRelease::tagName).containsExactly("1.1.0");
        assertThat(result.warnings()).anyMatch(item -> item.contains("晚于目标"));
        assertThat(result.complete()).isTrue();
    }
    private GitHubRelease release(String version) {
        return new GitHubRelease("project", "Spring AI", version, version, NOW, "https://github.com/spring-projects/spring-ai/releases/tag/" + version, false, "changes");
    }
}
