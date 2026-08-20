package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReportDeliveryControllerTest {

    @Test
    void rejectsNonPublicWebhookUrlsBeforeCallingTheStore() {
        ReportDeliveryStore store = mock(ReportDeliveryStore.class);
        ReportDeliveryController controller = new ReportDeliveryController(store);
        var body = new ReportDeliveryController.ChannelRequest(
                "Unsafe channel", "http://127.0.0.1:8080/internal", true);

        assertThatThrownBy(() -> controller.createChannel(body, new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("public HTTPS endpoint");
        assertThatThrownBy(() -> controller.updateChannel(
                UUID.randomUUID(), body, new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("public HTTPS endpoint");
        verifyNoInteractions(store);
    }
}
