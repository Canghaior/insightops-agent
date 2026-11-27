package com.jundaodsj.insightops.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore.AccountRecord;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import com.jundaodsj.insightops.server.report.EngineeringTaskService.PatchFile;
import com.jundaodsj.insightops.server.report.EngineeringTaskService.ResultCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EngineeringTaskControllerTest {
    private final EngineeringTaskService tasks = mock(EngineeringTaskService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EngineeringTaskController(tasks,
            new ObjectMapper().findAndRegisterModules())).build();
    private final AccountRecord account = new AccountRecord(UUID.randomUUID(), "engineer", "Engineer",
            UUID.randomUUID(),"Workspace","USER","OWNER","",false);
    private final UUID report = UUID.randomUUID(), version = UUID.randomUUID();
    private final String url = "/api/v1/reports/" + report + "/tasks";

    @Test void applicationControlsReportOwnershipAndReadonlySourceFields() throws Exception {
        for (String forged : new String[] {"""
                {"requestId":"%s","workspaceId":"%s","fields":{}}
                ""","""
                {"requestId":"%s","fields":{"engineeringBaselineId":"%s"}}
                ""","""
                {"requestId":"%s","fields":{"author":"%s"}}
                """}) {
            mvc.perform(post(url).requestAttr(CurrentAccount.ATTRIBUTE, account).contentType(MediaType.APPLICATION_JSON)
                    .content(forged.formatted(UUID.randomUUID(), UUID.randomUUID()))).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart(url+"/versions/{id}/results",version).requestAttr(CurrentAccount.ATTRIBUTE,account)
                .param("command","{\"recordedBy\":\"someone else\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }

    @Test void jsonKeepsChineseTextAndUsesOnlyTheAuthenticatedActorAndReport() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post(url).requestAttr(CurrentAccount.ATTRIBUTE, account).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","fields":{"question":"核对定向回归","origin":"MODEL","readiness":"READY"}}
                        """.formatted(id).getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
        var cmd = ArgumentCaptor.forClass(EngineeringTaskService.TaskCommand.class);
        verify(tasks).save(eq(account.actor()),eq(report),cmd.capture());
        assertThat(cmd.getValue().requestId()).isEqualTo(id);
        assertThat(cmd.getValue().fields().question()).isEqualTo("核对定向回归");
    }

    @Test void realMultipartEncodingBindsTheActorAndOpaquePatchBytes() throws Exception {
        UUID requestId = UUID.randomUUID();
        byte[] patch = "--- a/f\n+++ b/f\n".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart(url+"/versions/{id}/results",version).requestAttr(CurrentAccount.ATTRIBUTE,account)
                        .file(new MockMultipartFile("patch","file.diff","text/plain",patch))
                        .param("command","{\"requestId\":\""+requestId+"\",\"executedAt\":\"2026-11-20T01:02:03Z\",\"status\":\"EXECUTED\"}"))
                .andExpect(status().isOk());
        var result = ArgumentCaptor.forClass(ResultCommand.class);
        var file = ArgumentCaptor.forClass(PatchFile.class);
        verify(tasks).record(eq(account.actor()),eq(report),eq(version),result.capture(),file.capture());
        assertThat(result.getValue().requestId()).isEqualTo(requestId);
        assertThat(result.getValue().executedAt().toString()).isEqualTo("2026-11-20T01:02:03Z");
        assertThat(file.getValue().bytes()).isEqualTo(patch);
    }

    @Test void malformedCommandsNumbersForEnumsAndOversizedPatchesCannotReachService() throws Exception {
        String result = url+"/versions/{id}/results";
        for (String invalid : new String[]{"{","{\"status\":1}","{} {}"}) {
            mvc.perform(multipart(result,version).requestAttr(CurrentAccount.ATTRIBUTE,account).param("command",invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(multipart(result,version).requestAttr(CurrentAccount.ATTRIBUTE,account)
                .file(new MockMultipartFile("patch","big.diff","text/plain",new byte[EngineeringTaskService.MAX_PATCH_BYTES+1]))
                .param("command","{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }
}
