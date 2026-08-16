# ADR-001：首版采用模块化单体

> 状态：已接受
> 日期：2026-08-16

## 背景

InsightOps Agent 需要同时承载 HTTP/SSE、Agent 编排、GitHub 采集、知识处理、报告、任务和审计。旧项目的主要问题之一是过早拆分微服务后出现契约漂移、重复基础设施和本地环境难以复现。

## 决策

首版采用一个 Git 仓库、四个后端 Maven 模块、一个 Vue 前端和一个 PostgreSQL 实例：

```text
insightops-core
insightops-infrastructure
insightops-server
insightops-worker
web
```

Server 与 Worker 是两个可独立启动的进程，但共享 Core、Infrastructure、数据库迁移和领域契约。

## 依赖方向

```text
server ───────┐
              ├──> infrastructure ───> core
worker ───────┘
```

- `core` 不依赖 Spring Boot、数据库、DeepSeek、GitHub 或 Web。
- `infrastructure` 实现 `core` 定义的端口。
- `server` 只负责外部请求、SSE、校验和协议适配。
- `worker` 只负责异步任务领取与执行。
- Server 与 Worker 之间不通过 HTTP 互相调用。

## 暂不引入

- Nacos
- Spring Cloud Gateway
- OpenFeign
- Kafka
- Milvus
- Elasticsearch
- XXL-Job
- Kubernetes

只有当单体边界已经稳定并出现可测量的独立扩缩容需求时，才评估拆分服务。

## 后果

优点：本地启动简单、事务边界清晰、契约集中、测试容易、发布成本低。

代价：需要严格维护包与模块边界；Server 和 Worker 共用数据库时必须通过任务锁、幂等和状态机避免重复执行。

## 验收

- Maven 构建能验证依赖方向。
- Core 中不存在 Spring 和第三方 Provider 类型。
- 一个命令可构建全部后端模块。
- Server 与 Worker 可分别启动。
