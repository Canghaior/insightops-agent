package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.infrastructure.report.TemporaryReportArchive;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.VerificationHandoffService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationHandoffControllerTest {
    private final VerificationHandoffService service = mock(VerificationHandoffService.class);
    private final VerificationHandoffController controller = new VerificationHandoffController(service);
    private final UUID report = UUID.randomUUID(), version = UUID.randomUUID();
    private final AccountWorkspaceStore.AccountRecord account = new AccountWorkspaceStore.AccountRecord(
            UUID.randomUUID(), "test", "工程人员", UUID.randomUUID(), "隔离工作区", "USER", "OWNER", "", false);

    @Test void boundsSimultaneousArchivesAndAcceptsAnotherRequestAfterDownload() throws Exception {
        var preparing = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(service.prepare(account.actor(), report, version)).thenAnswer(invocation -> {
            preparing.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new IOException("test timeout");
            return archive();
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(this::download);
            var second = workers.submit(this::download);
            try {
                assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(this::download).isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
                verify(service, times(2)).prepare(account.actor(), report, version);
            } finally {
                finish.countDown();
            }
            for (var result : java.util.List.of(first, second)) {
                var response = result.get(5, TimeUnit.SECONDS);
                assertThat(response.getContentAsByteArray()).hasSize((int)response.getContentLengthLong());
                assertThat(response.getContentType()).isEqualTo("application/zip");
            }
        }
        assertThat(download().getHeader("Content-Disposition")).contains(version.toString());
    }

    @Test void disconnectedClientReleasesTheArchiveAndTheDownloadSlot() throws Exception {
        try (var prepared = archive()) {
            when(service.prepare(account.actor(),report,version)).thenReturn(prepared).thenAnswer(ignored -> archive());
            var disconnected = mock(HttpServletResponse.class);
            when(disconnected.getOutputStream()).thenThrow(new IOException("client disconnected"));
            assertThatThrownBy(() -> controller.download(report, version, request(), disconnected))
                    .isInstanceOf(IOException.class).hasMessage("client disconnected");
            assertThatThrownBy(prepared::size).isInstanceOf(ClosedChannelException.class);
            assertThat(download().getContentLengthLong()).isPositive();
        }
    }

    private MockHttpServletResponse download() throws IOException {
        var response = new MockHttpServletResponse();
        controller.download(report, version, request(), response);
        return response;
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setAttribute(CurrentAccount.ATTRIBUTE, account);
        return request;
    }

    private static TemporaryReportArchive archive() throws IOException {
        return TemporaryReportArchive.prepare(zip -> {
            zip.putNextEntry(new ZipEntry("sample.txt"));
            zip.write(42);
            zip.closeEntry();
        });
    }
}
