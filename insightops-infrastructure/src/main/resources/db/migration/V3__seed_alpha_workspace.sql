INSERT INTO workspace (id, name, slug, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'InsightOps Alpha',
    'insightops-alpha',
    'ACTIVE'
)
ON CONFLICT (slug) DO NOTHING;
