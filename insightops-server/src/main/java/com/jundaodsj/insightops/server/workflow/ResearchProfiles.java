package com.jundaodsj.insightops.server.workflow;

import java.util.*;

/** Shared form definitions and research guidance; project facts remain untrusted user input. */
public final class ResearchProfiles {
    private ResearchProfiles() { }
    public record Field(String key, String label, String placeholder, int maxLength) { }
    public record Profile(String id, String label, String description, List<Field> fields, List<String> concerns) { }
    private static Field field(String key, String label, String example) { return new Field(key, label, example, 200); }
    private static final List<Profile> PROFILES = List.of(
            new Profile("java_backend", "Java 后端", "围绕运行环境、API、配置与依赖研究升级影响。", List.of(
                    field("javaVersion", "Java 版本", "例如：21"), field("springBootVersion", "Spring Boot 版本", "例如：3.4.5"),
                    field("middleware", "中间件与主要依赖", "例如：PostgreSQL、Redis，填写实际版本"),
                    field("deployment", "部署环境", "例如：Linux 容器 / JDK 发行版")),
                    List.of("API 与配置变更", "依赖与兼容性", "弃用与移除", "安全风险", "性能与稳定性", "测试与回滚")),
            new Profile("web_frontend", "Web 前端", "围绕组件、构建工具、浏览器与渲染方式研究升级影响。", List.of(
                    field("nodeVersion", "Node.js 版本", "例如：22"), field("typescriptVersion", "TypeScript 版本", "例如：5.6"),
                    field("buildTool", "构建工具与插件", "例如：Vite 5、@vitejs/plugin-vue 5"),
                    field("uiLibraries", "组件库与主要依赖", "例如：Vue Router、Pinia、组件库及版本"),
                    field("browsers", "需要支持的浏览器", "例如：Chrome、Edge、Safari，注明最低版本"),
                    field("renderMode", "渲染与部署方式", "例如：SPA / SSR / 静态站点")),
                    List.of("组件 API 与响应式行为", "构建与 TypeScript", "插件与组件库兼容性", "浏览器与渲染", "性能与稳定性", "测试与回滚")),
            new Profile("general", "通用项目", "按填写的技术背景研究；其他方向尚未完成专项验收。", List.of(
                    field("runtime", "语言与运行环境", "填写实际语言、运行时及版本"),
                    field("dependencies", "主要依赖", "填写组件、插件及版本"),
                    field("deployment", "运行与部署平台", "填写操作系统、架构和部署方式")),
                    List.of("API 与配置变更", "依赖与兼容性", "弃用与移除", "安全风险", "性能与稳定性", "测试与回滚")));

    public static List<Profile> all() { return PROFILES; }
    public static Profile require(String id) {
        return PROFILES.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("研究方向无效，请重新选择。"));
    }
    public static String suggest(String owner, String name) {
        if ("vuejs".equalsIgnoreCase(owner) || "vitejs".equalsIgnoreCase(owner) || "facebook/react".equalsIgnoreCase(owner + "/" + name)) return "web_frontend";
        if ("spring-projects".equalsIgnoreCase(owner) || "langchain4j".equalsIgnoreCase(owner)) return "java_backend";
        return "general";
    }
    public static String projectName(String owner, String name) {
        return "vuejs/core".equalsIgnoreCase(owner + "/" + name) ? "Vue" : name;
    }
    public static Map<String, String> normalize(String profileId, Map<String, String> supplied, String javaVersion, String springBootVersion) {
        Profile profile = require(profileId);
        Map<String, String> values = new LinkedHashMap<>(supplied == null ? Map.of() : supplied);
        if ("java_backend".equals(profileId)) {
            if (javaVersion != null && !javaVersion.isBlank()) values.putIfAbsent("javaVersion", javaVersion);
            if (springBootVersion != null && !springBootVersion.isBlank()) values.putIfAbsent("springBootVersion", springBootVersion);
        }
        Map<String, Integer> allowed = new LinkedHashMap<>();
        profile.fields().forEach(field -> allowed.put(field.key(), field.maxLength()));
        allowed.put("notes", 1000);
        for (var entry : values.entrySet()) {
            if (!allowed.containsKey(entry.getKey())) throw new IllegalArgumentException("项目背景含不属于当前方向的字段，请重新选择配置。");
            if (entry.getValue() == null || entry.getValue().length() > allowed.get(entry.getKey()) || entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("项目背景内容无效或过长。");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        allowed.keySet().forEach(key -> { if (values.containsKey(key) && !values.get(key).isBlank()) normalized.put(key, values.get(key).strip()); });
        return normalized;
    }
    public static String label(String key) {
        if ("notes".equals(key)) return "项目补充说明";
        return PROFILES.stream().flatMap(profile -> profile.fields().stream()).filter(field -> field.key().equals(key))
                .map(Field::label).findFirst().orElse(key);
    }
    public static String terms(String id) {
        return switch (id) {
            case "web_frontend" -> "components reactivity TypeScript build browser rendering SSR plugins";
            case "java_backend" -> "Java Spring Boot dependencies configuration API";
            default -> "runtime dependencies configuration compatibility";
        };
    }
    public static String guidance(String id) {
        return switch (id) {
            case "web_frontend" -> "重点核查组件 API、响应式行为、构建插件、TypeScript、浏览器支持与 SPA/SSR。用户未使用的功能应标为条件性影响。Vue 核心声明无破坏性变更，不等于业务组件、插件和浏览器已验证兼容。未取得插件自己的版本资料时不能推断插件已兼容。";
            case "java_backend" -> "重点核查 Java/Spring Boot 基线、依赖、API、配置、中间件与业务回归。不能用其他维护分支的依赖版本替代目标版本要求。";
            default -> "围绕用户提供的语言、运行环境、依赖和部署条件调查。没有该方向专项资料时明确说明，不凭常识补充兼容性结论。";
        };
    }
}
