INSERT INTO knowledge_source (
    id, workspace_id, project_id, source_key, name, source_type,
    root_url, discovery_url, allowed_host, allowed_path_prefix,
    trust_tier, sync_interval_hours, enabled, next_sync_at
)
VALUES
    ('00000000-0000-0000-0000-000000000404', '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000101', 'spring-official-blog-rss', 'Spring Official Blog',
     'OFFICIAL_BLOG_RSS', 'https://spring.io/blog/', 'https://spring.io/blog.atom',
     'spring.io', '/blog/', 'T1_PROJECT_DOMAIN', 6, TRUE, now()),
    ('00000000-0000-0000-0000-000000000405', '00000000-0000-0000-0000-000000000001',
     '00000000-0000-0000-0000-000000000101', 'spring-ai-github-roadmap', 'Spring AI GitHub Roadmap',
     'OFFICIAL_ROADMAP', 'https://api.github.com/repos/spring-projects/spring-ai/milestones',
     'https://api.github.com/repos/spring-projects/spring-ai/milestones', 'api.github.com',
     '/repos/spring-projects/spring-ai/milestones', 'T1_PROJECT_DOMAIN', 6, TRUE, now())
ON CONFLICT (workspace_id, source_key) DO NOTHING;
