package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.GitLabConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GitLabConfigRepository extends JpaRepository<GitLabConfig, Long> {
    List<GitLabConfig> findByActiveTrue();
}
