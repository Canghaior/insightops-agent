# P2.0-B Function Calling 多轮 Agent 验收

日期：2026-08-21
范围：本地代码、数据库链路门禁、前端生产构建、三类生产镜像
结论：本地代码、数据库与前端回归通过；初始版本 `9ae6ba855ac05b02de6744492455ba2352801ca3` 已部署且容器健康，生产真实问答暴露多 Tool Call 拒绝问题；本次顺序化热修复待 CI 与生产复验。

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
热修复复跑: Docker Hub auth token endpoint timeout，构建开始前中止；部署前必须由 GitHub CI 重新构建三镜像
```

## 新增专项测试

- `ModelUsageTest`：已知/未知字段相加与溢出保护。
- `SpringAiDeepSeekPlanningModelGatewayTest`：原生 Function Call、模型配置、历史 Observation 回放。
- `AgentLoopServiceTest`：规划、执行、观察、停止、证据/引用和 Token 汇总；最大轮次上限；多 Tool Call 跨轮顺序化。
- `agentStream.p2b.test.ts`：标准化 `tool_failed` 事件与错误信息不泄漏。

## 部署边界

初始版本 `9ae6ba855ac05b02de6744492455ba2352801ca3` 已在生产健康启动；真实 DeepSeek 问答运行 `d76fea48-289a-4691-884a-8b6eb8883f39` 以 `MODEL_MULTIPLE_TOOL_CALLS` 失败。本次修复将多个候选工具跨轮顺序化，但在 GitHub CI 三镜像通过、生产重新部署以及同一问法复验成功之前，不关闭 P2.0-B 生产验收。
