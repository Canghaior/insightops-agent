package com.jundaodsj.insightops.server.localexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.infrastructure.model.DeepSeekCostEstimator;
import com.jundaodsj.insightops.model.application.AgentPlanningModelGateway;
import com.jundaodsj.insightops.server.businesssystem.EngineeringBaselineService;
import com.jundaodsj.insightops.server.report.BuildEvidenceService;
import com.jundaodsj.insightops.server.report.EngineeringTaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "insightops.local-execution", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(LocalExecutionProperties.class)
class LocalExecutionConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    LocalEngineeringRuns localEngineeringRuns(LocalExecutionProperties properties, JdbcClient jdbc, ObjectMapper json,
            EngineeringTaskService tasks, BuildEvidenceService builds, EngineeringBaselineService baselines,
            ResearchModelCatalog models, AgentPlanningModelGateway gateway, DeepSeekCostEstimator estimator,
            DataSource dataSource, PlatformTransactionManager transactions,
            @Value("${server.address:127.0.0.1}") String address,
            @Value("${insightops.auth.enabled:true}") boolean authenticated) throws IOException {
        if (!authenticated || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost").contains(address)) {
            throw new IllegalArgumentException("本地工程执行须通过启用认证的本机回环地址运行。");
        }
        return new LocalEngineeringRuns(properties, new LocalRunStore(jdbc, json), new LocalBuildEvidence(builds, tasks, json),
                tasks, baselines, models, gateway, estimator, json, new TransactionTemplate(transactions), dataSource);
    }
}
