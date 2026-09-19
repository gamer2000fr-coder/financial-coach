package com.coach.financier.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public final class AIModels {
    private AIModels() {}

    /**
     * Fournisseurs IA disponibles. {@code LOCAL} désigne un serveur LOCAL compatible OpenAI
     * (LM Studio, Ollama, llama.cpp…) : aucune clé API n'est requise et le modèle est celui chargé côté
     * serveur ({@code app.ai.local.model}).
     */
    public enum AIProvider {
        GPT,
        DEEPSEEK,
        LOCAL,
        MOCK
    }

    public enum RequestCategory {
        PURCHASE_PROJECT,
        BUDGET,
        SAVINGS,
        CREDIT,
        CASHFLOW,
        FINANCIAL_HEALTH,
        BANK_PRODUCT,
        OTHER_FINANCIAL,
        OUT_OF_SCOPE
    }

    public enum AIStatus {
        ANSWER,
        NEED_DATA,
        /** Échec (IA indisponible, réponse illisible...) : utilisé uniquement pour les traces. */
        ERROR
    }

    public enum BankingContextMode {
        SYNTHESIS_AVAILABLE,
        FULL_DATA,
        FULL_DATA_GENERATE_SYNTHESIS
    }

    public record Classification(
            boolean inScope,
            RequestCategory category,
            String reason
    ) {}

    /**
     * Demande de données de l'IA : elle liste les chemins (paths) exacts, issus du
     * catalogue data.json, des fichiers dont elle a besoin.
     */
    public record DataRequest(
            List<String> paths
    ) {}

    public record AIAnswer(
            AIStatus status,
            String answer,
            DataRequest dataRequest,
            Map<String, Object> financialContextUpdate,
            String conversationSummary,
            JsonNode financialSynthesis
    ) {}
}
