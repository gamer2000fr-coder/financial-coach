package com.coach.financier.model;

import jakarta.validation.constraints.NotBlank;

public final class ChatModels {
    private ChatModels() {}

    public record ChatRequest(
            @NotBlank String sessionId,
            @NotBlank String message,
            AIModels.AIProvider provider,
            Boolean disableOutOfScopeGuard
    ) {}

    public record ChatResponse(
            String sessionId,
            AIModels.AIProvider provider,
            AIModels.RequestCategory category,
            boolean inScope,
            AIModels.AIStatus status,
            String answer,
            FinancialSummary financialSummary,
            String conversationSummary,
            String agent
    ) {}
}
