package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.VersionTracker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VersionTrackerRepository extends JpaRepository<VersionTracker, Long> {
    VersionTracker findByProjectIdAndBranch(String projectId, String branch);
}
