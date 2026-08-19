# P1.4 知识库采集进度、心跳与安全租约

## 目标

官方文档采集最多处理 200 个页面，单次任务可能持续数分钟。管理页面需要在任务结束前看到真实进度和当前 URL；Worker 也必须在长任务中续租，并防止已经失去租约的旧进程覆盖接管者结果。

## 进度模型

V16 为 `knowledge_collection_job` 增加以下运行态字段：

- `max_page_count`：本次采集允许保存的有效页面上限；
- `discovered_url_count`：边界校验后累计发现的唯一 URL 数；
- `visited_url_count`：已经开始访问的候选 URL 数；
- `page_count`：已经形成有效正文和切片的页面数，任务结束后仍表示最终页面数；
- `current_url`：最近正在处理或最后处理的规范 URL；
- `heartbeat_at`：最近一次进度心跳；
- `lease_expires_at`：该次心跳续租后的到期时间。

Gateway 在发现入口、访问页面前、页面完成和发现新链接后报告进度。管理页面继续使用 5 秒静默轮询，不引入新的公网长连接。

## 租约与 fencing token

`knowledge_source.lock_token` 保存当前持有租约的 `knowledge_collection_job.id`。领取、心跳和完成遵守以下规则：

1. 领取任务时在同一事务内写入 `RUNNING`、`lock_token`、`locked_until` 和初始任务心跳。
2. 心跳只允许更新状态仍为 `RUNNING`、token 匹配且尚未过期的来源；进度与来源租约在同一事务内更新。
3. 成功或失败提交先锁定来源行并再次校验 token 和到期时间。
4. 过期任务被重新领取时，旧任务标记为 `FAILED / LOCK_EXPIRED`，新任务获得新的 token。
5. 旧 Worker 后续的心跳、成功或失败提交均因 token 不匹配而回滚，不会写入文档或覆盖新任务状态。

当前单请求超时默认为 20 秒，默认租约为 10 分钟；采集器在每次网络请求前后上报，因此正常心跳间隔显著小于租约。进程卡死或网络调用违反超时边界时不应无限保活，租约会自然到期并允许新 Worker 接管。

## API 与页面

`GET /api/v1/admin/knowledge/sources` 的 `lastJob` 增加上述进度、心跳和租约字段，来源状态同时返回 `lockedUntil`。这些字段只对系统管理员开放。

页面显示已访问、已发现、有效页面、当前 URL、最近心跳和租约到期时间。自动刷新失败使用独立错误通道；后续刷新成功会清除旧提示，不会污染手动采集、Embedding 重试或 RAG 评测错误。

## 验证

- `OfficialDocumentHttpGatewayTest` 验证当前 URL 与单调进度事件；
- `KnowledgeCollectionRunnerTest` 验证进度续租与租约丢失路径；
- `P0ChainDatabaseGateTest` 在 PostgreSQL/pgvector 上验证 V1～V16 迁移、进度持久化、锁过期接管和旧 Worker 写回拦截；
- 前端 Vitest 验证旧自动刷新错误在恢复后清除，以及自动刷新错误不会覆盖操作错误。
