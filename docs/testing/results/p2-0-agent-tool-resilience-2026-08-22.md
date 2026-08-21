# P2.0-C Agent 工具可靠性验收

日期：2026-08-22

## 范围

- P2.0-C1：Registry timeout 强制截止、用户取消、Run 时间/尝试/累计工具耗时预算。
- P2.0-C2：错误分类、有限重试、指数退避、上游分组熔断和半开恢复。
- P2.0-C3：尝试级数据库审计、Run 详情、SSE 重试状态、前端时间线、指标、仪表盘和告警。

## 自动化结果

| 验证 | 结果 |
|---|---|
| `mvn -q test -DskipITs` | PASS |
| `INSIGHTOPS_CHAIN_GATE=true mvn -q verify` | PASS：199 项测试、0 失败；数据库门禁 13 项；Flyway V1-V25 全量迁移通过 |
| P2.0-C 定向测试 | PASS：瞬时错误重试、永久错误不重试、硬超时、取消、熔断开路和永久错误忽略 |
| `npm run test -- --run` | PASS：14 个测试文件、32 项测试 |
| `npm run lint` | PASS |
| `npm run build` | PASS |
| `docker compose --env-file .env.prod.example -f infra/compose.prod.yml config` | PASS |
| Grafana Dashboard JSON 解析 | PASS |
| Prometheus `promtool check rules` | PASS：8 条规则 |

## 数据库与状态验收

- V25 新增 `tool_call_attempt`，并为 `(tool_call_id, attempt_no)` 建立唯一约束。
- 尝试支持 `RUNNING/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED/CIRCUIT_OPEN`。
- 逻辑 Tool Call 支持 `TIMED_OUT/CANCELLED`，终态更新带 `status = 'RUNNING'` 条件，迟到 Worker 不能覆盖终态。
- 真实 PostgreSQL 门禁完成一次 Tool Call 和 Attempt 的写入、结束、查询与嵌套返回。

## 可靠性验收

- 可重试瞬时错误在同一个逻辑 Tool Call 内重新执行，不新建第二个逻辑调用。
- 永久性错误不重试，也不计入熔断失败率。
- Registry timeout 到期会取消执行并保存 `TOOL_TIMEOUT/TIMED_OUT`。
- 用户取消在进入工具、等待结果和退避期间都能终止，并保存 `TOOL_CANCELLED/CANCELLED`。
- 单 Run 的截止时间、总尝试次数和累计工具耗时共同限制执行，耗尽后返回 `AGENT_RUN_BUDGET_EXHAUSTED` 或 `TOOL_RUN_BUDGET_EXHAUSTED`。
- 熔断按 GitHub、知识检索和数据库事件三个上游组隔离，支持 CLOSED、OPEN、HALF_OPEN。

## 可观测性与安全验收

- Run 详情返回每个 Tool Call 的 Attempt 列表，前端展示状态、耗时、错误码和退避。
- SSE `tool_retrying` 仅包含安全进度与标准错误码，不包含工具参数、异常消息、令牌或上游响应体。
- Prometheus 覆盖逻辑调用、尝试、重试、超时、耗时、熔断拒绝和熔断状态。
- Grafana 增加结果、P95、重试/超时与熔断面板；Prometheus 增加高重试率、超时突增和持续开路告警。

## 生产验收

生产提交、CI、部署版本、容器健康、V25、指标端点和真实问答将在推送部署后补录；自动化与本地数据库门禁通过之前不进入部署。
