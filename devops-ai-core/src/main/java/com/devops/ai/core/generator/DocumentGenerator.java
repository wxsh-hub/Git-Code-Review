package com.devops.ai.core.generator;

import java.util.List;

public interface DocumentGenerator {

    DocumentResult generate(DocumentRequest request);

    List<String> getSupportedFormats();
}
