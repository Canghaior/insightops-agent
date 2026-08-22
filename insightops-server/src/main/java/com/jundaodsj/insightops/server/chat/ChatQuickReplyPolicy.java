package com.jundaodsj.insightops.server.chat;

import java.util.Locale;
import java.util.Optional;

/** Deterministic replies for product identity questions that need no model or tool call. */
public final class ChatQuickReplyPolicy {

    private ChatQuickReplyPolicy() {
    }

    public static Optional<String> answer(String question, String model) {
        if (question == null) return Optional.empty();
        String normalized = question.trim()
                .replaceAll("[\\s？?！!。]+$", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.matches(
                "^(你(现在)?(是|使用|用的|使用的)(什么|哪个)?模型|"
                        + "(当前|现在)(使用|用的|使用的)?(什么|哪个)?模型|"
                        + "what model( are you| do you use)?)$")) {
            return Optional.of("当前 InsightOps 生产对话模型配置为 DeepSeek 的 "
                    + model + "。这个身份问题由系统直接回答，不需要启动工具检索。");
        }
        if (normalized.matches("^(你是谁|你叫什么|what are you|who are you)$")) {
            return Optional.of("我是 InsightOps Agent，负责基于官方知识库和只读工具，"
                    + "为 Spring AI、LangChain4j、Dify 与 Agent 架构问题提供可追溯回答。");
        }
        if (normalized.matches(
                "^(你能(做|干)什么|你有什么能力|what can you do)$")) {
            return Optional.of("我可以检索 Spring AI、LangChain4j 和 Dify 的官方资料，"
                    + "调用受控只读工具进行多步分析，并给出带来源、可追踪的技术回答。");
        }
        if (normalized.matches("^(你好|您好|hello|hi)$")) {
            return Optional.of("你好，我是 InsightOps Agent。你可以直接询问 Spring AI、"
                    + "LangChain4j、Dify 或 Agent 架构问题。");
        }
        return Optional.empty();
    }
}
