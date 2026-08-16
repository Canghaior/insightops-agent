package com.jundaodsj.insightops.tool.application.github;

public interface GitHubReleaseGateway {

    GitHubReleaseResult listReleases(GitHubReleaseQuery query);
}
