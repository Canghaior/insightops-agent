# P2.3-C 普通聊天可靠性 SLO 验收记录（2026-08-22）

## 1. 实现范围

- PostgreSQL 队列 SLO 聚合快照。
- 接管延迟 Timer、队列 Gauge、SSE 重连/回放计数。
- Prometheus 八条可靠性告警和 Grafana 四个新面板。
- 带强确认、前置安全点校验、旧 token 与成本账本判据的生产接管演练脚本。
- 真实 PostgreSQL 接管门禁补充队列状态与一秒接管延迟断言。

## 2. 自动化门禁

| 门禁 | 结果 |
|---|---|
| P2.3-C 指标单元测试 | PASS：队列 Gauge、接管 Timer 和采样异常计数 |
| P2.3-C 监控契约测试 | PASS：八条告警、四个面板和强确认演练边界 |
| PostgreSQL 迁移/接管/fencing | PASS：PostgreSQL 18.4、Flyway 34/34、排队/运行/过期快照、1 秒接管延迟、安全点恢复、旧 token 禁写 |
| 后端全仓 | PASS：Surefire 250/250，Failures 0、Errors 0、Skipped 0 |
| 前端 lint/test/build | PASS：ESLint；Vitest 20 文件 48/48；`vue-tsc` 与 Vite build |
| Prometheus/Grafana 配置 | PASS：Prometheus 3.7.3 `promtool` 识别 16 条规则；Grafana JSON 12 面板、版本 2 |
| 演练脚本语法 | PASS：Git Bash `bash -n` |

后端全仓以 `INSIGHTOPS_CHAIN_GATE=true mvn -q verify` 执行，所有临时 Schema 在测试结束后自动清理。Grafana JSON 另经 PowerShell JSON 解析验证。

## 3. 生产发布与演练

- Git 提交：`501849eef423da7111392b765e342c7c7f730fc3`。
- CI：GitHub Actions Run `32581592908`，后端、前端及 Server/Worker/Web 三个不可变镜像任务全部成功。
- 不可变镜像部署：GitHub Actions Run `32581830244` 成功，目标镜像 Tag 为完整提交 SHA；PostgreSQL、Ollama、Server、Worker、Web、Caddy 六服务健康门禁通过。
- 公网健康：`https://insightops.canghaior.com/` 返回 `200`；未登录访问 `/api/v1/auth/me` 返回 `401`，鉴权边界保持有效。
- Actuator 未通过公网 Caddy 暴露；后端健康以部署脚本的容器内健康检查为准。
- 生产 `SIGKILL` 接管演练：必须由用户对具体非关键 Run 单独确认后执行；实现和部署授权不等于生产故障注入授权。

## 4. 验收口径

代码、配置和自动化测试全部通过后，P2.3-C 可标记为“已实现并部署”；只有在用户明确选择具体测试 Run 并授权短暂 Server 故障后，才能进一步标记“真实生产故障注入闭环已关闭”。
