package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ModelReadiness;
import com.jundaodsj.insightops.server.chat.DurableChatRunProperties;
import com.jundaodsj.insightops.server.chat.KnowledgeRagProperties;
import com.jundaodsj.insightops.server.knowledge.KnowledgeEmbeddingProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/** A cheap configuration snapshot. Worker settings and external availability are not inferred. */
@Service
public class SystemCapabilityService {
    private final ModelReadiness model;
    private final KnowledgeRagProperties rag;
    private final KnowledgeEmbeddingProperties embedding;
    private final DurableChatRunProperties queue;

    public SystemCapabilityService(ModelReadiness model, KnowledgeRagProperties rag,
                                   KnowledgeEmbeddingProperties embedding, DurableChatRunProperties queue) {
        this.model = model;
        this.rag = rag;
        this.embedding = embedding;
        this.queue = queue;
    }

    public List<Capability> snapshot() {
        return List.of(
                new Capability("research", "生成研究结论", model.configurationState(),
                        model.ready() ? "模型配置已具备；外部服务连通性与余额尚未验证。"
                                : model.enabled() ? "模型配置不完整，请联系管理员补充配置。"
                                : "当前环境未开启模型生成，可查看已有研究。"),
                new Capability("retrieval", "查找资料", !rag.isEnabled() ? "DISABLED"
                        : embedding.isEnabled() ? "CONFIGURED_UNVERIFIED" : "DEGRADED",
                        !rag.isEnabled() ? "当前环境未开启知识库检索。"
                                : embedding.isEnabled() ? "已配置关键词与语义检索；资料覆盖和检索服务状态需在研究时核对。"
                                : "当前配置仅使用关键词检索，语义检索已关闭；资料覆盖需在研究时核对。"),
                new Capability("recovery", "断线后继续研究", queue.isEnabled() ? "CONFIGURED_UNVERIFIED" : "DISABLED",
                        queue.isEnabled() ? "已开启任务持久化；任务队列和执行进度请在研究详情中查看。"
                                : "当前环境未开启持久任务，断线恢复能力受限。"),
                new Capability("collection", "持续采集更新", "UNKNOWN",
                        "此接口无法确认采集进程是否在线；请在项目资料中核对最近采集时间和失败提示。"),
                new Capability("delivery", "自动投递报告", "UNKNOWN",
                        "此接口无法确认投递进程和渠道是否可用；已完成的研究可先下载报告。"));
    }

    public record Capability(String id, String label, String status, String detail) { }
}
