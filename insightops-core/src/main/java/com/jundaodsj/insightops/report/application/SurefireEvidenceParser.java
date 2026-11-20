package com.jundaodsj.insightops.report.application;

import com.jundaodsj.insightops.report.application.BuildEvidence.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Reads a single ordinary Surefire suite. No system properties or console output become test evidence. */
public final class SurefireEvidenceParser {
    public static final int MAX_CASES = 4000;
    private static final int MAX_STACK = 128 * 1024;

    public record Suite(String name, String basedir, List<TestCase> cases) {}

    public Suite parse(UUID fileId, String fileName, byte[] xml) {
        var handler = new SuiteHandler(fileId);
        try {
            var factory = SAXParserFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(new ByteArrayInputStream(xml), handler);
            return new Suite(handler.suite, handler.basedir, List.copyOf(handler.cases));
        } catch (SAXParseException ex) {
            throw new IllegalArgumentException(fileName + " 第 " + ex.getLineNumber()
                    + " 行不是支持的 XML。请使用格式完整、无 DTD 和外部实体的 Surefire TEST-*.xml。");
        } catch (SAXException | IOException ex) {
            throw new IllegalArgumentException(fileName + " 的 XML 无法读取。");
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("无法启用安全 XML 解析配置。", ex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fileName + "：" + ex.getMessage(), ex);
        }
    }

    private static final class SuiteHandler extends DefaultHandler {
        private final UUID fileId;
        private final List<TestCase> cases = new ArrayList<>();
        private int depth;
        private String section;
        private String leaf;
        private String suite;
        private String basedir;
        private Counts expected;
        private String className;
        private String testName;
        private Status status;
        private String type;
        private String message;
        private StringBuilder diagnostic;
        private boolean inDiagnostic;

        SuiteHandler(UUID fileId) { this.fileId = fileId; }

        @Override
        public void startElement(String uri, String name, String qName, Attributes attrs) {
            depth++;
            if (depth > 3 || !uri.isEmpty()) throw invalid("仅支持普通单模块 Surefire testsuite 结构。");
            if (depth == 3) leaf = name;
            if (depth == 1) {
                if (!name.equals("testsuite")) throw invalid("根元素应为单个 testsuite，暂不支持合并套件或多模块 XML。");
                suite = identity(attrs, "name");
                int total = number(attrs, "tests");
                int failures = number(attrs, "failures");
                int errors = number(attrs, "errors");
                int skipped = number(attrs, "skipped");
                if (failures + errors + skipped > total) throw invalid("套件声明的计数互相矛盾。");
                expected = new Counts(total - failures - errors - skipped, failures, errors, skipped);
            } else if (depth == 2) {
                section = name;
                switch (name) {
                    case "testcase" -> {
                        if (cases.size() >= MAX_CASES) throw invalid("每批最多支持 " + MAX_CASES + " 个 testcase。");
                        if (attrs.getValue("status") != null || attrs.getValue("result") != null)
                            throw invalid("testcase #" + (cases.size() + 1) + " 含非标准 status / result 属性，无法按普通 Surefire 确认结果。");
                        className = identity(attrs, "classname");
                        testName = identity(attrs, "name");
                        status = Status.PASSED;
                        type = null;
                        message = null;
                        diagnostic = null;
                    }
                    case "properties", "system-out", "system-err" -> { }
                    default -> throw invalid("套件内含不支持的元素 " + name + "。请按普通单次构建分别导入。");
                }
            } else if ("properties".equals(section) && name.equals("property")) {
                if ("basedir".equals(attrs.getValue("name"))) {
                    String value = bounded(attrs.getValue("value"), 2048, "basedir");
                    if (basedir != null && !basedir.equals(value)) throw invalid("同一 XML 声明了不同的模块目录。");
                    basedir = value;
                }
            } else if ("testcase".equals(section)) {
                if (name.equals("system-out") || name.equals("system-err")) return;
                if (status != Status.PASSED) throw invalid("testcase #" + (cases.size() + 1) + " 含多个结果标记。");
                status = switch (name) {
                    case "failure" -> Status.FAILURE;
                    case "error" -> Status.ERROR;
                    case "skipped" -> Status.SKIPPED;
                    default -> throw invalid("testcase #" + (cases.size() + 1) + " 使用不支持的标记 " + name
                            + "。重跑或 flaky 结果需单独核对。");
                };
                type = bounded(attrs.getValue("type"), 1000, "异常类型");
                message = bounded(attrs.getValue("message"), MAX_STACK, "异常消息");
                diagnostic = new StringBuilder();
                inDiagnostic = true;
            } else {
                throw invalid("套件包含不支持的嵌套结构。");
            }
        }

        @Override
        public void characters(char[] ch, int offset, int length) {
            if (inDiagnostic) {
                if (length > MAX_STACK - diagnostic.length()) throw invalid("单条异常正文过长，原件未导入。");
                diagnostic.append(ch, offset, length);
            } else if (!(depth == 2 && output(section)) && !(depth == 3 && (output(leaf) || "property".equals(leaf)))) {
                for (int i = offset; i < offset + length; i++) {
                    if (!Character.isWhitespace(ch[i])) throw invalid("套件或 testcase 中存在不支持的散文本结果。");
                }
            }
        }

        private static boolean output(String tag) { return "system-out".equals(tag) || "system-err".equals(tag); }

        @Override
        public void endElement(String uri, String name, String qName) {
            if (depth == 3 && inDiagnostic) inDiagnostic = false;
            if (depth == 2 && name.equals("testcase")) {
                cases.add(new TestCase(fileId, suite, className, testName, cases.size() + 1, status, type, message,
                        diagnostic == null ? null : diagnostic.toString()));
            }
            if (depth == 1 && !expected.equals(Counts.from(cases))) {
                throw invalid("套件声明计数与实际 testcase 的通过、failure、error、skipped 计数不一致。");
            }
            depth--;
        }

        @Override public void error(SAXParseException ex) throws SAXException { throw ex; }
        @Override public void fatalError(SAXParseException ex) throws SAXException { throw ex; }

        private static int number(Attributes attrs, String key) {
            String raw = attrs.getValue(key);
            if (raw == null || !raw.matches("[0-9]{1,7}")) throw invalid("套件缺少有效计数 " + key + "。");
            int n = Integer.parseInt(raw);
            if (n > MAX_CASES) throw invalid("每批最多支持 " + MAX_CASES + " 个 testcase。");
            return n;
        }

        private static String identity(Attributes attrs, String key) {
            String value = bounded(attrs.getValue(key), 2000, key);
            if (value == null || value.isBlank()) throw invalid("缺少测试身份字段 " + key + "。");
            return value;
        }

        private static String bounded(String value, int max, String field) {
            if (value != null && value.length() > max) throw invalid(field + " 超出导入长度限制。");
            return value;
        }

        private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException(detail); }
    }
}
