package com.devops.ai.core.gitlab;

import com.devops.ai.core.model.AuthorInfo;
import com.devops.ai.core.model.Branch;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.GitLabCommitQuery;
import com.devops.ai.core.model.ProjectInfo;
import com.devops.ai.infrastructure.entity.GitLabConfig;

import java.util.List;

public interface GitLabService {

    List<Commit> getCommits(GitLabCommitQuery query);

    ProjectInfo getProjectInfo(String projectId);

    List<Branch> getBranches(String projectId);

    boolean testConnection(GitLabConfig config);

    List<ProjectInfo> getProjects();

    List<AuthorInfo> getAuthors(String projectId);
}
