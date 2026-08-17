package com.jundaodsj.insightops.infrastructure.knowledge;

import com.jundaodsj.insightops.knowledge.application.KnowledgeStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Component
public class KnowledgeDocumentChunker {

    public List<KnowledgeStore.DocumentChunk> chunk(String content, int maxTokens, int overlapTokens) {
        int maxChars = Math.max(400, Math.max(100, maxTokens) * 4);
        int overlapChars = Math.min(maxChars / 3, Math.max(0, overlapTokens) * 4);
        List<Piece> pieces = pieces(normalize(content));
        List<KnowledgeStore.DocumentChunk> result = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        for (Piece piece : pieces) {
            if (piece.heading()) {
                if (!current.isEmpty()) {
                    add(result, heading, current.toString());
                    current.setLength(0);
                }
                heading = piece.text();
            }
            if (piece.text().length() > maxChars) {
                if (!current.isEmpty()) {
                    add(result, heading, current.toString());
                    current.setLength(0);
                }
                splitLarge(result, heading, piece.text(), maxChars, overlapChars);
                continue;
            }
            if (!current.isEmpty() && current.length() + piece.text().length() + 2 > maxChars) {
                String previous = current.toString();
                add(result, heading, previous);
                current.setLength(0);
                if (overlapChars > 0) current.append(tail(previous, overlapChars)).append("\n\n");
            }
            current.append(piece.text()).append("\n\n");
        }
        if (!current.isEmpty()) add(result, heading, current.toString());
        return List.copyOf(result);
    }

    private static List<Piece> pieces(String content) {
        List<Piece> pieces = new ArrayList<>();
        for (String block : content.split("\\n\\s*\\n")) {
            String value = block.trim();
            if (value.isEmpty()) continue;
            boolean heading = value.matches("^#{1,6}\\s+.+");
            pieces.add(new Piece(value, heading));
        }
        return pieces;
    }

    private static void splitLarge(List<KnowledgeStore.DocumentChunk> result, String heading,
                                   String value, int maxChars, int overlapChars) {
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + maxChars);
            if (end < value.length()) {
                int boundary = value.lastIndexOf('\n', end);
                if (boundary <= start + maxChars / 2) boundary = value.lastIndexOf(' ', end);
                if (boundary > start + maxChars / 2) end = boundary;
            }
            add(result, heading, value.substring(start, end));
            if (end == value.length()) break;
            start = Math.max(start + 1, end - overlapChars);
        }
    }

    private static void add(List<KnowledgeStore.DocumentChunk> result, String heading, String raw) {
        String value = raw.trim();
        if (value.length() < 40) return;
        result.add(new KnowledgeStore.DocumentChunk(result.size(), blankToNull(heading), value,
                sha256(value), value.length(), Math.max(1, (value.length() + 3) / 4)));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("[ ]{2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String tail(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int start = value.length() - maxChars;
        int boundary = value.indexOf(' ', start);
        return value.substring(boundary >= 0 ? boundary + 1 : start);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Piece(String text, boolean heading) { }
}
