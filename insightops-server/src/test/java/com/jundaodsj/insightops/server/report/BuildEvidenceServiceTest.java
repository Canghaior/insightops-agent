package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.BuildEvidenceStore;
import com.jundaodsj.insightops.report.application.ReportReviewStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildEvidenceServiceTest {
    private final ReportReviewStore reports = mock(ReportReviewStore.class);
    private final BuildEvidenceStore store = mock(BuildEvidenceStore.class);
    private final BuildEvidenceService service = new BuildEvidenceService(reports, store, new ObjectMapper());
    private final ActorContext actor = new ActorContext(UUID.randomUUID(), UUID.randomUUID());
    private final UUID report = UUID.randomUUID(), system = UUID.randomUUID(), request = UUID.randomUUID();

    @BeforeEach void owner() {
        when(reports.context(actor, report, false)).thenReturn(Optional.of(
                new ReportReviewStore.Context(report, UUID.randomUUID(), system, 1, null, null, null)));
        when(store.insert(eq(actor), eq(request), anyString(), any(), anyList())).thenReturn(true);
    }

    private String declaration(String extras) {
        return "{\"requestId\":\"" + request + "\",\"role\":\"BASELINE\"" + extras + "}";
    }

    private static MultipartFile data(String kind, String name, String text) {
        return new MockMultipartFile(kind, name, "application/octet-stream", text.getBytes(StandardCharsets.UTF_8));
    }

    private static String xml(String name, String dir) {
        return "<testsuite name='" + name + "' tests='1' failures='0' errors='0' skipped='0'>"
                + "<properties><property name='basedir' value='" + dir + "'/></properties>"
                + "<testcase classname='" + name + "' name='works[parameter=1]'/></testsuite>";
    }

    private static Map<String,List<MultipartFile>> sources() {
        var parts = new HashMap<String,List<MultipartFile>>();
        parts.put("xmlFiles", List.of(data("xmlFiles", "TEST-A.xml", xml("pkg.A","/a"))));
        return parts;
    }

    @Test void supportsTypicalCommandFieldAliasesWithoutChangingTheOriginal() throws Exception {
        var parts = sources();
        var input = data("commandRecord", "command.json", """
                {"command":["mvn","test"],"exitCode":0,
                 "startedAt":"2026-11-24T09:00:00+08:00","endedAt":"2026-11-24T09:01:00+08:00",
                 "start":"2026-11-24T01:00:00Z"}
                """);
        parts.put("commandRecord", List.of(input));
        var batch = service.importBatch(actor, report, declaration(""), parts);
        var command = batch.commandRecord();
        assertThat(command.exitCode()).isZero();
        assertThat(command.startedAt()).isEqualTo("2026-11-24T01:00:00Z");
        assertThat(command.endedAt()).isEqualTo("2026-11-24T09:01:00+08:00");
        String originalSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes()));
        assertThat(batch.files()).filteredOn(file -> file.kind() == FileKind.COMMAND_RECORD)
                .singleElement().satisfies(file -> assertThat(file.sha256()).isEqualTo(originalSha));
    }

    @Test void contradictoryCommandAliasesCannotBeSaved() {
        var parts = sources();
        parts.put("commandRecord", List.of(data("commandRecord", "command.json",
                "{\"exit\":0,\"exitCode\":1}")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor, report, declaration(""), parts))
                .withMessageContaining("exit 和 exitCode 的值不一致");
        parts.put("commandRecord", List.of(data("commandRecord", "command.json", """
                {"end":"2026-11-24T01:01:00Z","endedAt":"2026-11-24T09:02:00+08:00"}
                """)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor, report, declaration(""), parts))
                .withMessageContaining("end 和 endedAt 的时间不一致");
        verify(store, never()).insert(any(), any(), any(), any(), any());
    }

    @Test void storesOriginalBytesWithProvenanceAndKeepsConflictingClaimsSeparate() throws Exception {
        var parts = sources();
        parts.put("mavenLog", List.of(data("mavenLog","maven.log",
                "[INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0\n[INFO] BUILD FAILURE\n")));
        parts.put("commandRecord", List.of(data("commandRecord","build-1.json", """
                {"command":["mvnw","-Dtest=!MySqlTests,!PostgresTests","package"],"exit":1,
                 "start":"2026-11-20T20:00:00Z","end":"2026-11-20T20:02:00Z","totals":{"tests":99}}
                """)));
        parts.put("exclusionManifest",List.of(data("exclusionManifest","sources.json","""
                {"excludedMethods":[{"className":"MySqlTests","methodName":"owner","source":"src/MySqlTests.java:61"},
                                    {"className":"PostgresTests","methodName":"owner","source":"src/PostgresTests.java:82"}]}
                """)));
        var batch = service.importBatch(actor, report, declaration(",\"label\":\"abc\",\"executedBy\":\"外部工程师\",\"exitCode\":0"),parts);
        assertThat(batch.summary().counts()).isEqualTo(new Counts(1,0,0,0));
        assertThat(batch.metadata().exitCode()).isZero();
        assertThat(batch.metadata().executedBy()).isEqualTo("外部工程师");
        assertThat(batch.commandRecord().exitCode()).isEqualTo(1);
        assertThat(batch.commandRecord().startedAt()).isEqualTo("2026-11-20T20:00:00Z");
        assertThat(batch.commandRecord().excludedClasses()).containsExactly("MySqlTests","PostgresTests");
        assertThat(batch.excludedMethods()).hasSize(2);
        assertThat(batch.log()).isEqualTo(new LogFacts("FAILURE",new Counts(1,1,0,0)));
        assertThat(batch.warnings()).anyMatch(w -> w.contains("日志最终测试计数")).anyMatch(w -> w.contains("用户填写不一致"));
        var original = batch.files().getFirst();
        assertThat(original.kind()).isEqualTo(FileKind.SUREFIRE_XML);
        assertThat(original.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parts.get("xmlFiles").getFirst().getBytes())));
        assertThat(batch.cases().getFirst().fileId()).isEqualTo(original.id());
    }

    @Test void missingContextAndWildcardOrUnevidencedExclusionsStayUnknown() {
        var minimal = service.importBatch(actor,report,declaration(""),sources());
        assertThat(minimal.metadata().label()).isNull();
        assertThat(minimal.metadata().executedAt()).isNull();
        assertThat(minimal.commandRecord().exitCode()).isNull();
        assertThat(minimal.log()).isEqualTo(new LogFacts(null,null));
        assertThat(minimal.warnings()).anyMatch(w -> w.contains("未取得退出码"));
        var parts = sources();
        parts.put("commandRecord",List.of(data("commandRecord","cmd.json","{\"command\":[\"mvn\",\"-Dtest=!MySqlTests\",\"test\"]}")));
        var absent = service.importBatch(actor, report, declaration(""), parts);
        assertThat(absent.commandRecord().excludedClasses()).containsExactly("MySqlTests");
        assertThat(absent.excludedMethods()).isEmpty();
        assertThat(absent.warnings()).anyMatch(w -> w.contains("方法数量未知"));
        parts.put("commandRecord",List.of(data("commandRecord","cmd.json","{\"command\":[\"mvn\",\"-Dtest=!My*Tests\",\"test\"]}")));
        parts.put("exclusionManifest",List.of(data("exclusionManifest","methods.json",
                "{\"excludedMethods\":[{\"className\":\"MySqlTests\",\"methodName\":\"x\",\"source\":\"MySqlTests.java:4\"}]}")));
        var unsupported = service.importBatch(actor, report, declaration(""), parts);
        assertThat(unsupported.commandRecord().excludedClasses()).isEmpty();
        assertThat(unsupported.excludedMethods()).isEmpty();
        assertThat(unsupported.warnings()).anyMatch(w -> w.contains("清单中的类"));
    }

    @Test void duplicateSuitesModuleDirectoriesAndMavenReactorsAreDiagnosed() {
        var parts = sources();
        parts.put("xmlFiles",List.of(data("xmlFiles","TEST-A.xml",xml("A","/x")),data("xmlFiles","TEST-B.xml",xml("B","/y"))));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts))
                .withMessageContaining("TEST-B.xml").withMessageContaining("不同目录");
        parts.put("xmlFiles",List.of(data("xmlFiles","TEST-A.xml",xml("A","/x")),data("xmlFiles","TEST-B.xml",xml("A","/x"))));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("套件名称重复");
        parts.put("xmlFiles",sources().get("xmlFiles"));
        parts.put("mavenLog",List.of(data("mavenLog","build.log","[INFO] Reactor Build Order:")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("Reactor");
        verify(store,never()).insert(any(),any(),any(),any(),any());
    }

    @Test void nonMavenCommandsAndConflictingTestPropertiesCannotEstablishExclusions() {
        for (String args : List.of("[\"echo\",\"-Dtest=!MySqlTests\"]", "[\"-Dtest=!MySqlTests\"]",
                "[\"https://example.invalid/tools/mvn\",\"-Dtest=!MySqlTests\"]",
                "[\"mvn\",\"-Dtest=!MySqlTests\",\"--define\",\"test=!OtherTests\",\"test\"]",
                "[\"mvn\",\"-Dtest=!MySqlTests\",\"-D\",\"test=!OtherTests\",\"test\"]")) {
            var parts = sources();
            parts.put("commandRecord", List.of(data("commandRecord", "cmd.json", "{\"command\":" + args + "}")));
            parts.put("exclusionManifest", List.of(data("exclusionManifest", "methods.json",
                    "{\"excludedMethods\":[{\"className\":\"MySqlTests\",\"methodName\":\"one\",\"source\":\"MySqlTests.java:4\"}]}")));
            var batch = service.importBatch(actor, report, declaration(""), parts);
            assertThat(batch.commandRecord().command()).contains("-Dtest=!MySqlTests");
            assertThat(batch.commandRecord().excludedClasses()).isEmpty();
            assertThat(batch.excludedMethods()).isEmpty();
            assertThat(batch.warnings()).anyMatch(w -> w.contains("排除范围未知"));
            if (args.contains("OtherTests")) assertThat(batch.commandRecord().selector()).contains("MySqlTests", "OtherTests");
            else assertThat(batch.commandRecord().selector()).isNull();
        }
    }

    @Test void logReportingFlakyResultsAlongsideOrdinaryXmlDoesNotMasqueradeAsSingleRun() {
        var parts = sources();
        parts.put("mavenLog", List.of(data("mavenLog", "flaky.log", """
                [WARNING] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Flakes: 1
                [INFO] BUILD SUCCESS
                """)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor, report, declaration(""), parts))
                .withMessageContaining("flaky.log").withMessageContaining("重跑");
        verify(store,never()).insert(any(),any(),any(),any(),any());
    }

    @Test void readBoundariesAndFormatFailuresAreReportedBeforeAnyInsert() {
        var parts = sources();
        parts.put("xmlFiles",List.of(data("xmlFiles","../TEST-A.xml",xml("A","/a"))));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("文件名");
        parts.put("xmlFiles",List.of(data("xmlFiles","TEST-A.xml",xml("A","/a")),data("xmlFiles","TEST-a.xml",xml("B","/b"))));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("重复文件名");
        MultipartFile big = mock(MultipartFile.class);
        when(big.getOriginalFilename()).thenReturn("TEST-large.xml");
        when(big.getSize()).thenReturn(2L * 1024 * 1024 + 1);
        parts.put("xmlFiles",List.of(big));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("2 MiB");
        MultipartFile[] many = new MultipartFile[41];
        Arrays.fill(many,big);
        parts.put("xmlFiles",Arrays.asList(many));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("40");
        parts.put("xmlFiles",sources().get("xmlFiles"));
        parts.put("mavenLog",List.of(data("mavenLog","one.log","ok"),data("mavenLog","two.log","ok")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts)).withMessageContaining("一份");
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(",\"executedAt\":\"today\""),sources())).withMessageContaining("时区");
        verify(store,never()).insert(any(),any(),any(),any(),any());
    }

    @Test void requestReplayVerifiesContentsNamesReportAndScope() {
        var receipts = new HashMap<UUID,BuildEvidenceStore.Receipt>();
        var batches = new HashMap<UUID,Batch>();
        when(store.request(actor,system,request)).thenAnswer(inv -> Optional.ofNullable(receipts.get(request)));
        when(store.find(eq(actor),eq(system),any())).thenAnswer(inv -> Optional.ofNullable(batches.get(inv.getArgument(2))));
        when(store.insert(eq(actor),eq(request),anyString(),any(),anyList())).thenAnswer(inv -> {
            Batch batch=inv.getArgument(3);
            receipts.put(request,new BuildEvidenceStore.Receipt(batch.summary().id(), inv.getArgument(2)));
            batches.put(batch.summary().id(),batch);
            return true;
        });
        var p = sources();
        var b = service.importBatch(actor,report,declaration(""),p);
        assertThat(service.importBatch(actor,report,declaration(""),p)).isEqualTo(b);
        p.put("xmlFiles",List.of(data("xmlFiles","TEST-A.xml",xml("A","/different"))));
        assertThatThrownBy(() -> service.importBatch(actor,report,declaration(""),p)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        UUID otherReport = UUID.randomUUID();
        var outsider = new ActorContext(UUID.randomUUID(),actor.workspaceId());
        assertThatThrownBy(() -> service.require(outsider,report,b.summary().id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> service.importBatch(actor,otherReport,declaration(""),p)).hasMessageContaining("404");
        verify(store,never()).find(eq(outsider),any(),any());
    }

    @Test void enforcesCombinedByteBudgetAndRejectsUnconfirmedFieldsOrNumbers() {
        byte[] bytes = new byte[2 * 1024 * 1024];
        var files = new java.util.ArrayList<MultipartFile>();
        for (int i = 0; i < 7; i++)
            files.add(new MockMultipartFile("xmlFiles", "TEST-limit" + i + ".xml", "text/xml", bytes));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.importBatch(actor, report, declaration(""), Map.of("xmlFiles",files))).withMessageContaining("12 MiB");
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,
                declaration(",\"systemId\":\"" + system + "\""),sources())).withMessageContaining("字段");
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,
                declaration(",\"exitCode\":0.5"),sources())).withMessageContaining("整数");
        var parts = sources();
        parts.put("commandRecord",List.of(data("commandRecord","commands.json","{\"exit\":0,\"exit\":1}")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.importBatch(actor,report,declaration(""),parts))
                .withMessageContaining("commands.json");
        verify(store,never()).insert(any(),any(),any(),any(),any());
    }
}
