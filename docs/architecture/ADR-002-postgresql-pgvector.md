# ADR-002：使用 PostgreSQL 18 与 pgvector

> 状态：已接受，等待运行 Spike
> 日期：2026-08-16

## 背景

项目需要保存用户与工作区、项目配置、原始快照、情报事件、会话、Agent Run、Tool Call、任务和后续文档向量。P0 规模不需要同时维护 MySQL、Redis、Kafka 和独立向量数据库。

## 决策

- 主数据库使用 PostgreSQL 18 当前小版本。
- 向量扩展使用 pgvector 0.8.5。
- 本地通过 `pgvector/pgvector:0.8.5-pg18` 启动。
- 数据库结构统一由 Flyway 管理。
- 普通 CRUD 使用 JPA。
- 任务领取、锁、批量写入、统计和向量查询使用 JdbcClient/显式 SQL。
- P0 不启用 Embedding，不创建固定维度向量列。

## 为什么不立即创建向量表

向量列维度取决于尚未选择的 Embedding 模型。先创建错误维度会导致后续全量迁移和重新索引。因此 P0 只启用 `vector` 扩展，完整 RAG 阶段在模型评测后新增迁移。

## 数据一致性原则

- 数据库是任务、会话、事件和执行状态的事实源。
- 业务表使用 UUID 主键。
- 时间字段使用 `timestamptz`。
- 扩展数据使用 `jsonb`，稳定字段不得长期埋在 JSON 中。
- 项目、事件和任务使用业务唯一键保证幂等。
- 删除与状态变化必须有明确事务边界。

## 备选方案

- MySQL + Milvus：组件更多，首版没有必要。
- PostgreSQL + Elasticsearch：全文检索能力更强，但当前规模不足以抵消运维成本。
- 云向量数据库：需要额外账号、费用和数据边界，Alpha 前暂不采用。

## 验收

- Compose 健康检查通过。
- Flyway 可在空库完整执行。
- `vector` 扩展存在。
- P0 表、索引和约束可重复创建。
- Testcontainers 迁移测试通过。
