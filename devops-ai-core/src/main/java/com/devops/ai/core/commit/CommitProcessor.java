package com.devops.ai.core.commit;

import com.devops.ai.core.model.Category;
import com.devops.ai.core.model.Commit;
import com.devops.ai.core.model.Dimension;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CommitProcessor {

    private final CommitCleaner commitCleaner;

    public CommitProcessor(CommitCleaner commitCleaner) {
        this.commitCleaner = commitCleaner;
    }

    public List<Commit> deduplicate(List<Commit> commits) {
        Set<String> seenMessages = new HashSet<>();
        List<Commit> result = new ArrayList<>();

        for (Commit commit : commits) {
            String cleaned = commitCleaner.sanitize(commit.getMessage());
            if (cleaned != null && !cleaned.isEmpty() && !seenMessages.contains(cleaned)) {
                seenMessages.add(cleaned);
                commit.setMessage(cleaned);
                result.add(commit);
            }
        }

        return result;
    }

    public Map<String, List<Commit>> groupByDimension(List<Commit> commits, Dimension dimension) {
        switch (dimension) {
            case AUTHOR:
                return commits.stream()
                        .collect(Collectors.groupingBy(
                                c -> c.getAuthorName() != null ? c.getAuthorName() : "Unknown",
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
            case BRANCH:
                return commits.stream()
                        .collect(Collectors.groupingBy(
                                c -> "default",
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
            case TIME:
                return commits.stream()
                        .collect(Collectors.groupingBy(
                                c -> {
                                    if (c.getCreatedAt() != null) {
                                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                                        return sdf.format(c.getCreatedAt());
                                    }
                                    return "Unknown";
                                },
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
            default:
                Map<String, List<Commit>> result = new LinkedHashMap<>();
                result.put("all", new ArrayList<>(commits));
                return result;
        }
    }

    public String formatCommitMessage(String rawMessage) {
        return commitCleaner.sanitize(rawMessage);
    }

    public List<Commit> mergeExternalLogs(List<Commit> commits, String externalLog) {
        if (externalLog == null || externalLog.trim().isEmpty()) {
            return commits;
        }

        List<Commit> result = new ArrayList<>(commits);

        String[] lines = externalLog.split("\n");
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                Commit externalCommit = new Commit();
                externalCommit.setId("external-" + UUID.randomUUID().toString());
                externalCommit.setMessage(line.trim());
                externalCommit.setAuthorName("External");
                externalCommit.setCreatedAt(new Date());
                result.add(externalCommit);
            }
        }

        return result;
    }
}
