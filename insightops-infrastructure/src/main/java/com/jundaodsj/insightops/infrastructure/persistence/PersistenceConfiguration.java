package com.jundaodsj.insightops.infrastructure.persistence;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = TrackedProjectEntity.class)
@EnableJpaRepositories(basePackageClasses = TrackedProjectJpaRepository.class)
public class PersistenceConfiguration {
}
