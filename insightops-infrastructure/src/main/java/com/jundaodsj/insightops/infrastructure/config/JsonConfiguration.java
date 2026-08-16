package com.jundaodsj.insightops.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper insightOpsObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
