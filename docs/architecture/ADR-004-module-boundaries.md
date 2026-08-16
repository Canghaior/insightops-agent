# ADR-004：后端模块边界

> 状态：已接受
> 日期：2026-08-16

## Maven模块

### insightops-core

允许：

- Java标准库。
- 领域实体、值对象、枚举和状态机。
- 应用端口与用例接口。
- 与框架无关的业务测试。

禁止：

- Spring组件注解。
- JPA注解。
- HTTP客户端。
- DeepSeek、GitHub、PostgreSQL和文件系统SDK。

### insightops-infrastructure

负责：

- JPA与JdbcClient。
- Flyway迁移。
- DeepSeek模型适配。
- GitHub REST适配。
- 本地文件存储。
- PostgreSQL任务锁和执行记录。
- Provider配置与健康状态。

### insightops-server

负责：

- REST与SSE入口。
- 请求校验和响应协议。
- TraceId和HTTP日志。
- 用户请求取消信号。
- Actuator和系统状态接口。

Controller不得直接访问JPA Repository或第三方Provider。

### insightops-worker

负责：

- 领取到期任务。
- GitHub采集。
- 后续解析、Embedding、事件和报告任务。
- 心跳、超时恢复、重试和失败终止。

Worker不得绕过状态机直接篡改执行终态。

## Core业务包

```text
foundation  错误、Trace、通用值对象
identity    用户、工作区和授权边界
model       Chat、Embedding、模型调用端口
agent       Agent Run、Step、预算和状态机
conversation 会话、消息和上下文
knowledge   数据源、快照、文档和检索
research    跟踪项目、事件、证据和报告
tool        工具定义、调用和策略
job         任务、领取、重试和恢复
audit       模型、检索、工具和操作审计
```

## 依赖规则

1. 业务包之间通过公开用例或领域事件协作。
2. `foundation` 不依赖其他业务包。
3. `agent` 可依赖 `model`、`tool`、`conversation` 的端口，不能依赖其基础设施实现。
4. `research` 可引用 `knowledge` 的文档/证据标识，不直接调用向量数据库。
5. `job` 只编排任务状态，不包含具体GitHub或模型SDK代码。
6. `audit` 接收审计事件，不反向控制业务结果。

## 验收

- Maven依赖只沿 `server/worker → infrastructure → core`。
- Core构建文件不包含Spring依赖。
- 模块边界测试在CI执行。
- 新Provider通过Adapter接入，不修改核心领域类型。
