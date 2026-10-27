package com.jundaodsj.insightops.conversation.application;

public record ChatCitation(
        String label,
        String title,
        String url,
        String project,
        String heading,
        String sourceType,
        Double score,
        String versionLabel,
        java.time.Instant fetchedAt) {
    public ChatCitation(String label, String title, String url, String project, String heading,
                        String sourceType, Double score) {
        this(label, title, url, project, heading, sourceType, score, null, null);
    }
}
