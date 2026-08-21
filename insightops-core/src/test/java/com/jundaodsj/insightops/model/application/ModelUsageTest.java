package com.jundaodsj.insightops.model.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelUsageTest {

    @Test
    void shouldAddKnownAndUnknownUsageFields() {
        ModelUsage result = new ModelUsage(10, null, 15, 3L, null)
                .plus(new ModelUsage(7, 4, 11, null, 2L));

        assertThat(result).isEqualTo(new ModelUsage(17, 4, 26, 3L, 2L));
        assertThat(result.plus(null)).isEqualTo(result);
    }

    @Test
    void shouldRejectOverflowInsteadOfPersistingCorruptedUsage() {
        ModelUsage maximum = new ModelUsage(Integer.MAX_VALUE, 0, 0, null, null);

        assertThatThrownBy(() -> maximum.plus(new ModelUsage(1, 0, 0, null, null)))
                .isInstanceOf(ArithmeticException.class);
    }
}
