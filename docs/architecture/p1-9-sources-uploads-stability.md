# P1.9 多来源、用户资料与生产稳定性

## 目标与边界

P1.9 把技术情报来源从 GitHub Release、Issue、Pull Request、Security Advisory 和官方文档扩展到官方博客 RSS、Roadmap/Milestone 与用户上传资料，并把默认真实项目扩充到 10 个。该阶段仍保持只读研究边界：外部来源只采集公开官方数据；用户文件只参与其授权范围内的 RAG，不执行文件中的指令。

P1.9 分为三部分：

- P1.9-A：官方博客 RSS、GitHub Roadmap/Milestone 与 10 个真实项目持续采集。
- P1.9-B：Markdown、纯文本和 PDF 上传、解析、Embedding、权限隔离与可追溯引用。
- P1.9-C：采集指标、告警、上传文件备份恢复和生产运行手册。

## P1.9-A：官方更新与项目扩容

### 来源模型

`knowledge_source.source_type` 新增 `OFFICIAL_BLOG_RSS`、`OFFICIAL_ROADMAP` 和 `USER_UPLOAD`。RSS 与 Roadmap 仍复用知识采集任务、租约、进度、修订和切片管线，因此具备相同的去重、失败重试、心跳和 fencing token 保护。

- Spring 官方博客使用 `https://spring.io/blog.atom`，支持 Atom/RSS 1.0/RSS 2.0。
- Spring AI Roadmap 使用 GitHub Milestones API；网关只允许当前项目仓库对应的 API 路径。
- HTTP 响应的 `ETag` 与 `Last-Modified` 保存到来源，后续发送条件请求；`304` 作为成功的无变化同步。
- RSS/里程碑条目的官方发布时间保存到 `source_snapshot.published_at` 和 `intelligence_event.occurred_at`，重新采集不会把事件时间改成采集时间。
- 事件统一为 `OFFICIAL_BLOG` 或 `ROADMAP`，继续进入证据、关注规则、通知、摘要和问答链路。

默认项目由 3 个扩充到 10 个：Spring AI、LangChain4j、Dify、Spring AI Alibaba、Quarkus LangChain4j、MCP Java SDK、OpenAI Java、Anthropic Java SDK、Google Gen AI Java 和 Ollama。新增项目按 5～65 分钟错峰首次采集，后续按各自 12/24 小时周期运行，避免同时冲击 GitHub API。生产应配置 `GITHUB_TOKEN` 以提高 API 限额。

## P1.9-B：用户上传知识

### 安全与配额

- 接受 `.md`、`.txt` 和 `.pdf`；默认单文件上限 20 MiB、单 Workspace 1 GiB、PDF 最多 500 页。
- 文件名只作为显示元数据；存储键由服务端 UUID 生成，不使用用户路径。
- PDF 同时校验扩展名、媒体类型和 `%PDF-` 文件签名。
- 文件先写入临时文件，计算 SHA-256 后原子移动到专用卷；数据库创建失败时回收已写入文件。
- 下载使用登录接口 `/api/v1/knowledge/uploads/{uploadId}/content`，不暴露本地存储路径。
- 删除仅允许文件所有者或系统管理员，先删除数据库中的来源、文档、切片、Embedding 和上传记录，再删除存储对象。

### 权限与检索

每个上传文件选择一个项目和一种可见性：

- `PRIVATE`：仅上传者和系统管理员可见。
- `WORKSPACE`：同 Workspace 登录用户可见。

可见性在 SQL 检索层过滤，不依赖 Prompt。普通用户的关键词、向量和混合检索都只能召回 Workspace 共享文件或自己的私有文件；系统管理员在当前 Workspace 可审计全部文件。上传引用使用受认证的相对 URL，并保留 PDF 页码片段，例如 `/api/v1/knowledge/uploads/<uuid>/content#page=2`。

Worker 通过 Apache PDFBox 提取 PDF 分页文本，通过 UTF-8 解码 Markdown/文本。页面继续使用统一切片和 Embedding 管线，采集状态在“我的资料”页面实时轮询展示，包含当前文件、页数进度、心跳、错误和重试入口。

## P1.9-C：指标、告警与备份

Server/Worker 暴露 Micrometer/Prometheus 指标；P1.9 增加以下运维指标：

- 过期的知识采集租约数。
- 待处理的 Embedding 切片数。
- 失败的用户上传任务数。
- 采集完成计数，按结果和来源类型区分。

Prometheus 规则覆盖 Server/Worker 不可用、租约过期、Embedding 积压、上传失败和 HTTP 5xx 比例。Prometheus 与 Grafana 仅绑定服务器回环地址，通过 Compose `observability` profile 启用，不直接暴露公网。

备份由单一时间戳的三件套组成：

```text
insightops-<UTC时间>.dump
insightops-<UTC时间>.uploads.tar.gz
insightops-<UTC时间>.sha256
```

脚本短暂停止 Server/Worker，取得数据库和上传卷的一致快照，并在完成或失败时恢复原运行状态。恢复必须同时提供数据库、上传归档和校验清单，并显式传入破坏性恢复确认参数。

## 验证门禁

- Atom/RSS 解析、条件请求、GitHub Milestone 边界和外部事件转换单元测试。
- 本地文件存储路径边界、PDF/Markdown/文本解析、上传校验、配额、失败清理和权限测试。
- PostgreSQL V1～V24 全量迁移门禁，覆盖 10 项目、两类官方更新来源、上传隔离/删除和官方发布时间。
- RAG 测试覆盖动态项目别名、用户上传可回答性、认证下载引用和 Guardrail 来源校验。
- 前端 API、lint、单元测试和生产构建。
- Compose、Caddy、Prometheus 与备份/恢复脚本静态验证；部署后再完成真实来源、上传、指标、备份和公网回归。
