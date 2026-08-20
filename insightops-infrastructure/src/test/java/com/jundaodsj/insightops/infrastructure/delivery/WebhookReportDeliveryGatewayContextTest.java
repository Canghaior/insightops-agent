package com.jundaodsj.insightops.infrastructure.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookReportDeliveryGatewayContextTest {

    @Test
    void selectsTheProductionConstructorInASpringContext() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(WebhookReportDeliveryGateway.class);
            context.refresh();

            assertThat(context.getBean(WebhookReportDeliveryGateway.class)).isNotNull();
        }
    }
}
