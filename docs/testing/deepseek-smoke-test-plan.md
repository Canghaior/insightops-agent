# DeepSeek P0 Smoke Test 计划

> 状态：P0-017～P0-022 模型、流式、工具、结构化输出、错误与成本审计门禁已通过
> 日期：2026-08-15，2026-08-16 更新
> 对应决策：`../architecture/ADR-003-deepseek-model.md`

## 1. 目标

使用最小、可重复的测试确认 DeepSeek 官方 API 和 Spring AI 集成能够支撑 InsightOps Agent 的第一条链路。

本计划不把在线模型测试混入普通单元测试。在线 Smoke Test 必须显式启用，并记录用量和结果。

## 2. 前置条件

- 已创建正式工程骨架。
- 已使用 Spring AI OpenAI-compatible Starter 接入 DeepSeek 官方 API。
- 当前进程存在 `DEEPSEEK_API_KEY`，但测试不得打印其值。
- `DEEPSEEK_CHAT_MODEL=deepseek-v4-flash`。
- `DEEPSEEK_THINKING_ENABLED=false`。
- DeepSeek 账户余额和 API 状态正常。
- 在线测试默认关闭，仅通过显式 Profile 或测试标签启用。

建议测试标记：

```text
@Tag("online-model")
```

## 3. Test 1：认证与普通问答

输入：

```text
只回答：DEEPSEEK_SMOKE_OK
```

通过标准：

- HTTP 调用成功。
- 最终内容包含且只表达 `DEEPSEEK_SMOKE_OK`。
- 返回模型元数据能够识别模型。
- 记录总耗时和 Token（接口提供时）。
- 日志和测试报告不包含 API Key。

## 4. Test 2：真实流式输出

输入：

```text
用三句话说明 GitHub Release 对技术团队的价值，每句话单独输出。
```

通过标准：

- 收到多个增量 Chunk，而不是结束后一次性返回完整答案。
- 能记录首 Token 时间和总完成时间。
- Chunk 顺序正确，拼接后的最终内容完整。
- 正常收到结束信号。
- 取消订阅后不再向客户端发送内容。

## 5. Test 3：Tool Calling

P0 产品链路采用 ADR-003 确定的项目控制工具循环：受控路由只允许调用三个白名单仓库的 `github_release_list`，DeepSeek 接收工具结果并生成最终答案。该策略优先保证可审计性和参数安全；模型原生动态 Function Calling 不作为 P0-020 完成门槛。

注册无外部副作用的测试工具：

```text
get_current_date
```

工具输入为空对象，输出固定测试日期。提示模型回答“今天的日期是什么”，但禁止它凭自身知识回答。

通过标准：

- 模型选择 `get_current_date`。
- 工具参数是合法 JSON 且符合 Schema。
- 系统只执行已注册工具。
- 工具结果回传后模型生成最终答案。
- Tool Call 的名称、参数、开始、结束、状态和结果摘要可记录。
- 模型不得在工具执行前编造日期。

## 6. Test 4：JSON 结构化输出

要求模型返回：

```json
{
  "project": "spring-ai",
  "intent": "release_list",
  "timeWindowDays": 30
}
```

通过标准：

- 输出可以直接被 JSON 解析器读取。
- 三个字段存在且类型正确。
- 不包含 Markdown 代码围栏或额外说明。
- 空内容或截断时能够识别为失败。
- JSON 空响应最多允许重试一次。

## 7. Test 5：错误、超时与取消

分别验证：

| 场景 | 期望行为 |
|---|---|
| 无效 Key | 返回认证错误，不重试，不泄露 Key |
| 429 | 有限退避并最多重试2次 |
| 5xx | 最多重试2次，失败后返回统一错误 |
| 连接超时 | 明确标记连接失败 |
| Run 超过90秒 | 标记 `TIMED_OUT` 并停止后续步骤 |
| 用户取消 | 标记 `CANCELLED`，停止流和工具循环 |

错误响应不得包含内部堆栈、完整 Prompt、Authorization 头或服务端敏感信息。

## 8. Test 6：成本与审计

通过标准：

- 记录 Provider、模型名和 Run ID。
- 记录输入、输出和缓存 Tokens（接口提供时）。
- 记录开始、首 Token 和结束时间。
- 记录成功、失败、超时和取消状态。
- 可以根据带生效日期的价格配置计算估算费用。
- 审计日志不包含 API Key。
- 普通 CI 测试默认不调用 DeepSeek。

## 9. Embedding测试状态

P0 设置 `EMBEDDING_ENABLED=false`，不执行 Embedding Smoke Test。

进入完整 RAG 阶段时另建测试计划，至少验证：

- 中英文技术文本效果。
- 返回维度固定。
- 单条和批量输入。
- 最大长度和截断策略。
- 相同文本的稳定性。
- pgvector 写入、过滤、检索和删除。
- 模型版本或维度变化时的重建策略。

## 10. 执行结果模板

实际执行时创建：

```text
docs/testing/results/deepseek-smoke-YYYY-MM-DD.md
```

记录：

```markdown
# DeepSeek Smoke Test 结果

- 日期：
- 环境：
- Spring Boot：
- Spring AI：
- 模型：
- 思考模式：
- 执行人：

| 测试 | 结果 | 耗时 | Token | 费用 | 备注 |
|---|---|---:|---:|---:|---|
| 普通问答 | NOT_RUN | | | | |
| 流式输出 | NOT_RUN | | | | |
| Tool Calling | NOT_RUN | | | | |
| JSON 输出 | NOT_RUN | | | | |
| 错误/超时/取消 | NOT_RUN | | | | |
| 成本与审计 | NOT_RUN | | | | |
```

## 11. 任务映射与完成定义

- `P0-017`：Test 1、同步调用的 Token/耗时/TraceId、统一错误映射、Key 不泄漏。
- `P0-018`：Test 2，真实 SSE 流式输出和取消。
- `P0-020`：Test 3 的真实 GitHub Release 工具、受控路由、证据增强生成和执行审计。
- `P0-022`：Test 4～6 的完整自动化与发布门禁。

六项全部通过是“P0 模型能力完整门禁”，不再错误地阻塞仅负责首个 Provider 同步接入的 `P0-017`。
