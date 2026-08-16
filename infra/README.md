# 本地基础设施

P0 只启动 PostgreSQL 18 和 pgvector，不引入 Redis、Kafka、Milvus 或 Kubernetes。
本项目默认映射到宿主机 `55432` 端口，避免与本机已有 PostgreSQL 的 `5432` 冲突。

## 启动

在项目根目录执行：

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d
```

如果还没有 `.env`，先复制 `.env.example`，再只在本机 `.env` 中填写 API Key：

```powershell
Copy-Item .env.example .env
```

## 检查

```powershell
docker compose --env-file .env -f infra/compose.yaml ps
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U insightops -d insightops -c "select extversion from pg_extension where extname = 'vector';"
```

数据库初始化由后端启动时的 Flyway 完成，不在 Compose 中复制 SQL。
