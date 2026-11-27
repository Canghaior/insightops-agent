package com.jundaodsj.insightops.businesssystem.application;

import static com.jundaodsj.insightops.businesssystem.application.EngineeringBaselineStore.*;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

/** Reads one local POM. It does not build a Maven model, follow a parent, or activate profiles. */
public final class MavenPomFacts {
    public static final String PATH = "sources/pom.xml";
    private static final String NS = "http://maven.apache.org/POM/4.0.0";
    private static final Pattern VALUE = Pattern.compile("[a-zA-Z0-9_.$}{()\\[\\],+\\-]{1,160}");
    private static final Pattern COORD = Pattern.compile("[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*");
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private record Node(String name, int line, StringBuilder chars, List<Node> children) {
        List<Node> all(String key) {
            return children.stream().filter(n -> n.name.equals(key)).toList();
        }

        Node at(String path) {
            Node node = this;
            for (String key : path.split("/")) {
                var hits = node.all(key);
                if (hits.size() != 1) return null;
                node = hits.getFirst();
            }
            return node;
        }

        String text() {
            return chars.toString().strip();
        }

        String value(String key) {
            var n = at(key);
            return n == null ? "" : n.text();
        }

        Source source() {
            return new Source(PATH, line);
        }
    }

    private static Node root(byte[] bytes) {
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var nodes = new ArrayDeque<Node>();
            var roots = new ArrayList<Node>();
            parser.parse(
                    new InputSource(new ByteArrayInputStream(bytes)),
                    new DefaultHandler() {
                        private Locator locator;
                        private int count;

                        @Override
                        public void setDocumentLocator(Locator loc) {
                            locator = loc;
                        }

                        @Override
                        public void startElement(
                                String uri, String name, String q, Attributes attrs) {
                            if (++count > 15000 || nodes.size() >= 80)
                                throw new IllegalArgumentException("POM 结构超出支持范围。");
                            var element =
                                    new Node(
                                            uri.isEmpty() || NS.equals(uri)
                                                    ? name
                                                    : "{other}" + name,
                                            locator.getLineNumber(),
                                            new StringBuilder(),
                                            new ArrayList<>());
                            if (nodes.isEmpty()) roots.add(element);
                            else nodes.peek().children.add(element);
                            nodes.push(element);
                        }

                        @Override
                        public void characters(char[] ch, int start, int len) {
                            if (!nodes.isEmpty()) nodes.peek().chars.append(ch, start, len);
                        }

                        @Override
                        public void endElement(String u, String n, String q) {
                            nodes.pop();
                        }
                    });
            if (roots.size() != 1 || !"project".equals(roots.getFirst().name))
                throw new IllegalArgumentException("需要普通 Maven 根 POM。");
            return roots.getFirst();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("POM 无法安全解析。请检查 XML 格式与命名空间。");
        }
    }

    private final Node pom;
    private final List<Fact> facts;
    private final List<String> gaps;

    private MavenPomFacts(Node pom, List<Fact> facts, List<String> gaps) {
        this.pom = pom;
        this.facts = facts;
        this.gaps = gaps;
    }

    public static void read(byte[] bytes, List<String> focus, List<Fact> facts, List<String> gaps) {
        new MavenPomFacts(root(bytes), facts, gaps).scan(focus);
    }

    private void scan(List<String> focus) {
        if (!pom.all("modules").isEmpty()
                || pom.all("profiles").stream()
                        .flatMap(p -> p.all("profile").stream())
                        .anyMatch(p -> !p.all("modules").isEmpty()))
            throw new IllegalArgumentException("此 POM 声明多个模块，请选择单个模块的根 POM。");
        var artifact = pom.at("artifactId");
        if (artifact == null || !COORD.matcher(artifact.text()).matches())
            throw new IllegalArgumentException("POM 需要唯一的 artifactId。");
        declaration("项目 artifactId", artifact);
        for (String field : List.of("groupId", "version", "packaging"))
            declaration("项目 " + field, pom.at(field));
        var parent = pom.at("parent");
        if (parent != null) {
            String coord = coordinate(parent);
            declaration("父 POM " + coord, parent.at("version"));
            gaps.add(PATH + ":" + parent.line + " 父 POM 内容未解析。依赖管理、继承属性与最终有效版本可能取决于外部输入。");
        }
        for (String k :
                List.of(
                        "java.version",
                        "maven.compiler.release",
                        "maven.compiler.source",
                        "maven.compiler.target")) declaration(k, pom.at("properties/" + k));
        if (List.of(
                                "java.version",
                                "maven.compiler.release",
                                "maven.compiler.source",
                                "maven.compiler.target")
                        .stream()
                        .noneMatch(k -> pom.at("properties/" + k) != null)
                && pom.at("build/plugins") == null) {
            gaps.add(PATH + " 没有可识别的 Java 版本或编译目标声明；实际构建配置未知。");
        }
        for (String section : List.of("dependencies", "dependencyManagement/dependencies")) {
            Node list = pom.at(section);
            if (list == null) continue;
            for (Node dep : list.all("dependency")) {
                String coord = coordinate(dep);
                boolean bom =
                        "import".equals(dep.value("scope")) && "pom".equals(dep.value("type"));
                if (!focus.isEmpty() && !focus.contains(coord) && !bom) continue;
                if (coord.equals("坐标未知")) {
                    gaps.add(PATH + ":" + dep.line + " 依赖的坐标无法由字面声明确认。");
                    continue;
                }
                String subject =
                        (bom ? "导入 BOM " : section.equals("dependencies") ? "直接依赖 " : "依赖管理 ")
                                + coord;
                if (dep.at("version") == null)
                    gaps.add(PATH + ":" + dep.line + " " + subject + " 未显式声明版本。");
                else declaration(subject, dep.at("version"));
            }
        }
        var plugins = pom.at("build/plugins");
        if (plugins != null)
            for (Node pl : plugins.all("plugin"))
                if ("maven-compiler-plugin".equals(pl.value("artifactId")))
                    for (String key : List.of("release", "source", "target"))
                        declaration("编译插件 " + key, pl.at("configuration/" + key));
        if (pom.at("profiles") != null)
            gaps.add(PATH + ":" + pom.at("profiles").line + " 存在 Profile，实际激活情况未知。");
    }

    private static String coordinate(Node dep) {
        String group = dep.value("groupId"), artifact = dep.value("artifactId");
        return COORD.matcher(group).matches() && COORD.matcher(artifact).matches()
                ? group + ":" + artifact
                : "坐标未知";
    }

    private void declaration(String subject, Node n) {
        if (n == null) return;
        if (!VALUE.matcher(n.text()).matches()) {
            gaps.add(PATH + ":" + n.line + " " + subject + " 的表达式不在支持范围。");
            return;
        }
        facts.add(new Fact(Kind.DECLARATION, subject, n.text(), n.source(), List.of()));
        if (!n.text().contains("$")) return;
        var via = new ArrayList<Source>();
        String value = resolve(n.text(), new HashSet<>(), via);
        if (value != null)
            facts.add(
                    new Fact(Kind.STATIC_DERIVATION, subject, value, n.source(), List.copyOf(via)));
        else
            gaps.add(
                    PATH
                            + ":"
                            + n.line
                            + " "
                            + subject
                            + " 的静态值未知。只展开此 POM 中唯一的本地属性，未展开继承或嵌套继承、循环及动态表达式。");
    }

    private String resolve(String raw, Set<String> seen, List<Source> via) {
        var m = EXPRESSION.matcher(raw);
        var result = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            if (seen.size() >= 12
                    || via.size() >= 24
                    || !seen.add(key)
                    || key.toLowerCase(Locale.ROOT)
                            .matches(
                                    ".*(secret|password|token|credential|private.?key|api.?key).*"))
                return null;
            var n = pom.at("properties/" + key);
            if (n == null || !VALUE.matcher(n.text()).matches()) return null;
            via.add(n.source());
            String value = resolve(n.text(), seen, via);
            if (value == null) return null;
            seen.remove(key);
            m.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        m.appendTail(result);
        return result.length() <= 160 && result.indexOf("$") < 0 ? result.toString() : null;
    }
}
