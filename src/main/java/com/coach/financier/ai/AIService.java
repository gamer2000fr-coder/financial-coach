package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;

import java.util.List;
import java.util.Map;

public interface AIService {
    AIModels.Classification classifyUserRequest(String message, AIModels.AIProvider provider);

    /**
     * Premier appel IA (compréhension) : classe le périmètre, l'intention, le type de
     * projet, le montant et l'objet. Retourne une structure JSON. Ne sélectionne aucun produit.
     *
     * @param currentProjectDescription description texte du projet courant (ou chaîne vide)
     */
    IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                        AIModels.AIProvider provider);

    AIModels.AIAnswer answer(String customerMessage,
                             AIModels.Classification classification,
                             FinancialSummary financialSummary,
                             Object bankingData,
                             AIModels.BankingContextMode contextMode,
                             Map<String, Object> additionalData,
                             List<ConversationModels.Message> history,
                             AIModels.AIProvider provider);
}
