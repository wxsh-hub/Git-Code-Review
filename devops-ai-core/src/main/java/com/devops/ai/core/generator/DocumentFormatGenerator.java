package com.devops.ai.core.generator;

public interface DocumentFormatGenerator {

    String getFormat();

    DocumentResult generate(DocumentRequest request);
}
