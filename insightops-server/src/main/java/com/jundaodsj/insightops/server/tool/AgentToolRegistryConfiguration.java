package com.jundaodsj.insightops.server.tool;

import com.jundaodsj.insightops.server.chat.KnowledgeRagProperties;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import com.jundaodsj.insightops.tool.application.registry.AgentToolNames;
import com.jundaodsj.insightops.tool.application.registry.AgentToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class AgentToolRegistryConfiguration {

    @Bean
    public AgentToolRegistry agentToolRegistry(KnowledgeRagProperties ragProperties) {
        return new AgentToolRegistry(definitions(ragProperties.isEnabled()));
    }

    public static List<AgentToolDefinition> definitions(boolean ragEnabled) {
        return List.of(releaseDefinition(), ragDefinition(ragEnabled), eventDefinition(),
                memoryDefinition(), mcpDefinition());
    }

    private static AgentToolDefinition releaseDefinition() {
        return definition(
                AgentToolNames.GITHUB_RELEASE_LIST,
                "读取当前 Workspace 已启用项目的 GitHub 官方 Release；用于版本、发布、升级和变更比较。",
                true,
                Duration.ofSeconds(30),
                List.of(
                        AgentToolDefinition.Parameter.stringArray(
                                "projectIds", "服务端解析后的项目 ID，最多 3 个。", true, 3),
                        AgentToolDefinition.Parameter.integer(
                                "timeWindowDays", "可选时间窗口，1 到 365 天。", false, 1, 365),
                        AgentToolDefinition.Parameter.integer(
                                "maxReleasesPerProject", "每个项目最多返回的 Release。", true, 1, 30),
                        AgentToolDefinition.Parameter.bool(
                                "includePrereleases", "是否包含预发布版本。", true)),
                List.of(
                        AgentToolDefinition.Parameter.jsonArray(
                                "releases", "GitHub 官方 Release 记录。", true),
                        AgentToolDefinition.Parameter.string(
                                "fetchedAt", "GitHub 获取时间。", true, 64),
                        AgentToolDefinition.Parameter.bool(
                                "truncated", "结果是否被上限截断。", true)));
    }

    private static AgentToolDefinition ragDefinition(boolean enabled) {
        return definition(
                AgentToolNames.KNOWLEDGE_HYBRID_SEARCH,
                "在用户有权访问的官方资料和上传文件中执行关键词与向量混合检索。",
                enabled,
                Duration.ofSeconds(20),
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "query", "用于检索的当前问题和必要对话上下文。", true, 16_000),
                        AgentToolDefinition.Parameter.integer(
                                "candidateLimit", "进入证据筛选的候选切片上限。", true, 1, 20)),
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "provider", "检索提供方。", true, 128),
                        AgentToolDefinition.Parameter.string(
                                "model", "向量模型。", true, 256),
                        AgentToolDefinition.Parameter.string(
                                "mode", "实际检索模式。", true, 64),
                        AgentToolDefinition.Parameter.bool(
                                "vectorAvailable", "向量检索是否可用。", true),
                        AgentToolDefinition.Parameter.bool(
                                "answerable", "证据是否足以回答当前问题。", true),
                        AgentToolDefinition.Parameter.integer(
                                "retrievalDurationMs", "检索耗时毫秒数。", true, 0, 120_000),
                        AgentToolDefinition.Parameter.integer(
                                "resultCount", "最终选中的证据切片数量。", true, 0, 12),
                        AgentToolDefinition.Parameter.jsonArray(
                                "sources", "结构化引用与得分。", true)));
    }

    private static AgentToolDefinition eventDefinition() {
        return definition(
                AgentToolNames.PROJECT_INTELLIGENCE_EVENT_SEARCH,
                "检索已采集的 GitHub Issue、Pull Request 和 Security Advisory 情报事件。",
                true,
                Duration.ofSeconds(10),
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "question", "当前用户问题。", true, 4_000),
                        AgentToolDefinition.Parameter.stringArray(
                                "eventTypes", "服务端允许的事件类型，最多 3 类。", true, 3),
                        AgentToolDefinition.Parameter.integer(
                                "limit", "最多返回的事件数。", true, 1, 50)),
                List.of(
                        AgentToolDefinition.Parameter.integer(
                                "resultCount", "检索到的事件数量。", true, 0, 50),
                        AgentToolDefinition.Parameter.stringArray(
                                "sources", "GitHub 官方事件 URL。", true, 50)));
    }

    private static AgentToolDefinition memoryDefinition() {
        return new AgentToolDefinition(
                AgentToolNames.USER_MEMORY_UPSERT,
                1,
                "新增或更新当前用户的长期记忆。该操作必须由当前用户人工审批后才会执行。",
                true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.MUTATING,
                AgentToolDefinition.ApprovalPolicy.REQUIRED,
                Duration.ofSeconds(10),
                100_000,
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "key", "记忆名称，例如回答风格。", true, 80),
                        AgentToolDefinition.Parameter.string(
                                "value", "需要记住的具体内容。", true, 1_000),
                        AgentToolDefinition.Parameter.string(
                                "category", "PROFILE、PREFERENCE、INTEREST 或 CONSTRAINT。", true, 32)),
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "status", "审批状态。", true, 32),
                        AgentToolDefinition.Parameter.string(
                                "approvalId", "人工审批记录 ID。", true, 64),
                        AgentToolDefinition.Parameter.string(
                                "expiresAt", "审批到期时间。", true, 64)));
    }

    private static AgentToolDefinition mcpDefinition() {
        return new AgentToolDefinition(
                AgentToolNames.MCP_READ_CALL,
                1,
                "调用 Workspace 管理员已启用并加入白名单的 HTTPS MCP 只读工具。",
                true,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                Duration.ofSeconds(30),
                200_000,
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "connectionId", "MCP 连接 ID。", true, 64),
                        AgentToolDefinition.Parameter.string(
                                "toolName", "连接白名单内的 MCP 工具名。", true, 128),
                        AgentToolDefinition.Parameter.json(
                                "arguments", "传给 MCP 工具的 JSON 参数。", true)),
                List.of(
                        AgentToolDefinition.Parameter.string(
                                "connection", "MCP 连接名称。", true, 100),
                        AgentToolDefinition.Parameter.string(
                                "toolName", "实际调用的 MCP 工具名。", true, 128),
                        AgentToolDefinition.Parameter.json(
                                "result", "MCP JSON-RPC 结果。", true)));
    }

    private static AgentToolDefinition definition(
            String name,
            String description,
            boolean enabled,
            Duration timeout,
            List<AgentToolDefinition.Parameter> input,
            List<AgentToolDefinition.Parameter> output) {
        return new AgentToolDefinition(
                name,
                1,
                description,
                enabled,
                AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER,
                AgentToolDefinition.RiskLevel.READ_ONLY,
                AgentToolDefinition.ApprovalPolicy.NOT_REQUIRED,
                timeout,
                1_000_000,
                input,
                output);
    }
}
