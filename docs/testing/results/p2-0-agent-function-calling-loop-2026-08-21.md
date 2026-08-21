# P2.0-B Function Calling 多轮 Agent 验收

日期：2026-08-21
范围：本地代码、数据库链路门禁、前端生产构建、三类生产镜像
结论：通过。顺序化热修复 `cd297a6bc408ace94025974aabea5d4892449945` 已完成 CI、生产部署和真实多工具问答复验，P2.0-B 生产验收关闭。

## 验收内容

- DeepSeek 原生 Function Calling 能返回结构化 Tool Call，框架不直接执行工具。
- Planner 能携带 Assistant Tool Call 与 Tool Observation 进入下一轮。
- Executor 能通过统一 Registry/审计入口路由三类真实只读工具。
- `Plan -> Tool -> Observation -> Finish` 能累计证据、引用和所有 Planner Token。
- `MODEL_MAX_TOOL_ROUNDS` 真实限制工具轮次，到达上限后只允许基于已有 Observation 回答。
- 参数异常、重复调用、工具失败和客户端取消采用安全错误路径；多 Tool Call 在禁止并行的前提下跨轮顺序化。
- PLAN、TOOL、OBSERVATION 能进入现有执行记录时间线。
- SSE/前端支持同一 Run 的多个工具进度与 `tool_failed` 安全降级。
- DeepSeek 关闭时多轮 Agent 服务不会破坏应用启动。

## 自动化结果

```text
INSIGHTOPS_CHAIN_GATE=true mvn -q verify
Tests: 193 passed, 0 failures, 0 errors, 0 skipped
Flyway: V1-V24 在临时 PostgreSQL Schema 完整执行

npm run test -- --run
Test Files: 14 passed
Tests: 31 passed

npm run lint
passed

npm run build
vue-tsc + Vite production build passed

docker compose --env-file .env.prod.example -f infra/compose.prod.yml build server worker web
初始版本 9ae6ba8: server / worker / web built
热修复由 GitHub Actions CI #54（Run 32481502882）完成：backend / frontend / server / worker / web 全部通过
```

## 新增专项测试

- `ModelUsageTest`：已知/未知字段相加与溢出保护。
- `SpringAiDeepSeekPlanningModelGatewayTest`：原生 Function Call、模型配置、历史 Observation 回放。
- `AgentLoopServiceTest`：规划、执行、观察、停止、证据/引用和 Token 汇总；最大轮次上限；多 Tool Call 跨轮顺序化。
- `agentStream.p2b.test.ts`：标准化 `tool_failed` 事件与错误信息不泄漏。

## 生产验收

- 生产地址：`https://insightops.canghaior.com`
- 部署版本：`cd297a6bc408ace94025974aabea5d4892449945`
- 容器：server / worker / web 均为 healthy。
- 初始失败 Run：`d76fea48-289a-4691-884a-8b6eb8883f39`，失败码 `MODEL_MULTIPLE_TOOL_CALLS`。
- 修复后成功 Run：`e76fcec8`（页面短 ID）。
- 同一真实问题先执行 GitHub Release 工具，获取 10 条结果；下一轮执行知识库混合检索，获取 6 条证据；最终回答成功生成并展示 Release 与文档来源。
- 多个候选 Tool Call 已按模型顺序跨轮执行，没有并行越过 Executor、Registry、权限或审计边界。

以上结果确认 P2.0-B 的 Function Calling、多轮 Observation、跨工具证据累计和最终回答已形成生产闭环。强制超时、重试、熔断及尝试级审计进入 P2.0-C。
