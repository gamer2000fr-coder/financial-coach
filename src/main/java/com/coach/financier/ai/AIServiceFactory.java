package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AIServiceFactory {
    private final OpenAIService openAIService;
    private final DeepSeekService deepSeekService;
    private final MockAIService mockAIService;
    private final AIModels.AIProvider defaultProvider;

    public AIServiceFactory(OpenAIService openAIService,
                            DeepSeekService deepSeekService,
                            MockAIService mockAIService,
                            @Value("${app.ai.default-provider:MOCK}") String defaultProvider) {
        this.openAIService = openAIService;
        this.deepSeekService = deepSeekService;
        this.mockAIService = mockAIService;
        this.defaultProvider = parse(defaultProvider);
    }

    public AIService get(AIModels.AIProvider provider) {
        AIModels.AIProvider effective = provider == null ? defaultProvider : provider;
        return switch (effective) {
            case GPT -> openAIService;
            case DEEPSEEK -> deepSeekService;
            case MOCK -> mockAIService;
        };
    }

    public AIModels.AIProvider defaultProvider() { return defaultProvider; }

    private static AIModels.AIProvider parse(String value) {
        try { return AIModels.AIProvider.valueOf(value.toUpperCase()); }
        catch (Exception e) { return AIModels.AIProvider.MOCK; }
    }
}
