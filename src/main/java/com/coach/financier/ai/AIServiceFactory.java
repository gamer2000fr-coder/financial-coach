package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import org.springframework.stereotype.Component;

@Component
public class AIServiceFactory {
    /**
     * Repli utilisé UNIQUEMENT si l'appelant ne fournit aucun fournisseur. En usage normal, le
     * fournisseur est choisi dans l'IHM et transmis à chaque appel (échanges ET clôture).
     */
    private static final AIModels.AIProvider FALLBACK_PROVIDER = AIModels.AIProvider.MOCK;

    private final OpenAIService openAIService;
    private final DeepSeekService deepSeekService;
    private final LocalAIService localAIService;
    private final MockAIService mockAIService;

    public AIServiceFactory(OpenAIService openAIService,
                            DeepSeekService deepSeekService,
                            LocalAIService localAIService,
                            MockAIService mockAIService) {
        this.openAIService = openAIService;
        this.deepSeekService = deepSeekService;
        this.localAIService = localAIService;
        this.mockAIService = mockAIService;
    }

    public AIService get(AIModels.AIProvider provider) {
        AIModels.AIProvider effective = provider == null ? FALLBACK_PROVIDER : provider;
        return switch (effective) {
            case GPT -> openAIService;
            case DEEPSEEK -> deepSeekService;
            case LOCAL -> localAIService;
            case MOCK -> mockAIService;
        };
    }

    /** Fournisseur de repli (l'IHM transmet normalement le fournisseur choisi). */
    public AIModels.AIProvider defaultProvider() { return FALLBACK_PROVIDER; }
}
