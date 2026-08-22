package com.jundaodsj.insightops.server.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatQuickReplyPolicyTest {

    @Test
    void answersIdentityQuestionsWithoutStartingAnAgentPlan() {
        assertThat(ChatQuickReplyPolicy.answer(
                "你是什么模型？", "deepseek-v4-flash"))
                .hasValueSatisfying(answer -> assertThat(answer)
                        .contains("DeepSeek", "deepseek-v4-flash", "不需要启动工具检索"));
        assertThat(ChatQuickReplyPolicy.answer(
                "你能做什么", "deepseek-v4-flash")).isPresent();
    }

    @Test
    void keepsEvidenceQuestionsOnTheAgentPath() {
        assertThat(ChatQuickReplyPolicy.answer(
                "Spring AI 最新稳定版是什么？", "deepseek-v4-flash")).isEmpty();
    }
}
