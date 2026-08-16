package com.jundaodsj.insightops.tool.application.github;

public final class GitHubToolException extends RuntimeException {

    private final GitHubToolErrorCode code;

    public GitHubToolException(GitHubToolErrorCode code, Throwable cause) {
        super("GitHub Release tool failed: " + code, cause);
        this.code = code;
    }

    public GitHubToolErrorCode code() {
        return code;
    }
}
