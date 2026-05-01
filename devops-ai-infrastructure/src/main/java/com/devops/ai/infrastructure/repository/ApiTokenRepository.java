package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    ApiToken findByTokenAndActiveTrue(String token);
}
