package com.jundaodsj.insightops.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TrackedProjectJpaRepository extends JpaRepository<TrackedProjectEntity, UUID> {

    Optional<TrackedProjectEntity> findByRepositoryOwnerAndRepositoryName(String owner, String repository);
}
