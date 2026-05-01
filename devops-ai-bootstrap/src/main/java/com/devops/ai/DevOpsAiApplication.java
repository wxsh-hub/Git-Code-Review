package com.devops.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EntityScan(basePackages = "com.devops.ai.infrastructure.entity")
@EnableJpaRepositories(basePackages = "com.devops.ai.infrastructure.repository")
@EnableAsync
public class DevOpsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevOpsAiApplication.class, args);
    }
}
