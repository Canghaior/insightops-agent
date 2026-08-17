# P1.4-A 官方文档采集验证结果

日期：2026-08-17

## 自动验证

- Java 单元测试：采集边界、robots.txt 规则、标题感知切片、稳定哈希、Worker 成功/失败调度、管理员权限。
- PostgreSQL 隔离门禁：V1-V12 全部迁移成功；首次保存 1 个文档及切片，再次提交相同内容只记为未变化，不重复创建修订与切片。
- 前端：知识库 API 测试、TypeScript 构建、Vitest 和 ESLint 均通过。

## 真实官方来源烟雾测试

测试对象：Spring AI Reference `https://docs.spring.io/spring-ai/reference/`

运行约束：

- `DOCUMENT_CRAWL_MAX_PAGES_PER_SOURCE=3`
- `DOCUMENT_CRAWL_MAX_DEPTH=1`
- `DOCUMENT_COLLECTION_BATCH_SIZE=1`
- 仅 Spring AI 来源被手动排队
- `INTELLIGENCE_ANALYSIS_ENABLED=false`

Worker 结果：

```text
claimed=1, succeeded=1, failed=0, pages=3, chunks=15
```

结论：V12 在本地 public Schema 成功应用；官方 HTTPS 采集、正文清洗、切片和 PostgreSQL 保存链路真实可运行。该测试没有调用 DeepSeek，没有生成 Embedding，也没有触发 LangChain4j 或 Dify 首次采集。

## UI 验证说明

前端生产构建和 API 单元测试已通过。本轮自动浏览器访问本机地址被桌面安全策略拦截，因此视觉验收留给本机已登录 Chrome：以系统管理员登录后打开“知识库采集”，应看到 Spring AI 为成功状态、3 个文档和 15 个切片；另外两个来源应显示等待管理员首次触发。
