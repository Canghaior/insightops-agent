package com.jundaodsj.insightops.server.workflow;
import com.jundaodsj.insightops.conversation.application.ChatCitation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowEvidenceTest {
    @Test void relabelsEachNodeOnceWithoutCascadingOrLosingSourceMetadata() {
        var combined = new ArrayList<ChatCitation>();
        var prompt = new StringBuilder();
        WorkflowEvidence.append(prompt, combined, "first [S1]\n", List.of(citation("S1", "first")));
        WorkflowEvidence.append(prompt, combined, "second [S1] third [S2]\n", List.of(citation("S1", "second"), citation("S2", "third")));
        assertThat(prompt).hasToString("first [S1]\nsecond [S2] third [S3]\n");
        assertThat(combined).extracting(ChatCitation::label).containsExactly("S1", "S2", "S3");
        assertThat(combined.get(1).url()).isEqualTo("second");
    }
    private ChatCitation citation(String label, String url) { return new ChatCitation(label, url, url, "spring-ai", "heading", "OFFICIAL_DOCUMENT", 1.0); }
}
