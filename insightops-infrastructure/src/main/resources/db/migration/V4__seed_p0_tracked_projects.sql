INSERT INTO tracked_project (
    id, workspace_id, platform, repository_owner, repository_name,
    canonical_url, priority, enabled
)
VALUES
    (
        '00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000001',
        'github', 'spring-projects', 'spring-ai',
        'https://github.com/spring-projects/spring-ai', 1, TRUE
    ),
    (
        '00000000-0000-0000-0000-000000000102',
        '00000000-0000-0000-0000-000000000001',
        'github', 'langchain4j', 'langchain4j',
        'https://github.com/langchain4j/langchain4j', 2, TRUE
    ),
    (
        '00000000-0000-0000-0000-000000000103',
        '00000000-0000-0000-0000-000000000001',
        'github', 'langgenius', 'dify',
        'https://github.com/langgenius/dify', 3, TRUE
    )
ON CONFLICT (workspace_id, platform, repository_owner, repository_name) DO NOTHING;
