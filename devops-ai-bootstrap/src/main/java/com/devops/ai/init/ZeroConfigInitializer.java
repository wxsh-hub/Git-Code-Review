package com.devops.ai.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.HashMap;
import java.util.Map;

public class ZeroConfigInitializer implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ZeroConfigInitializer.class);

    private static final String PROPERTY_SOURCE_NAME = "zeroConfigDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();

        if (environment.getProperty("spring.datasource.url") == null) {
            defaults.put("spring.datasource.url",
                    "jdbc:h2:file:./data/devops-ai;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            defaults.put("spring.datasource.driver-class-name", "org.h2.Driver");
            defaults.put("spring.datasource.username", "sa");
            defaults.put("spring.datasource.password", "");
            defaults.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
            defaults.put("spring.h2.console.enabled", "true");
            log.info("Zero-config: Using H2 embedded database");
        }

        if (environment.getProperty("server.port") == null) {
            defaults.put("server.port", "8080");
        }

        if (environment.getProperty("devops.ai.encrypt.key") == null) {
            defaults.put("devops.ai.encrypt.key", "devops-ai-zero-config-key-" + System.currentTimeMillis());
        }

        if (environment.getProperty("devops.ai.generator.output-dir") == null) {
            defaults.put("devops.ai.generator.output-dir", "./output");
        }

        if (!defaults.isEmpty()) {
            MutablePropertySources propertySources = environment.getPropertySources();
            MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, defaults);
            propertySources.addFirst(propertySource);
            log.info("Zero-config: Applied {} default configuration values", defaults.size());
        }
    }
}
