package com.jundaodsj.insightops.server.chat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableChatStreamServiceTest {

    @Test
    void exposesDurableRunIdentityAndDisablesProxyBuffering() {
        UUID runId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);
        var emitter = new DurableChatStreamService.RunAwareSseEmitter(runId, 15_000L);

        emitter.extendResponse(response);

        assertEquals(runId.toString(), headers.getFirst(
                DurableChatStreamService.RUN_ID_HEADER));
        assertEquals("no", headers.getFirst("X-Accel-Buffering"));
        assertTrue(headers.getCacheControl().contains("no-cache"));
        assertTrue(headers.getCacheControl().contains("no-store"));
        assertTrue(headers.getCacheControl().contains("no-transform"));
    }
}
