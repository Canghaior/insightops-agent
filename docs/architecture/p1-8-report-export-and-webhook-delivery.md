# P1.8 报告导出与 Webhook 交付

## 目标与边界

P1.8 把已经完成的技术情报分析固化为用户可复用、可下载、可审计的报告，并提供第一种站外交付渠道。当前支持专题报告、Markdown、PDF 和通用 HTTPS Webhook；日报/月报自动生成、邮件、团队分享链接仍属于后续阶段。

## 不可变报告快照

- 报告只选择当前用户已关注项目中状态为 `SUCCEEDED` 的分析，并按 Workspace、周期、项目和事件类型过滤。
- 每次生成最多保存 100 条情报；周期最长 366 天。
- `research_report` 同时保存筛选条件、结构化情报 JSON 快照和生成后的 Markdown。后续分析变化不会改写历史报告。
- Markdown 和 PDF 都从同一份快照导出，因此条目、风险数和证据链接保持一致。
- PDF 使用嵌入式 Noto CJK 字体，包含标题、周期、风险指标、逐项证据和页码；生产镜像显式安装字体，避免依赖客户端字体替换。

## Webhook 安全与可靠性

- 渠道仅接受公网 HTTPS URL、默认端口或 443；禁止凭据、Fragment、localhost 和解析到回环、链路本地、私网、组播、IPv6 ULA 或 CGNAT 的地址。
- HTTP 客户端禁止重定向，避免通过 3xx 绕过目标校验。
- 完整 URL 使用 AES-256-GCM 加密保存；列表、报告投递任务和页面只显示 `https://host/***`。解密只发生在 Worker 已领取任务之后。
- 每个“报告 + 渠道”只有一条投递任务。重复提交返回原任务；只有最终失败任务可以显式重新入队。
- Worker 使用 `FOR UPDATE SKIP LOCKED`、租约令牌和过期接管并发领取任务。每次请求携带投递 ID 作为 `Idempotency-Key`。
- 2xx 视为成功；408、425、429 和 5xx 按 5、10、20 分钟指数退避重试，其余 4xx 直接失败。默认共尝试 3 次。
- 投递记录保存状态、尝试次数、HTTP 状态、耗时、脱敏错误、下次尝试时间和送达时间。删除渠道采用软删除，历史审计仍然保留。

生产必须配置稳定的 `DELIVERY_ENCRYPTION_KEY`。为了兼容首次部署，Compose 在未单独提供时使用现有数据库密码派生密钥；密钥变化后，已有渠道需要重新配置。

## API 与页面

```text
GET  /api/v1/reports
POST /api/v1/reports
GET  /api/v1/reports/{reportId}/export.md
GET  /api/v1/reports/{reportId}/export.pdf

GET/POST /api/v1/delivery-channels
PUT/DELETE /api/v1/delivery-channels/{channelId}
POST /api/v1/reports/{reportId}/deliveries
GET  /api/v1/report-deliveries
POST /api/v1/report-deliveries/{deliveryId}/retry
```

所有接口都要求登录，并使用当前用户和 Workspace 双重边界。`/reports` 页面提供报告生成、快照预览、Markdown/PDF 下载、Webhook 渠道管理和投递审计。

## 验证

- 单元测试覆盖报告参数/快照、AES-GCM 随机加密与篡改检测、Webhook URL 安全策略、投递成功、重试和终止。
- PostgreSQL 门禁覆盖 V20 迁移、用户隔离、报告持久化、渠道名称并发边界、敏感 URL 掩码、入队幂等、租约领取、失败重试、成功审计和渠道软删除。
- PDF 门禁生成 8 页中文样本，验证 PDF 元数据、页数、嵌入字体、中文文本可提取，并将首、中、末页渲染为 PNG 做视觉检查。
- 前端测试覆盖报告创建、Webhook 写入不回显密钥、投递入队和二进制下载；完整 lint、测试与生产构建必须通过。
