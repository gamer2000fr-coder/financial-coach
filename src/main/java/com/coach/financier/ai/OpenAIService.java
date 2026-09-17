package com.coach.financier.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("openAIService")
public class OpenAIService extends RemoteAIService {
    public OpenAIService(ObjectMapper objectMapper,
                         @Value("${app.ai.openai.base-url}") String baseUrl,
                         @Value("${app.ai.openai.api-key}") String apiKey,
                         @Value("${app.ai.openai.model}") String model,
                         @Value("${app.ai.openai.max-tokens:16384}") int maxTokens) {
        super(objectMapper, baseUrl, apiKey, model, "GPT/OpenAI", maxTokens);
    }
}
