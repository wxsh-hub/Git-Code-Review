package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.ProjectConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectConfigRepository extends JpaRepository<ProjectConfig, Long> {
    List<ProjectConfig> findByActiveTrue();
    List<ProjectConfig> findByGitlabConfigId(Long gitlabConfigId);
}
