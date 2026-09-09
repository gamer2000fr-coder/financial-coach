package com.coach.financier.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("deepSeekService")
public class DeepSeekService extends RemoteAIService {
    public DeepSeekService(ObjectMapper objectMapper,
                           @Value("${app.ai.deepseek.base-url}") String baseUrl,
                           @Value("${app.ai.deepseek.api-key}") String apiKey,
                           @Value("${app.ai.deepseek.model}") String model) {
        super(objectMapper, baseUrl, apiKey, model, "DeepSeek");
    }
}
