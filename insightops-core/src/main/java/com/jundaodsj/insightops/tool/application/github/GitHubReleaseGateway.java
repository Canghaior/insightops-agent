package com.jundaodsj.insightops.tool.application.github;

public interface GitHubReleaseGateway {

    GitHubReleaseResult listReleases(GitHubReleaseQuery query);

    default GitHubReleaseResult listRepositoryReleases(GitHubRepositoryReleaseQuery query) {
        throw new UnsupportedOperationException("Dynamic GitHub repository releases are not supported");
    }
}
