package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBuildPlanTest {
    @Test
    void buildsOnlyNamedTestCommandsAndRejectsMavenFlagsAsClasses() {
        var plan = new LocalBuildPlan(LocalBuildPlan.Mode.SELECTED_CLASSES, List.of("sample.ExampleTest", "OtherTest"));
        assertThat(plan.command()).endsWith("-Dtest=sample.ExampleTest,OtherTest", "test");
        assertThatThrownBy(() -> new LocalBuildPlan(LocalBuildPlan.Mode.SELECTED_CLASSES, List.of("-DskipTests")))
                .hasMessageContaining("类名");
        assertThatThrownBy(() -> new LocalBuildPlan(LocalBuildPlan.Mode.SELECTED_CLASSES, List.of()))
                .hasMessageContaining("1 至 12");
    }

    @Test
    void reexportsPrePlanInputsWithOriginalJsonShape() throws Exception {
        var json = new ObjectMapper();
        String original = """
                {"requestId":"038bd0ae-690a-49a3-bd75-940cf683eefd","workspace":"rule","sourceRevision":"sha",
                "modelChoice":"model","instruction":"change","writeScopes":["src/main/"]}
                """;
        var read = json.readValue(original, LocalRunStore.Command.class);
        assertThat(read.effectivePlan()).isEqualTo(LocalBuildPlan.ALL);
        assertThat(json.valueToTree(read).equals(json.readTree(original))).isTrue();
    }
}
