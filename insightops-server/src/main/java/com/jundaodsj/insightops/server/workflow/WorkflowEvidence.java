package com.jundaodsj.insightops.server.workflow;

import com.jundaodsj.insightops.conversation.application.ChatCitation;
import java.util.*;
import java.util.regex.*;

final class WorkflowEvidence {
    private WorkflowEvidence() { }
    static void append(StringBuilder prompt, List<ChatCitation> combined, String evidence, List<ChatCitation> citations) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (ChatCitation citation : citations) {
            String prefix = citation.label().replaceAll("[0-9]+$", "");
            long count = combined.stream().filter(item -> item.label().replaceAll("[0-9]+$", "").equals(prefix)).count();
            String label = prefix + (count + 1);
            labels.put(citation.label(), label);
            combined.add(new ChatCitation(label, citation.title(), citation.url(), citation.project(), citation.heading(),
                    citation.sourceType(), citation.score(), citation.versionLabel(), citation.fetchedAt()));
        }
        Matcher matcher = Pattern.compile("\\[([A-Za-z]+[0-9]+)]").matcher(evidence == null ? "" : evidence);
        StringBuffer relabeled = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(relabeled,
                Matcher.quoteReplacement("[" + labels.getOrDefault(matcher.group(1), matcher.group(1)) + "]"));
        matcher.appendTail(relabeled);
        prompt.append(relabeled);
    }
}
