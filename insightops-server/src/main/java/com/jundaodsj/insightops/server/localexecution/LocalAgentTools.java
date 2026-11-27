package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.FunctionDefinition;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway.PlannedToolCall;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LocalAgentTools {
    @FunctionalInterface
    interface TestCandidate { Object run() throws IOException; }

    record Output(boolean ok, Object result, String error) {}

    private static final FunctionDefinition LIST = function("list_files",
            "列出工程文件，可用 pathContains 过滤路径。结果截断时用 nextPage 继续。", """
            {"type":"object","properties":{"directory":{"type":"string"},"pathContains":{"type":"string"},
            "page":{"type":"integer"}},"additionalProperties":false}
            """);
    private static final FunctionDefinition SEARCH = function("search_code",
            "在仓库内按文字搜索，返回文件路径和行号。结果截断时用 nextPage 继续。", """
            {"type":"object","properties":{"directory":{"type":"string"},"query":{"type":"string"},"page":{"type":"integer"}},
            "required":["query"],"additionalProperties":false}
            """);
    private static final FunctionDefinition READ = function("read_file",
            "读取指定文件的 UTF-8 行，返回准确的旧文件 sha256。行号从 1 开始，省略时读开头至最多 200 行。", """
            {"type":"object","properties":{"path":{"type":"string"},"fromLine":{"type":"integer"},
            "toLine":{"type":"integer"}},"required":["path"],"additionalProperties":false}
            """);
    private static final FunctionDefinition WRITE = function("write_file",
            "实际写入单个授权文件。修改时先读并使用准确旧哈希。新建时 expectedSha256 传空字符串。content 为完整文件，保留文件尾换行。已有测试文件不可覆盖。", """
            {"type":"object","properties":{"path":{"type":"string"},"expectedSha256":{"type":"string"},
            "content":{"type":"string"}},"required":["path","expectedSha256","content"],"additionalProperties":false}
            """);
    private static final FunctionDefinition TEST = function("run_maven_tests",
            "在隔离容器运行当前工作副本的真实 Maven test，返回退出码、Surefire 计数和日志。应在修改完成后调用。", """
            {"type":"object","properties":{},"additionalProperties":false}
            """);
    private static final FunctionDefinition WRITE_FILES = function("write_files",
            "联动修改多个授权文件：先校验所有文件的旧哈希再写入。每项提供完整文件，新文件哈希为空。", """
            {"type":"object","properties":{"edits":{"type":"array","items":{"type":"object","properties":{
            "path":{"type":"string"},"expectedSha256":{"type":"string"},"content":{"type":"string"}},
            "required":["path","expectedSha256","content"],"additionalProperties":false}}},
            "required":["edits"],"additionalProperties":false}
            """);

    private final ObjectMapper json;
    private final LocalRepositoryFiles files;
    private final TestCandidate tester;

    LocalAgentTools(ObjectMapper json, LocalRepositoryFiles files, TestCandidate tester) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.files = files;
        this.tester = tester;
    }

    static List<FunctionDefinition> definitions(boolean edit) {
        return edit ? List.of(LIST, SEARCH, READ, WRITE, WRITE_FILES, TEST) : List.of(LIST, SEARCH, READ);
    }

    Output call(PlannedToolCall call, boolean editing) throws IOException {
        if (definitions(editing).stream().noneMatch(d -> d.name().equals(call.name()))) {
            return error("该工具未在当前阶段开放。");
        }
        if (call.argumentsJson().length() > 280_000) return error("工具参数超出字节限制。");
        try {
            var args = json.readTree(call.argumentsJson());
            if (args == null || !args.isObject()) return error("工具参数须为 JSON 对象。");
            Object result = switch (call.name()) {
                case "list_files" -> {
                    keys(args, "directory", "pathContains", "page");
                    yield files.list(text(args, "directory", ""), text(args, "pathContains", ""), integer(args, "page", 1));
                }
                case "search_code" -> {
                    keys(args, "directory", "query", "page");
                    yield files.search(text(args, "directory", ""), text(args, "query", null), integer(args, "page", 1));
                }
                case "read_file" -> {
                    keys(args, "path", "fromLine", "toLine");
                    int first = integer(args, "fromLine", 1);
                    yield files.read(text(args, "path", null), first, integer(args, "toLine", first + 199));
                }
                case "write_file" -> {
                    keys(args, "path", "expectedSha256", "content");
                    yield files.write(text(args, "path", null), text(args, "expectedSha256", null), text(args, "content", null));
                }
                case "run_maven_tests" -> {
                    keys(args);
                    yield tester.run();
                }
                case "write_files" -> {
                    keys(args, "edits");
                    var list = args.path("edits");
                    if (!list.isArray() || list.isEmpty() || list.size() > 8) throw new IllegalArgumentException("需要 1 至 8 个文件。");
                    var edits = new ArrayList<LocalRepositoryFiles.Edit>();
                    for (var item : list) {
                        if (!item.isObject()) throw new IllegalArgumentException("每项须为文件变更对象。");
                        keys(item, "path", "expectedSha256", "content");
                        edits.add(new LocalRepositoryFiles.Edit(text(item, "path", null), text(item, "expectedSha256", null),
                                text(item, "content", null)));
                    }
                    yield files.writeAll(edits);
                }
                default -> throw new IllegalStateException("工具未注册。");
            };
            return new Output(true, result, "");
        } catch (JsonProcessingException e) {
            return error("工具参数不是有效的 JSON。");
        } catch (CharacterCodingException e) {
            return error("该文件不是支持的 UTF-8 文本，无法读取或改写。");
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    private static void keys(JsonNode args, String... allowed) {
        var names = Set.of(allowed);
        var fields = args.fieldNames();
        while (fields.hasNext()) {
            if (!names.contains(fields.next())) throw new IllegalArgumentException("工具参数包含未定义字段。");
        }
    }

    private static String text(JsonNode object, String name, String otherwise) {
        var node = object.get(name);
        if (node == null && otherwise != null) return otherwise;
        if (node == null || !node.isTextual() || node.textValue().indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " 须为普通字符串。");
        }
        return node.textValue();
    }

    private static int integer(JsonNode object, String name, int otherwise) {
        var node = object.get(name);
        if (node == null) return otherwise;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 200_000) {
            throw new IllegalArgumentException(name + " 须为 1 至 200000 的整数。");
        }
        return node.intValue();
    }

    private static Output error(String message) { return new Output(false, Map.of(), message); }

    private static FunctionDefinition function(String name, String description, String schema) {
        return new FunctionDefinition(name, description, schema);
    }
}
