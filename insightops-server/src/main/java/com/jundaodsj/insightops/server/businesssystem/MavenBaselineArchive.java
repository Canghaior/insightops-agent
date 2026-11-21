package com.jundaodsj.insightops.server.businesssystem;

import static com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.businesssystem.application.MavenPomFacts;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

final class MavenBaselineArchive {
    static final int MIB = 1024 * 1024;
    static final int MAX_BYTES = 6 * MIB + 65536;
    private static final String MANIFEST = "evidence/extractor.json";
    private static final String TREE = "evidence/dependency-tree.txt";
    private static final String BUILD = "evidence/maven-version.txt";
    private static final String APP = "evidence/application.log";
    private static final Set<String> KNOWN =
            Set.of(
                    "baseline.json",
                    MANIFEST,
                    TREE,
                    BUILD,
                    APP,
                    MavenPomFacts.PATH,
                    "sources/.mvn/wrapper/maven-wrapper.properties",
                    "sources/src/main/resources/application.properties");
    private static final Pattern JAVA_PATH =
            Pattern.compile(
                    "sources/src/(main|test)/java/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.java");
    private static final String PART = "[A-Za-z0-9_.-]{1,100}";
    private static final Pattern TREE_ROOT =
            Pattern.compile(
                    "^(?:\\[INFO] )?("
                            + PART
                            + "):("
                            + PART
                            + "):"
                            + PART
                            + ":("
                            + PART
                            + ")\\s*$");
    private static final Pattern DEP =
            Pattern.compile(
                    "^(?:\\[INFO] )?[ |]*(?:\\+-|\\\\-) ("
                            + PART
                            + "):("
                            + PART
                            + "):"
                            + PART
                            + ":(?:"
                            + PART
                            + ":)?("
                            + PART
                            + "):(?:compile|test|provided|runtime|system)(?:"
                            + " \\(optional\\))?\\s*$");
    private static final Pattern BUILD_JAVA =
            Pattern.compile("^Java version:\\s*(" + PART + ")(?:,.*)?$");
    private static final Pattern RUN_JAVA =
            Pattern.compile(
                    "\\bStarting ([\\w.$]*Application) using Java (" + PART + ") with PID\\b");
    private final ObjectMapper json;

    MavenBaselineArchive(ObjectMapper json) {
        this.json = json;
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, byte[]> entries(String name, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("工程原件大小不在支持范围。");
        if ("pom.xml".equalsIgnoreCase(name)) {
            if (bytes.length > MIB) throw invalid("pom.xml 超过 1 MiB。");
            return Map.of(MavenPomFacts.PATH, bytes);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw invalid("请选择 pom.xml 或 v1 工程资料 ZIP。");
        Map<String, byte[]> files = new TreeMap<>();
        Set<String> names = new HashSet<>();
        int total = 0;
        try (var zip =
                new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String path = entry.getName();
                if (entry.isDirectory()
                        || path.length() > 240
                        || (!KNOWN.contains(path) && !JAVA_PATH.matcher(path).matches())
                        || !names.add(path.toLowerCase(Locale.ROOT))
                        || files.size() >= 32) throw invalid("工程包包含未支持或重复的路径。");
                byte[] data = zip.readNBytes(MIB + 1);
                total += data.length;
                if (data.length == 0 || data.length > MIB || total > 6 * MIB)
                    throw invalid("工程包内原件为空或超过大小限制。");
                files.put(path, data);
            }
        } catch (IOException ex) {
            throw invalid("工程包损坏，无法读取原件。");
        }
        if (!files.keySet().containsAll(Set.of("baseline.json", MavenPomFacts.PATH, MANIFEST)))
            throw invalid("工程包缺少格式说明、根 POM 或原始提取清单。");
        return files;
    }

    private static IllegalArgumentException invalid(String error) {
        return new IllegalArgumentException(error);
    }

    private JsonNode read(byte[] bytes) {
        try {
            var node = json.readTree(bytes);
            if (node == null || !node.isObject()) throw invalid("工程清单应为 JSON 对象。");
            return node;
        } catch (IOException ex) {
            throw invalid("工程资料的 JSON 清单无法解析。");
        }
    }

    Analysis parse(String name, byte[] bytes) {
        var files = entries(name, bytes);
        var facts = new ArrayList<Fact>();
        var gaps = new ArrayList<String>();
        var focus = new LinkedHashSet<String>();
        String label = "单模块 POM";
        if (files.containsKey("baseline.json")) {
            var desc = read(files.get("baseline.json"));
            if (!"insightops.maven-baseline/v1".equals(desc.path("format").asText()))
                throw invalid("不支持的工程资料格式版本。");
            label = desc.path("label").asText("").strip();
            if (label.isEmpty()
                    || label.length() > 240
                    || !desc.path("focus").isArray()
                    || desc.path("focus").size() > 32) throw invalid("基线标签或关注坐标无效。");
            for (var item : desc.path("focus")) {
                if (!item.asText().matches(PART + ":" + PART)) throw invalid("关注的 Maven 坐标格式不受支持。");
                focus.add(item.asText());
            }
            verifyInventory(desc.path("files"), files, false);
            var extracted = read(files.get(MANIFEST));
            if (!label.equals(extracted.path("baselineLabel").asText()))
                throw invalid("来源标签与提取清单不一致。");
            verifyInventory(extracted.path("files"), files, true);
        }
        MavenPomFacts.read(files.get(MavenPomFacts.PATH), List.copyOf(focus), facts, gaps);
        observe(files, TREE, facts, gaps, focus);
        observe(files, BUILD, facts, gaps, focus);
        observe(files, APP, facts, gaps, focus);
        if (facts.size() > 96 || gaps.size() > 64) throw invalid("工程资料超出首版单模块事实范围，请使用指定关注坐标的工程包。");
        var inventory =
                files.entrySet().stream()
                        .map(e -> new FileInfo(e.getKey(), hash(e.getValue()), e.getValue().length))
                        .toList();
        return new Analysis(label, inventory, List.copyOf(facts), List.copyOf(gaps));
    }

    private static void verifyInventory(
            JsonNode rows, Map<String, byte[]> files, boolean extractor) {
        if (!rows.isArray()) throw invalid("工程包缺少文件清单。");
        var expected = new HashSet<>(files.keySet());
        if (extractor) expected.removeIf(p -> !p.startsWith("sources/"));
        else expected.remove("baseline.json");
        for (var row : rows) {
            String path = (extractor ? "sources/" : "") + row.path("path").asText("");
            if (!expected.remove(path)
                    || !hash(files.get(path)).equals(row.path("sha256").asText())
                    || (extractor
                            && (!"read".equals(row.path("status").asText())
                                    || files.get(path).length != row.path("bytes").asInt(-1))))
                throw invalid("原件与清单不一致，无法核对文件、字节数或 SHA-256。");
        }
        if (!expected.isEmpty()) throw invalid("存在未列入清单的原件。");
    }

    private static boolean matchingTree(String[] lines, List<Fact> facts, List<String> gaps) {
        var expected = new HashMap<String, String>();
        for (Fact fact : facts) {
            if (fact.subject().startsWith("项目 ") && !fact.value().contains("$")) {
                expected.put(fact.subject().substring(3), fact.value());
            }
        }
        int roots = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher root = TREE_ROOT.matcher(lines[i]);
            if (!root.matches()) continue;
            roots++;
            String[] fields = {"groupId", "artifactId", "version"};
            for (int j = 0; j < fields.length; j++) {
                String value = expected.get(fields[j]);
                if (value != null && !value.equals(root.group(j + 1))) {
                    gaps.add(
                            TREE
                                    + ":"
                                    + (i + 1)
                                    + " 中的根项目 "
                                    + fields[j]
                                    + " 为 "
                                    + root.group(j + 1)
                                    + "，与本 POM 的 "
                                    + value
                                    + " 不同。此记录不能确定本模块的实际依赖版本。");
                    return false;
                }
            }
        }
        if (roots == 1) return true;
        gaps.add(TREE + " 未能识别唯一的 Maven 根项目，本模块的实际依赖版本未知。");
        return false;
    }

    private static void observe(
            Map<String, byte[]> files,
            String path,
            List<Fact> facts,
            List<String> gaps,
            Set<String> focus) {
        byte[] data = files.get(path);
        int before = facts.size();
        if (data != null) {
            String[] lines = new String(data, StandardCharsets.UTF_8).split("\\R");
            if (path.equals(TREE) && !matchingTree(lines, facts, gaps)) return;
            var seen = new HashSet<String>();
            for (int i = 0; i < lines.length; i++) {
                Matcher m =
                        (path.equals(TREE) ? DEP : path.equals(BUILD) ? BUILD_JAVA : RUN_JAVA)
                                .matcher(lines[i]);
                if (!m.find()) continue;
                var source = new Source(path, i + 1);
                String subject, value;
                Kind kind;
                if (path.equals(TREE)) {
                    String coord = m.group(1) + ":" + m.group(2);
                    if (!focus.isEmpty() && !focus.contains(coord)) continue;
                    subject = coord;
                    value = m.group(3);
                    kind = Kind.MAVEN_RESOLUTION;
                } else if (path.equals(BUILD)) {
                    subject = "Maven 进程使用的 Java";
                    value = m.group(1);
                    kind = Kind.BUILD_ENVIRONMENT;
                } else {
                    subject = m.group(1) + " 进程使用的 Java";
                    value = m.group(2);
                    kind = Kind.RUNTIME_OBSERVATION;
                }
                if (seen.add(subject + " " + value))
                    facts.add(new Fact(kind, subject, value, source, List.of()));
                if (seen.size() > 48) throw invalid("单份执行记录的结果过多，请缩小资料范围。");
            }
        }
        if (facts.size() == before)
            gaps.add(
                    (data == null ? "未提供" : "未能从所给文件识别")
                            + (path.equals(TREE)
                                    ? " Maven 实际依赖解析记录，最终解析版本未知。"
                                    : path.equals(BUILD)
                                            ? " Maven 进程的 Java 版本记录，构建工具 JDK 未知。"
                                            : " 应用进程启动记录，业务进程 JDK 未知。"));
        else gaps.add(path + " 仅代表所提供记录中的执行。与指定源码提交、生产环境或其他记录的对应关系仍需外部佐证。");
    }
}
