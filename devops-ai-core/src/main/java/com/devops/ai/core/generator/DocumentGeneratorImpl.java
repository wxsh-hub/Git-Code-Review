package com.devops.ai.core.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DocumentGeneratorImpl implements DocumentGenerator {

    private static final Logger log = LoggerFactory.getLogger(DocumentGeneratorImpl.class);

    private final Map<String, DocumentFormatGenerator> formatGenerators;

    public DocumentGeneratorImpl(List<DocumentFormatGenerator> generators) {
        this.formatGenerators = new HashMap<>();
        for (DocumentFormatGenerator generator : generators) {
            this.formatGenerators.put(generator.getFormat(), generator);
        }
    }

    @Override
    public DocumentResult generate(DocumentRequest request) {
        long startTime = System.currentTimeMillis();

        log.info("Starting document generation for project: {}, format: {}, incremental: {}",
                request.getProjectName(), request.getFormat(), request.isIncremental());

        String format = request.getFormat() != null ? request.getFormat() : "markdown";
        DocumentFormatGenerator generator = formatGenerators.get(format);

        if (generator == null) {
            DocumentResult errorResult = new DocumentResult();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage("Unsupported format: " + format);
            errorResult.setFormat(format);
            return errorResult;
        }

        try {
            DocumentResult result = generator.generate(request);

            long elapsed = System.currentTimeMillis() - startTime;
            result.setGenerationTimeMs(elapsed);
            result.setCommitCount(request.getTotalCommits());

            log.info("Document generation completed in {}ms, format: {}, commits: {}",
                    elapsed, format, request.getTotalCommits());

            return result;
        } catch (Exception e) {
            log.error("Document generation failed: {}", e.getMessage(), e);
            DocumentResult errorResult = new DocumentResult();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage("Document generation failed: " + e.getMessage());
            errorResult.setFormat(format);
            errorResult.setGenerationTimeMs(System.currentTimeMillis() - startTime);
            return errorResult;
        }
    }

    @Override
    public List<String> getSupportedFormats() {
        return new ArrayList<>(formatGenerators.keySet());
    }
}
