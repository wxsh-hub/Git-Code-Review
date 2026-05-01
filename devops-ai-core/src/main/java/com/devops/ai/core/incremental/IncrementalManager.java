package com.devops.ai.core.incremental;

import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.GitLabCommitQuery;
import com.devops.ai.core.gitlab.GitLabService;
import com.devops.ai.infrastructure.entity.VersionTracker;
import com.devops.ai.infrastructure.repository.VersionTrackerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Component
public class IncrementalManager {

    private static final Logger log = LoggerFactory.getLogger(IncrementalManager.class);

    private final VersionTrackerRepository trackerRepository;
    private final GitLabService gitLabService;

    public IncrementalManager(VersionTrackerRepository trackerRepository,
                              GitLabService gitLabService) {
        this.trackerRepository = trackerRepository;
        this.gitLabService = gitLabService;
    }

    public boolean isIncrementalMode(String projectId, String branch) {
        VersionTracker tracker = trackerRepository.findByProjectIdAndBranch(projectId, branch);
        return tracker != null && tracker.getLastHash() != null;
    }

    public VersionTracker getTracker(String projectId, String branch) {
        return trackerRepository.findByProjectIdAndBranch(projectId, branch);
    }

    public String getSinceHash(String projectId, String branch) {
        VersionTracker tracker = trackerRepository.findByProjectIdAndBranch(projectId, branch);
        if (tracker != null) {
            return tracker.getLastHash();
        }
        return null;
    }

    public void updateTracker(String projectId, String branch, String latestHash) {
        VersionTracker tracker = trackerRepository.findByProjectIdAndBranch(projectId, branch);
        if (tracker == null) {
            tracker = new VersionTracker();
            tracker.setProjectId(projectId);
            tracker.setBranch(branch);
            tracker.setCreatedAt(new Date());
        }
        tracker.setLastHash(latestHash);
        tracker.setLastGenerated(new Date());
        tracker.setUpdatedAt(new Date());
        trackerRepository.save(tracker);
        log.info("Version tracker updated for {}/{}: hash={}", projectId, branch, latestHash);
    }

    public void resetTracker(String projectId, String branch) {
        VersionTracker tracker = trackerRepository.findByProjectIdAndBranch(projectId, branch);
        if (tracker != null) {
            trackerRepository.delete(tracker);
            log.info("Version tracker reset for {}/{}", projectId, branch);
        }
    }

    public List<Commit> getIncrementalCommits(String projectId, String branch) {
        return getIncrementalCommits(projectId, branch, null, null);
    }

    public List<Commit> getIncrementalCommits(String projectId, String branch, String customSinceHash) {
        return getIncrementalCommits(projectId, branch, customSinceHash, null);
    }

    public List<Commit> getIncrementalCommits(String projectId, String branch, String customSinceHash, String customUntilHash) {
        String sinceHash = customSinceHash != null ? customSinceHash : getSinceHash(projectId, branch);
        if (sinceHash == null) {
            log.info("No incremental tracker found for {}/{}, returning empty", projectId, branch);
            return null;
        }

        GitLabCommitQuery query = new GitLabCommitQuery();
        query.setProjectId(projectId);
        query.setBranch(branch);
        query.setSinceHash(sinceHash);
        if (customUntilHash != null && !customUntilHash.isEmpty()) {
            query.setUntilHash(customUntilHash);
        }

        log.info("Fetching incremental commits for {}/{} from hash {} to {}", projectId, branch, sinceHash,
                customUntilHash != null ? customUntilHash : "HEAD");
        return gitLabService.getCommits(query);
    }
}
