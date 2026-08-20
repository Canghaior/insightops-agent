INSERT INTO tracked_project (
    id, workspace_id, platform, repository_owner, repository_name,
    canonical_url, priority, sync_interval_hours, chat_aliases,
    enabled, next_sync_at, created_at, updated_at
)
VALUES
    ('00000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000001',
     'github', 'alibaba', 'spring-ai-alibaba', 'https://github.com/alibaba/spring-ai-alibaba',
     2, 12, ARRAY['spring ai alibaba', 'spring-ai-alibaba', '阿里 spring ai'], TRUE, now() + interval '5 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000001',
     'github', 'quarkiverse', 'quarkus-langchain4j', 'https://github.com/quarkiverse/quarkus-langchain4j',
     2, 12, ARRAY['quarkus langchain4j', 'quarkus-langchain4j'], TRUE, now() + interval '15 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000106', '00000000-0000-0000-0000-000000000001',
     'github', 'modelcontextprotocol', 'java-sdk', 'https://github.com/modelcontextprotocol/java-sdk',
     2, 12, ARRAY['mcp java', 'mcp java sdk', 'model context protocol java'], TRUE, now() + interval '25 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000107', '00000000-0000-0000-0000-000000000001',
     'github', 'openai', 'openai-java', 'https://github.com/openai/openai-java',
     2, 12, ARRAY['openai java', 'openai-java'], TRUE, now() + interval '35 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000108', '00000000-0000-0000-0000-000000000001',
     'github', 'anthropics', 'anthropic-sdk-java', 'https://github.com/anthropics/anthropic-sdk-java',
     3, 24, ARRAY['anthropic java', 'claude java', 'anthropic-sdk-java'], TRUE, now() + interval '45 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000109', '00000000-0000-0000-0000-000000000001',
     'github', 'googleapis', 'java-genai', 'https://github.com/googleapis/java-genai',
     3, 24, ARRAY['google gen ai java', 'gemini java', 'java-genai'], TRUE, now() + interval '55 minutes', now(), now()),
    ('00000000-0000-0000-0000-000000000110', '00000000-0000-0000-0000-000000000001',
     'github', 'ollama', 'ollama', 'https://github.com/ollama/ollama',
     3, 24, ARRAY['ollama'], TRUE, now() + interval '65 minutes', now(), now())
ON CONFLICT (workspace_id, platform, repository_owner, repository_name) DO NOTHING;
