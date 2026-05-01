package com.devops.ai.infrastructure.repository;

import com.devops.ai.infrastructure.entity.CommitCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommitCategoryRepository extends JpaRepository<CommitCategory, Long> {
    List<CommitCategory> findAllByOrderByPriorityAsc();
}
