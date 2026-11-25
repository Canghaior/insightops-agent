package com.jundaodsj.insightops.server.businesssystem;

import static com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.Kind.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

class MavenBaselineArchiveTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MavenBaselineArchive parser = new MavenBaselineArchive(json);
    private static final String POM =
            """
<project><modelVersion>4.0.0</modelVersion>
<parent><groupId>example</groupId><artifactId>platform</artifactId><version>1</version></parent>
<artifactId>demo</artifactId>
<properties><major>17</major><java.version>${major}</java.version>
<nested>${from.parent}</nested><maven.compiler.release>${nested}</maven.compiler.release></properties>
<dependencies><dependency><groupId>example</groupId><artifactId>lib</artifactId></dependency></dependencies>
</project>
""";
    private static final String APPLICATION_LOG = """
            2026 INFO Starting DemoApplication using Java 17.0.4 with PID 100
            2026 INFO No active profile set, falling back to 1 default profile: "default"
            2026 INFO HikariPool-1 - Added connection conn0: url=jdbc:h2:mem:demo
            """;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void selectedModulePackagesOnlyApprovedOriginalsWithVerifiableHashes() {
        var files = Map.of("sources/pom.xml", bytes(POM), "sources/src/main/java/Amount.java",
                bytes("class Amount {}"), "evidence/maven-version.txt", bytes("Java version: 21, vendor: Example"));
        byte[] result = parser.packageModule("orders", List.of("example:lib"), files);
        assertThat(parser.packageModule("orders", List.of("example:lib"), files)).isEqualTo(result);
        var received = MavenBaselineArchive.entries("module.zip", result);
        assertThat(received.keySet()).containsExactlyInAnyOrder("baseline.json", "evidence/extractor.json",
                "sources/pom.xml", "sources/src/main/java/Amount.java", "evidence/maven-version.txt");
        files.forEach((path, contents) -> assertThat(received.get(path)).isEqualTo(contents));
        assertThat(parser.parse("module.zip", result).facts()).anyMatch(f -> f.kind() == BUILD_ENVIRONMENT);
        assertThatThrownBy(() -> parser.packageModule("orders", List.of(), Map.of("sources/pom.xml", bytes(POM),
                "sources/../.env", bytes("example")))).hasMessageContaining("不支持打包");
        assertThatThrownBy(() -> parser.packageModule("orders", List.of(), Map.of("sources/pom.xml",
                bytes(POM.replace("<artifactId>demo", "<modules><module>app</module></modules><artifactId>demo")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamedMavenPomKeepsTheSameFactsAndOriginalBytes() {
        var original = bytes(POM);
        assertThat(parser.parse("pom-target.XML", original)).isEqualTo(parser.parse("pom.xml", original));
        assertThat(MavenBaselineArchive.entries("pom-target.XML", original).get("sources/pom.xml"))
                .containsExactly(original);
        assertThatThrownBy(() -> parser.parse("settings.xml", bytes("<settings/>")))
                .hasMessageContaining("需要普通 Maven 根 POM");
    }

    @Test
    void ordinarySourceZipSuggestsSelectingItsRootPom() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("pom.xml"));
            zip.write(bytes(POM));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> parser.parse("downloaded-source.zip", out.toByteArray()))
                .hasMessageContaining("请解压后选择模块的根 POM");
    }

    @Test
    void returnsDeclarationsAndLocalProvenanceAndLeavesInheritedUnknown() {
        var result = parser.parse("pom.xml", bytes(POM));
        assertThat(result.facts())
                .anyMatch(
                        f ->
                                f.kind() == DECLARATION
                                        && f.value().equals("${major}")
                                        && f.source().line() == 4);
        assertThat(result.facts())
                .anyMatch(
                        f ->
                                f.kind() == STATIC_DERIVATION
                                        && f.subject().equals("java.version")
                                        && f.value().equals("17")
                                        && f.via().getFirst().line() == 4);
        assertThat(result.facts())
                .noneMatch(
                        f ->
                                f.subject().equals("maven.compiler.release")
                                        && f.kind() == STATIC_DERIVATION);
        assertThat(result.gaps())
                .anyMatch(g -> g.contains("嵌套继承"))
                .anyMatch(g -> g.contains("未显式声明"))
                .anyMatch(g -> g.contains("最终解析版本未知"))
                .anyMatch(g -> g.contains("业务进程 JDK 未知"));
    }

    private byte[] zip(String overrideHash) throws IOException {
        return zip(overrideHash, "demo");
    }

    private byte[] zip(String overrideHash, String artifact) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        files.put("sources/pom.xml", bytes(POM));
        files.put(
                "evidence/extractor.json",
                json.writeValueAsBytes(
                        Map.of(
                                "baselineLabel",
                                "sample",
                                "files",
                                List.of(
                                        Map.of(
                                                "path",
                                                "pom.xml",
                                                "bytes",
                                                bytes(POM).length,
                                                "sha256",
                                                MavenBaselineArchive.hash(bytes(POM)),
                                                "status",
                                                "read")))));
        files.put(
                "evidence/maven-version.txt",
                bytes("Apache Maven 3.9.9\nJava version: 21.0.1, vendor: demo\n"));
        files.put("evidence/application.log", bytes(APPLICATION_LOG));
        files.put(
                "evidence/dependency-tree.txt",
                bytes(
                        "group:"
                                + artifact
                                + ":jar:1\n"
                                + "+- example:lib:jar:2.1:compile\n"
                                + "\\- other:unselected:jar:9:compile\n"));
        var list =
                files.entrySet().stream()
                        .map(
                                e ->
                                        Map.of(
                                                "path",
                                                e.getKey(),
                                                "sha256",
                                                overrideHash != null
                                                        ? overrideHash
                                                        : MavenBaselineArchive.hash(e.getValue())))
                        .toList();
        files.put(
                "baseline.json",
                json.writeValueAsBytes(
                        Map.of(
                                "format",
                                "insightops.maven-baseline/v1",
                                "label",
                                "sample",
                                "focus",
                                List.of("example:lib"),
                                "files",
                                list)));
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void evidenceTypesReferToIndependentRecordsAndFocus() throws Exception {
        var analysis = parser.parse("example.zip", zip(null));
        assertThat(analysis.facts())
                .anyMatch(
                        f ->
                                f.kind() == BUILD_ENVIRONMENT
                                        && f.value().equals("21.0.1")
                                        && f.source().line() == 2)
                .anyMatch(f -> f.kind() == RUNTIME_OBSERVATION && f.value().equals("17.0.4"))
                .anyMatch(
                        f ->
                                f.kind() == MAVEN_RESOLUTION
                                        && f.value().equals("2.1")
                                        && f.source().path().endsWith("dependency-tree.txt"))
                .noneMatch(f -> f.subject().contains("unselected"));
        assertThat(analysis.gaps()).anyMatch(g -> g.contains("对应关系仍需外部佐证"));
    }

    @Test
    void incompatibleExecutionRecordStaysAnOriginalWithAnExplicitGap() throws Exception {
        var result = parser.parse("case.zip", zip(null, "different-module"));
        assertThat(result.facts()).noneMatch(f -> f.kind() == MAVEN_RESOLUTION);
        assertThat(result.gaps())
                .anyMatch(g -> g.contains("different-module") && g.contains("不能确定本模块"));
        assertThat(result.files()).anyMatch(f -> f.path().equals("evidence/dependency-tree.txt"));
    }

    @Test
    void runtimeLogOriginalRetainsObservationsOmittedFromSupportedFacts() throws Exception {
        var result = parser.parse("case.zip", zip(null));

        assertThat(result.files()).filteredOn(file -> file.path().equals("evidence/application.log"))
                .singleElement().satisfies(file -> assertThat(file.sha256())
                        .isEqualTo(MavenBaselineArchive.hash(bytes(APPLICATION_LOG))));
        assertThat(result.facts()).filteredOn(fact -> fact.kind() == RUNTIME_OBSERVATION)
                .singleElement().satisfies(fact -> {
                    assertThat(fact.value()).isEqualTo("17.0.4");
                    assertThat(fact.source().line()).isEqualTo(1);
                });
    }

    @Test
    void checksumAndUnsupportedStructuresFailWithoutAResult() throws Exception {
        assertThatThrownBy(() -> parser.parse("case.zip", zip("0".repeat(64))))
                .hasMessageContaining("清单不一致");
        for (String extra :
                List.of(
                        "<modules><module>a</module></modules>",
                        "<profiles><profile><modules><module>a</module></modules></profile></profiles>"))
            assertThatThrownBy(
                            () ->
                                    parser.parse(
                                            "pom.xml",
                                            bytes(POM.replace("</project>", extra + "</project>"))))
                    .hasMessageContaining("模块");
        assertThatThrownBy(() -> parser.parse("pom.xml", bytes("incomplete XML")))
                .hasMessageContaining("无法安全解析");
    }
}
