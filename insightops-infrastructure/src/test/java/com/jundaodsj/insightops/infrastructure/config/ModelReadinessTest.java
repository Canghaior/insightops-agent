package com.jundaodsj.insightops.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelReadinessTest {

    @Test
    void shouldRequireBothFeatureFlagAndApiKey() {
        assertThat(new ModelReadiness(true, true, "deepseek", "deepseek-v4-flash").ready()).isTrue();
        assertThat(new ModelReadiness(true, false, "deepseek", "deepseek-v4-flash").ready()).isFalse();
        assertThat(new ModelReadiness(false, true, "deepseek", "deepseek-v4-flash").ready()).isFalse();
    }
}
