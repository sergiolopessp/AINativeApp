package com.app.ainative;

import org.springframework.ai.model.bedrock.cohere.autoconfigure.BedrockCohereEmbeddingAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = BedrockCohereEmbeddingAutoConfiguration.class)
public class AiNativeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiNativeApplication.class, args);
    }
}
