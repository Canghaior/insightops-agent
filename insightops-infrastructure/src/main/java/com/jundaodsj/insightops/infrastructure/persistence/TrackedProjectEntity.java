package com.jundaodsj.insightops.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tracked_project")
public class TrackedProjectEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, length = 32)
    private String platform;

    @Column(name = "repository_owner", nullable = false, length = 128)
    private String repositoryOwner;

    @Column(name = "repository_name", nullable = false, length = 128)
    private String repositoryName;

    @Column(name = "canonical_url", nullable = false, length = 512)
    private String canonicalUrl;

    @Column(nullable = false)
    private short priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrackedProjectEntity() {
    }

    public static TrackedProjectEntity create(
            UUID workspaceId,
            String owner,
            String repository,
            String canonicalUrl,
            short priority) {
        TrackedProjectEntity entity = new TrackedProjectEntity();
        entity.id = UUID.randomUUID();
        entity.workspaceId = workspaceId;
        entity.platform = "github";
        entity.repositoryOwner = owner;
        entity.repositoryName = repository;
        entity.canonicalUrl = canonicalUrl;
        entity.priority = priority;
        entity.enabled = true;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public String getRepositoryOwner() {
        return repositoryOwner;
    }

    public String getRepositoryName() {
        return repositoryName;
    }
}
