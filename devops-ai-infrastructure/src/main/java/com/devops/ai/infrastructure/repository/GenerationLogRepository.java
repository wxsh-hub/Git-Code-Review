package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.GenerationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GenerationLogRepository extends JpaRepository<GenerationLog, Long> {
    List<GenerationLog> findByProjectIdOrderByCreatedAtDesc(String projectId);
    GenerationLog findByTaskId(String taskId);
}
