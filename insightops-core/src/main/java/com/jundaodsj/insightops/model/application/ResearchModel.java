package com.jundaodsj.insightops.model.application;

import java.util.Arrays;

/** Stable public choices for a research run. Provider credentials are resolved by the server. */
public enum ResearchModel {
    GPT_62_SOL("gpt-6.2-sol", "GPT-6.2 Sol"),
    GPT_62_ASTRA("gpt-6.2-astra", "GPT-6.2 Astra"),
    GPT_61_TERRA("gpt-6.1-terra", "GPT-6.1 Terra"),
    GPT_61_SOL("gpt-6.1-sol", "GPT-6.1 Sol"),
    GPT_6_ASTRA("gpt-6-astra", "GPT-6 Astra"),
    GPT_6_SOL("gpt-6-sol", "GPT-6 Sol"),
    DEEPSEEK_FLASH("deepseek-flash", "DeepSeek Flash", false),
    DEEPSEEK_FLASH_THINKING("deepseek-flash", "DeepSeek Flash · 思考", true),
    DEEPSEEK_PRO("deepseek-v4-pro", "DeepSeek Pro", false),
    DEEPSEEK_PRO_THINKING("deepseek-v4-pro", "DeepSeek Pro · 思考", true);

    private final String modelId;
    private final String label;
    private final String provider;
    private final boolean thinking;

    ResearchModel(String modelId, String label) { this(modelId, label, "openai", false); }
    ResearchModel(String modelId, String label, boolean thinking) { this(modelId, label, "deepseek", thinking); }
    ResearchModel(String modelId, String label, String provider, boolean thinking) {
        this.modelId = modelId;
        this.label = label;
        this.provider = provider;
        this.thinking = thinking;
    }
    public String id() { return thinking ? modelId + "-thinking" : modelId; }
    public String modelId() { return modelId; }
    public String label() { return label; }
    public String provider() { return provider; }
    public boolean thinking() { return thinking; }
    public static ResearchModel fromId(String id) {
        return Arrays.stream(values()).filter(model -> model.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持这个研究模型，请重新选择。"));
    }
}
