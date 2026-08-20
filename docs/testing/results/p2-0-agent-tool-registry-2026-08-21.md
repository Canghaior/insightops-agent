# P2.0-A Agent Tool Registry 验证记录

日期：2026-08-21

## 结论

P2.0-A 本地实现与完整回归通过。三类现有生产工具已统一注册，并通过统一入口执行启用状态、权限、输入/输出 Schema、审计结果上限、幂等键和完成状态校验。当前固定编排行为未改变，模型 Function Calling 和多轮工具循环不计入本次完成范围。

## 实现验收

| 检查项 | 结果 |
|---|---|
| Registry 重复、未知、禁用和权限过滤 | PASS |
| 封闭输入/输出 JSON Schema | PASS |
| 参数必填、类型、范围和未知字段拒绝 | PASS |
| 写入型工具必须声明人工审批 | PASS |
| 供应商无关 Function Schema | PASS |
| `GET /api/v1/agent/tools` 登录角色目录 | PASS |
| 审计 Step、Tool Call、耗时和幂等键 | PASS |
| 结果大小、输出合同和重复完成保护 | PASS |
| Release 工具迁移 | PASS |
| RAG 工具迁移与不可用降级 | PASS |
| 项目事件工具迁移与真实审计用例 | PASS |
| 生产数据库门禁装配 | PASS |

输出合同门禁首次启用时发现 Release `fetchedAt` 受 ObjectMapper 配置影响可能序列化为数字时间戳。实现已改为显式 ISO-8601 字符串，并在修复后重跑全部门禁通过。

## 自动化验证

| 命令 | 结果 |
|---|---|
| `INSIGHTOPS_CHAIN_GATE=true mvn verify` | PASS：186 个测试，0 失败；Server 数据库门禁 13 条；Flyway V1-V24 全量迁移通过 |
| `npm run lint` | PASS：0 错误 |
| `npm run test` | PASS：13 个测试文件、30 个测试 |
| `npm run build` | PASS：TypeScript 与 Vite 生产构建完成 |
| `docker compose --env-file .env.prod.example -f infra/compose.prod.yml build server worker web` | PASS：三项生产镜像均构建完成 |
| `git diff --check` | PASS |

## 生产与阶段边界

- 本次未新增数据库迁移，继续使用 Flyway V24。
- 本次尚未推送、部署或修改生产环境。
- Registry 的建议超时已登记，但强制中断、统一重试和熔断属于 P2.0-C。
- Planner、Executor、Observation、Function Calling 和多轮 Plan-Act-Observe 属于下一阶段 P2.0-B。
- `OrcaTerm/` 为用户文件，未纳入改动、提交或清理。
