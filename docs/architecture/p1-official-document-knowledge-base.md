# P1.4-A 官方文档知识库基础

更新时间：2026-08-17

## 目标与边界

本阶段把 Spring AI、LangChain4j、Dify 官方文档转成可审计的原文、修订和文本切片。它不生成 Embedding、不执行向量检索、不把切片注入研究问答，也不调用 DeepSeek。

## 数据流

```text
SYSTEM_ADMIN 手动触发
  -> knowledge_source 到期队列
  -> Worker 领取并加租约
  -> HTTPS 白名单 + DNS/重定向/robots.txt 校验
  -> 有界抓取与正文清洗
  -> 标题感知切片 + SHA-256
  -> knowledge_document / knowledge_revision / knowledge_chunk
  -> knowledge_collection_job 审计与退避重试
```

## 数据模型

- `knowledge_source`：来源、允许域名与路径、可信等级、调度状态。
- `knowledge_document`：规范 URL、标题、语言、版本与当前修订。
- `knowledge_revision`：不可变原文及内容指纹；相同指纹不会重复写入。
- `knowledge_chunk`：标题路径、顺序、原文、估算 Token 和审计元数据。
- `knowledge_collection_job`：每次任务的页数、新增/变化/未变化数量、错误和耗时。
- `retrieval_trace`：为后续检索审计预留；P1.4-A 不产生记录。

向量列不放在 V12 中。P1.4-B 选定 Embedding 模型并固定维度后，再通过新迁移建立向量索引，避免模型未定时锁死 Schema。

## 安全与运行约束

- 只接受预先登记的 HTTPS host/path，拒绝 userinfo、自定义端口和非公网 DNS 地址。
- 每次重定向重新校验边界；页面内越界链接直接忽略。
- 读取 robots.txt；限制重定向、响应字节、页数、深度、超时与请求间隔。
- 默认 `DOCUMENT_COLLECTION_ENABLED=false`，三个来源首次调度时间设为 2999 年，必须管理员明确排队。
- 错误信息只保存分类和截断后的消息，不保存响应正文、Cookie、认证头或环境变量。

## 管理入口

- 页面：`/admin/knowledge`，仅 `SYSTEM_ADMIN` 可见。
- `GET /api/v1/admin/knowledge/sources`
- `POST /api/v1/admin/knowledge/sources/{sourceId}/sync`

页面显示文档、修订、切片、上次任务、失败原因和下次执行时间。
