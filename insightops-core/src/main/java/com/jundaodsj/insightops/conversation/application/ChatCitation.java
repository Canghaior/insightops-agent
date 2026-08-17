package com.jundaodsj.insightops.conversation.application;

public record ChatCitation(
        String label,
        String title,
        String url,
        String project,
        String heading,
        String sourceType,
        Double score) {
}
