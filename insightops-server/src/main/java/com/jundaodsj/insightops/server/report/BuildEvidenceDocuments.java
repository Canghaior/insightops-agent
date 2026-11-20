package com.jundaodsj.insightops.server.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import com.jundaodsj.insightops.report.application.SurefireEvidenceParser;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic readers for the optional build artifacts. None of their text is executed or dereferenced. */
final class BuildEvidenceDocuments {
    private static final Pattern EXACT_EXCLUDE = Pattern.compile("![A-Za-z_$][\\w$]*");
    private static final Set<String> MAVEN_LAUNCHERS = Set.of("mvn", "mvnw", "mvn.cmd", "mvnw.cmd", "mvn.bat", "mvnw.bat");
    private static final Pattern LOG_COUNTS = Pattern.compile(
            "^\\[(?:INFO|WARN|WARNING|ERROR)]\\s+Tests run:\\s*(\\d{1,8}),\\s*Failures:\\s*(\\d{1,8}),\\s*Errors:\\s*(\\d{1,8}),\\s*Skipped:\\s*(\\d{1,8})(?:,\\s*Flakes:\\s*(\\d{1,8}))?\\s*$");
    private static final Pattern ANSI_COLORS = Pattern.compile("\\x1b\\[[;\\d]*m");
    private static final int MAX_MANIFEST_METHODS = 1000;

    private final ObjectMapper json;
    private final SurefireEvidenceParser xml = new SurefireEvidenceParser();

    BuildEvidenceDocuments(ObjectMapper json) { this.json = json; }

    Batch analyze(Summary header, Metadata metadata, List<Original> originals) {
        List<TestCase> cases = new ArrayList<>();
        Set<String> suites = new HashSet<>();
        Set<String> basedirs = new HashSet<>();
        List<String> warnings = new ArrayList<>();
        BuildRecord command = new BuildRecord(List.of(), null, null, null, null, List.of());
        LogFacts log = new LogFacts(null, null);
        List<ExcludedMethod> methods = List.of();
        for (Original original : originals) {
            EvidenceFile file = original.file();
            if (file.kind() == FileKind.SUREFIRE_XML) {
                var suite = xml.parse(file.id(), file.name(), original.content());
                if (!suites.add(suite.name())) throw bad(file.name(), "套件名称重复。请将多模块、分次运行或重复 XML 分开。");
                if (suite.basedir() != null && !suite.basedir().isBlank()) basedirs.add(suite.basedir());
                if (basedirs.size() > 1) throw bad(file.name(), "XML 的 basedir 属于不同目录。首版仅支持单模块的一次构建。");
                if (cases.size() + suite.cases().size() > SurefireEvidenceParser.MAX_CASES)
                    throw bad(file.name(), "每批最多支持 " + SurefireEvidenceParser.MAX_CASES + " 个 testcase。");
                cases.addAll(suite.cases());
            } else {
                try {
                    switch (file.kind()) {
                        case MAVEN_LOG -> log = log(text(original.content()), warnings);
                        case COMMAND_RECORD -> command = command(object(original.content()), warnings);
                        case EXCLUSION_MANIFEST -> methods = manifest(object(original.content()));
                        default -> throw new IllegalStateException("Unsupported evidence kind");
                    }
                } catch (IllegalArgumentException ex) { throw bad(file.name(), ex.getMessage()); }
            }
        }
        List<String> excluded = command.excludedClasses();
        Set<String> observedClasses = new HashSet<>();
        for (TestCase test : cases) {
            observedClasses.add(simple(test.className()));
            observedClasses.add(simple(test.suite()));
        }
        List<String> observedExcluded = excluded.stream().filter(observedClasses::contains).toList();
        if (!observedExcluded.isEmpty()) warnings.add("命令排除类也出现在 XML 中，请核对材料是否属于同一次构建：" + String.join("、", observedExcluded));
        Set<String> documented = new HashSet<>();
        for (ExcludedMethod method : methods) documented.add(simple(method.className()));
        if (!excluded.containsAll(documented)) {
            warnings.add("清单中的类未能全部对应命令明确排除的类，不能据此确定排除方法数。原清单可下载。");
            methods = List.of();
        } else if (!excluded.isEmpty() && methods.isEmpty()) {
            warnings.add("命令明确排除了测试类，尚无列出具体方法的来源清单。排除方法数量未知。");
        } else if (!documented.containsAll(excluded)) {
            warnings.add("清单只覆盖部分排除类，已列出的方法以外仍有未知项。");
        }

        Counts counts = Counts.from(cases);
        if (command.command().isEmpty()) warnings.add("未取得 Maven 执行命令，测试选择范围未知。");
        if (command.exitCode() == null && metadata.exitCode() == null) warnings.add("未取得退出码，不能由 XML 推出构建成功。");
        if (log.outcome() == null) warnings.add("未取得 Maven 最终 BUILD SUCCESS / BUILD FAILURE 标记。");
        if (command.exitCode() != null && metadata.exitCode() != null && !command.exitCode().equals(metadata.exitCode()))
            warnings.add("命令记录的退出码与用户填写不一致，请核对原件。");
        for (Integer exit : new Integer[]{command.exitCode(), metadata.exitCode()}) {
            if (exit != null && log.outcome() != null && (exit == 0) != log.outcome().equals("SUCCESS")) {
                warnings.add("退出码与 Maven 日志的最终构建标记不一致，请核对来源。");
                break;
            }
        }
        if (log.counts() != null && !log.counts().equals(counts)) warnings.add("日志最终测试计数与这一批 XML 不一致，请核对遗漏或混入的材料。");
        if ("SUCCESS".equals(log.outcome()) && counts.failures() + counts.errors() > 0)
            warnings.add("日志报告构建成功，但 XML 有失败或报错；请核对日志与测试配置。");
        if (cases.isEmpty()) warnings.add("已导入的套件没有记录任何 testcase。");
        warnings.add("统计仅涵盖已上传的 XML。文件哈希可核对字节。原件是否均来自同一模块、同一次构建和所填提交，以及材料是否齐全，尚未核实。");
        var summary = new Summary(header.id(), header.systemId(), header.reportId(), header.role(),
                header.label(), header.importedAt(), counts);
        return new Batch(summary, metadata, command, log, methods, originals.stream().map(Original::file).toList(),
                List.copyOf(cases), List.copyOf(warnings));
    }

    private JsonNode object(byte[] data) {
        try {
            JsonNode root = json.readTree(text(data));
            if (root == null || !root.isObject()) throw new IllegalArgumentException("应为 UTF-8 JSON 对象。");
            return root;
        } catch (JsonProcessingException ex) { throw new IllegalArgumentException("JSON 格式或深度不受支持。"); }
    }

    private static String text(byte[] data) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("包含无法按文本处理的字符。");
            return value.startsWith("\uFEFF") ? value.substring(1) : value;
        } catch (CharacterCodingException ex) { throw new IllegalArgumentException("需要 UTF-8 文本文件。"); }
    }

    private static LogFacts log(String source, List<String> warnings) {
        Counts summary = null;
        String outcome = null;
        int totals = 0, finishes = 0;
        for (Iterator<String> it = source.lines().iterator(); it.hasNext();) {
            String line = it.next();
            if (line.indexOf('\u001b') >= 0) line = ANSI_COLORS.matcher(line).replaceAll("");
            line = line.strip();
            if (!line.startsWith("[")) continue;
            if (line.startsWith("[INFO] Reactor Build Order") || line.startsWith("[INFO] Reactor Summary"))
                throw new IllegalArgumentException("检测到 Maven Reactor 日志，请按模块、按次导入构建材料。");
            var count = LOG_COUNTS.matcher(line);
            if (count.matches()) {
                if (count.group(5) != null && Integer.parseInt(count.group(5)) > 0)
                    throw new IllegalArgumentException("Maven 测试总结报告了 flaky 重跑结果，暂不支持按普通单次测试进行对照。");
                int n = Integer.parseInt(count.group(1)), f = Integer.parseInt(count.group(2));
                int e = Integer.parseInt(count.group(3)), s = Integer.parseInt(count.group(4));
                if (f + e + s > n) throw new IllegalArgumentException("Maven 日志中的测试总结计数互相矛盾。");
                summary = new Counts(n - f - e - s, f, e, s);
                totals++;
            }
            if (line.equals("[INFO] BUILD SUCCESS") || line.equals("[INFO] BUILD FAILURE")) {
                outcome = line.endsWith("SUCCESS") ? "SUCCESS" : "FAILURE";
                finishes++;
            }
        }
        if (finishes > 1 || totals > 1) throw new IllegalArgumentException("日志有多个构建或测试总结，请提供同一模块、同一次构建的日志。");
        if (summary == null) warnings.add("日志中没有识别到整次测试的 Maven 总结行。");
        return new LogFacts(outcome, summary);
    }

    private static BuildRecord command(JsonNode root, List<String> warnings) {
        JsonNode args = root.get("command");
        if (args == null && !root.has("exit") && !root.has("start") && !root.has("end"))
            throw new IllegalArgumentException("未找到支持的 command、exit、start 或 end 字段。");
        List<String> command = new ArrayList<>();
        if (args != null && !args.isNull()) {
            if (!args.isArray() || args.size() > 256) throw new IllegalArgumentException("command 应为最多 256 个参数的数组。");
            for (JsonNode arg : args) command.add(string(arg, 4096, "command 参数"));
        }
        JsonNode status = root.get("exit");
        Integer exit = null;
        if (status != null && !status.isNull()) {
            if (!status.isIntegralNumber() || !status.canConvertToInt()) throw new IllegalArgumentException("exit 应为整数。");
            exit = status.intValue();
        }
        List<String> selectors = new ArrayList<>();
        boolean otherTestProperty = false;
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.startsWith("-Dtest=")) selectors.add(arg.substring(7));
            else if (arg.equals("-Dtest") || arg.equals("--define=test") || arg.startsWith("--define=test=")) {
                otherTestProperty = true;
                selectors.add(arg);
            } else if ((arg.equals("-D") || arg.equals("--define")) && i + 1 < command.size()) {
                String next = command.get(i + 1);
                if (next.equals("test") || next.startsWith("test=")) {
                    otherTestProperty = true;
                    selectors.add(arg + " " + next);
                }
            }
        }
        String selector = selectors.isEmpty() ? null : String.join(" | ", selectors);
        List<String> excluded = List.of();
        List<String> tokens = selectors.size() == 1 ? List.of(selector.split(",", -1)) : List.of();
        if (!command.isEmpty() && !mavenLauncher(command.getFirst())) {
            selector = null;
            warnings.add("未识别到直接启动 Maven 的命令。保留原始参数，具体排除范围未知。");
        } else if (!otherTestProperty && !tokens.isEmpty() && tokens.stream().allMatch(x -> EXACT_EXCLUDE.matcher(x).matches())) {
            excluded = tokens.stream().map(x -> x.substring(1)).distinct().toList();
        } else if (!selectors.isEmpty() || otherTestProperty) {
            warnings.add("选择器包含通配、方法、组合、重复或不支持的参数写法，保留原命令，具体排除范围未知。");
        }
        String start = time(root, "start"), end = time(root, "end");
        if (start != null && end != null && OffsetDateTime.parse(start).isAfter(OffsetDateTime.parse(end)))
            warnings.add("命令记录的结束时间早于开始时间，请核对原记录。");
        return new BuildRecord(List.copyOf(command), exit, start, end, selector, excluded);
    }

    private static boolean mavenLauncher(String command) {
        // Inspect the recorded name only; the path belongs to the external build environment.
        if (command.contains("://")) return false;
        String path = command.replace('\\', '/');
        return MAVEN_LAUNCHERS.contains(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
    }

    private static List<ExcludedMethod> manifest(JsonNode root) {
        JsonNode rows = root.get("excludedMethods");
        if (rows == null || !rows.isArray() || rows.size() > MAX_MANIFEST_METHODS)
            throw new IllegalArgumentException("excludedMethods 应为最多 1000 行的方法清单。");
        List<ExcludedMethod> result = new ArrayList<>();
        Set<List<String>> seen = new HashSet<>();
        for (JsonNode row : rows) {
            String clazz = string(row.path("className"), 512, "className");
            String name = string(row.path("methodName"), 512, "methodName");
            String source = string(row.path("source"), 1024, "source");
            if (clazz.isBlank() || name.isBlank() || !source.matches(".+:[1-9][0-9]{0,8}"))
                throw new IllegalArgumentException("排除方法需要类、方法和带行号的来源位置。");
            if (!seen.add(List.of(clazz, name))) throw new IllegalArgumentException("清单中有重复的方法身份。");
            result.add(new ExcludedMethod(clazz, name, source));
        }
        return List.copyOf(result);
    }

    static String timestamp(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try { OffsetDateTime.parse(value); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException(field + " 需要带时区的 ISO 时间。"); }
        return value;
    }

    private static String time(JsonNode root, String key) {
        return !root.hasNonNull(key) ? null : timestamp(string(root.get(key), 100, key), key);
    }

    private static String string(JsonNode node, int max, String key) {
        if (!node.isTextual() || node.textValue().length() > max || node.textValue().indexOf('\0') >= 0)
            throw new IllegalArgumentException(key + " 应为不超出限制的文本。");
        return node.textValue();
    }

    private static String simple(String className) { return className.substring(className.lastIndexOf('.') + 1); }
    private static IllegalArgumentException bad(String name, String message) { return new IllegalArgumentException(name + "：" + message); }
}
