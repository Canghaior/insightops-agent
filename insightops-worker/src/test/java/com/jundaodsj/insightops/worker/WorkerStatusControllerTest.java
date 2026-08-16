package com.jundaodsj.insightops.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerStatusControllerTest {

    @Test
    void shouldReportWorkerUp() {
        assertThat(new WorkerStatusController().status().status()).isEqualTo("UP");
    }
}
