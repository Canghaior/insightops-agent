package com.jundaodsj.insightops.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class SnapshotJdbcRepository {

    private final JdbcClient jdbcClient;

    public SnapshotJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long countByProjectId(UUID projectId) {
        return jdbcClient.sql("select count(*) from source_snapshot where project_id = :projectId")
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }
}
