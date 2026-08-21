# P2.0-B Function Calling 多轮 Agent 验收

日期：2026-08-21
范围：本地代码、数据库链路门禁、前端生产构建、三类生产镜像
结论：通过；尚未推送或部署生产。

## 验收内容

- DeepSeek 原生 Function Calling 能返回结构化 Tool Call，框架不直接执行工具。
- Planner 能携带 Assistant Tool Call 与 Tool Observation 进入下一轮。
- Executor 能通过统一 Registry/审计入口路由三类真实只读工具。
- `Plan -> Tool -> Observation -> Finish` 能累计证据、引用和所有 Planner Token。
- `MODEL_MAX_TOOL_ROUNDS` 真实限制工具轮次，到达上限后只允许基于已有 Observation 回答。
- 参数异常、重复调用、工具失败、多 Tool Call 和客户端取消均采用安全错误路径。
- PLAN、TOOL、OBSERVATION 能进入现有执行记录时间线。
- SSE/前端支持同一 Run 的多个工具进度与 `tool_failed` 安全降级。
- DeepSeek 关闭时多轮 Agent 服务不会破坏应用启动。

## 自动化结果

```text
INSIGHTOPS_CHAIN_GATE=true mvn -q verify
Tests: 192 passed, 0 failures, 0 errors, 0 skipped
Flyway: V1-V24 在临时 PostgreSQL Schema 完整执行

npm run test -- --run
Test Files: 14 passed
Tests: 31 passed

npm run lint
passed

npm run build
vue-tsc + Vite production build passed

docker compose --env-file .env.prod.example -f infra/compose.prod.yml build server worker web
server: built
worker: built
web: built
```

## 新增专项测试

- `ModelUsageTest`：已知/未知字段相加与溢出保护。
- `SpringAiDeepSeekPlanningModelGatewayTest`：原生 Function Call、模型配置、历史 Observation 回放。
- `AgentLoopServiceTest`：规划、执行、观察、停止、证据/引用和 Token 汇总；最大轮次上限。
- `agentStream.p2b.test.ts`：标准化 `tool_failed` 事件与错误信息不泄漏。

## 部署边界

本记录只关闭本地 P2.0-B 代码和构建验收。生产推送、CI 镜像发布、服务器更新、真实 DeepSeek 多轮调用和生产回归必须在用户再次授权部署后执行，不能把本地镜像构建视为生产完成。
