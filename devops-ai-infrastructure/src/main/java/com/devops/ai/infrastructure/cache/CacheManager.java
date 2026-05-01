package com.devops.ai.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Component
public class CacheManager {

    @Value("${devops.ai.cache.project-ttl-minutes:60}")
    private int projectTtlMinutes;

    @Value("${devops.ai.cache.branch-ttl-minutes:5}")
    private int branchTtlMinutes;

    private Cache<String, Object> projectCache;
    private Cache<String, Object> branchCache;

    @PostConstruct
    public void init() {
        projectCache = Caffeine.newBuilder()
                .expireAfterWrite(projectTtlMinutes, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats()
                .build();

        branchCache = Caffeine.newBuilder()
                .expireAfterWrite(branchTtlMinutes, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats()
                .build();
    }

    public void putProject(String key, Object value) {
        projectCache.put(key, value);
    }

    public Object getProject(String key) {
        return projectCache.getIfPresent(key);
    }

    public void putBranch(String key, Object value) {
        branchCache.put(key, value);
    }

    public Object getBranch(String key) {
        return branchCache.getIfPresent(key);
    }

    public void evictProject(String key) {
        projectCache.invalidate(key);
    }

    public void evictBranch(String key) {
        branchCache.invalidate(key);
    }

    public void clearAll() {
        projectCache.invalidateAll();
        branchCache.invalidateAll();
    }
}
