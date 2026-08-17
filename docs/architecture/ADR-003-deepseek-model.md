# ADR-003：P0 使用 DeepSeek 作为 Chat Model

> 状态：已接受，2026-08-17 补充 P1 Embedding 决策
> 日期：2026-08-15
> 决策范围：P0 最小 Agent 垂直闭环
> 决策人：项目所有者

## 1. 背景

InsightOps Agent 的第一条真实链路需要完成中文研究问答、SSE 流式输出、JSON 结构化结果和 GitHub 工具调用。项目所有者已经准备 DeepSeek 官方 API Key，并确认优先使用 DeepSeek 模型。

P0 当前只处理 GitHub Release 结构化数据，不进行文档向量检索，因此 Chat Model 和 Embedding Model 可以分阶段决策。

## 2. 决策

### 2.1 Chat Model

| 配置 | 决策 |
|---|---|
| Provider | DeepSeek 官方 API |
| Base URL | `https://api.deepseek.com` |
| 默认模型 | `deepseek-v4-flash` |
| 后续复杂研究候选 | `deepseek-v4-pro` |
| API 协议 | OpenAI Chat Completions compatible |
| Java 集成 | Spring AI OpenAI-compatible Starter |
| P0 思考模式 | 显式关闭 |
| 温度 | `0.2` |
| 最大输出 Tokens | `4096` |
| 最大工具轮数 | `4` |
| 单次 Run 总超时 | `90` 秒 |
| 最大重试 | `2` 次，仅针对可重试错误 |

旧模型名 `deepseek-chat` 和 `deepseek-reasoner` 不进入项目配置。启动 Smoke Test 必须先调用模型列表或通过官方文档确认模型名仍可用。

### 2.2 思考模式

P0 使用：

```json
{
  "thinking": {
    "type": "disabled"
  }
}
```

原因：

1. P0 重点是工具选择、参数正确、来源引用和执行审计。
2. 非思考模式链路更简单，延迟和 Token 成本更可控。
3. 思考模式的工具调用需要正确保存和回传 `reasoning_content`，P0 不承担该额外协议复杂度。
4. 后续复杂专题研究可以单独评测 V4 Pro 思考模式，不改变默认模型。

系统不保存、展示或审计模型的隐式思维链。业务审计只记录输入摘要、Agent Step、Tool Call、证据、最终答案、Token、耗时和状态。

### 2.3 Embedding

P0 明确不启用 Embedding：

```text
EMBEDDING_ENABLED=false
```

原因：

1. 第一条链路直接消费 GitHub Release API 的结构化结果，不需要向量检索。
2. DeepSeek 当前官方 API 未提供项目需要的独立 Embedding 接口契约，不能假设现有 DeepSeek Key 同时支持 Embedding。
3. 过早接入第二个 Provider 会扩大密钥、成本、错误处理和测试范围。

在完整 RAG 阶段前必须单独选定 Embedding Provider、模型、向量维度、批大小和迁移策略，并用中英文技术文档评测后再写入数据库迁移。

### 2.4 P1.4-B Embedding 决策

P0 的“不启用 Embedding”边界保持不变。P1.4-B 已完成独立选型并采用本机 Ollama `bge-m3`：1024 维、32 条一批、向量写入 PostgreSQL pgvector。该方案不需要新的云端 API Key，也不调用 DeepSeek；模型文件保存在 D 盘。完整实现与真实验收见 [P1.4-B 本地 Embedding 与向量检索](p1-vector-embedding-retrieval.md)。

## 3. 集成方式

2026-08-16 的真实 Spike 发现 Spring AI 2.0 原生 DeepSeek Starter 的请求结构没有 V4 `thinking` 字段，导致 `DEEPSEEK_THINKING_ENABLED=false` 无法生效。P0 因此按预设备选方案改用 Spring AI OpenAI-compatible Starter，并通过 `extraBody` 发送 `thinking.type=disabled`：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

项目保留自己的模型端口，业务模块不直接依赖 DeepSeek 类型：

```text
Agent Core
  → ChatModelPort
  → SpringAiDeepSeekChatAdapter（OpenAI-compatible 协议）
  → DeepSeek API
```

工具执行循环由项目控制，以便持久化 `agent_run`、`agent_step` 和 `tool_call`。Spring AI 负责模型协议和流式数据适配，业务状态机、预算、权限、工具审计和取消由项目实现。

## 4. 配置与密钥

仓库只提交 `.env.example`，真实 Key 只能通过 `DEEPSEEK_API_KEY` 注入。

`SPRING_AI_MODEL_CHAT=openai` 只表示底层使用兼容协议适配器；业务 Provider 仍然记录为 `deepseek`，模型仍由 `DEEPSEEK_CHAT_MODEL` 指定。

禁止：

- 在 Markdown、YAML、Java、测试 Fixture 或前端代码中写入真实 Key。
- 在日志中记录 Authorization 请求头或完整 Key。
- 把 Key 放入 Tool Result、Agent 消息或错误响应。
- 将真实 `.env.local` 提交到 Git。

正式工程创建时必须将 `.env.local`、`.env.*.local` 和 IDE 私有配置加入 `.gitignore`。

## 5. 错误和重试策略

| 类型 | 策略 |
|---|---|
| 400 参数错误 | 不重试，记录为请求契约错误 |
| 401/403 认证错误 | 不重试，提示检查密钥或权限 |
| 408/网络瞬断 | 有限重试，最多2次 |
| 429 限流 | 遵守服务端提示并有限退避，不无限等待 |
| 5xx | 最多重试2次，之后返回可理解错误 |
| 用户取消 | 立即终止流和后续工具调用，不重试 |
| Run 超过90秒 | 标记超时并停止继续生成 |

## 6. 成本控制

P0 初始开发预算上限设置为每月20元人民币，可通过环境变量调整。每次调用记录：

- Provider 和模型名。
- 输入、输出和缓存命中 Tokens（接口提供时）。
- 开始、首 Token 和结束时间。
- 成功、失败、超时或取消状态。
- 按当期价格计算的估算费用。

价格不能硬编码在业务代码，应使用带生效日期的配置或成本表。

## 7. 备选方案

### 7.1 使用通用 OpenAI Starter 代理 DeepSeek

已选择。原生 Starter 的兼容性 Spike 因缺少 V4 `thinking` 请求字段失败；OpenAI-compatible Starter 支持 `extraBody`，能够保持 DeepSeek 官方 API、模型、工具调用和流式协议不变，同时显式关闭思考模式。

### 7.2 P0 默认使用 V4 Pro

暂不选择。P0 的工具路由和 Release 总结优先追求低延迟、低成本和可重复性，V4 Flash 足够用于第一轮验证。

### 7.3 P0 同时接入 Embedding

不选择。Embedding 对第一条 GitHub Release 链路没有必要价值，在 RAG 阶段单独决策。

## 8. 后果

正面影响：

- 能利用现有 DeepSeek API Key 快速开始模型验证。
- Chat、Streaming、JSON 和 Tool Calling 使用同一 Provider。
- P0 不被 Embedding 和第二套密钥阻塞。
- 模型配置和业务代码保持隔离。

代价与风险：

- 项目暂时依赖 DeepSeek 官方服务可用性。
- Spring AI OpenAI-compatible 适配器需要在后续 SSE 和工具调用任务中继续验证。
- 未来启用思考模式时，需要增加 `reasoning_content` 协议测试。
- RAG 阶段仍需重新做一次 Embedding 选型。

## 9. 验收条件

本 ADR 仅表示方案已确定，不表示模型已经接入。模型接入完成必须满足：

1. `deepseek-v4-flash` 模型可用。
2. 普通问答、真实流式、Tool Calling 和 JSON 输出 Smoke Test 通过。
3. 401、429、5xx、超时和取消行为符合策略。
4. API Key 不出现在仓库、日志和响应中。
5. Token、耗时和估算费用能够记录。
6. CI 中的普通测试不调用付费在线模型。
