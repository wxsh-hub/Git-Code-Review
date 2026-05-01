package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {
    AiConfig findByConfigKey(String configKey);
}
