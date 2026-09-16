package com.coach.financier.controller;

import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ChatModels;
import com.coach.financier.model.ProjectType;
import com.coach.financier.service.AILogService;
import com.coach.financier.service.CoachContextBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NON-RÉGRESSION du CHAT : le flux complet (classification → construction du contexte → appel du
 * Coach → journalisation) est exercé avec le fournisseur MOCK, qui n'exige aucune clé API.
 * <p>
 * Ce test verrouille le comportement du chat après l'EXTRACTION du {@link CoachContextBuilder}
 * hors de {@code ChatController} (l'atelier d'optimisation des prompts partage désormais ce
 * constructeur de contexte).
 */
@SpringBootTest
class ChatControllerTest {

    @Autowired
    private ChatController chatController;

    @Autowired
    private AILogService aiLogService;

    @Autowired
    private AIServiceFactory aiServiceFactory;

    private static ChatModels.ChatRequest request(String sessionId, String message) {
        return new ChatModels.ChatRequest(sessionId, message, AIModels.AIProvider.MOCK, null);
    }

    @Test
    void financingRequestAnswersWithAnAgentAndIsTraced() {
        ChatModels.ChatResponse response = chatController.chat(request("chat-test-1",
                "Je souhaite financer une voiture d'occasion à 15000 euros, quelles solutions ?"));

        assertTrue(response.inScope());
        assertEquals(AIModels.AIStatus.ANSWER, response.status());
        assertNotNull(response.answer());
        assertFalse(response.answer().isBlank());
        assertNotNull(response.financialSummary(), "la synthèse financière est toujours calculée");
        assertNotNull(response.agent());
        assertEquals("chat-test-1", response.sessionId());

        // Trace IA : bloc de debug structuré + prompt consultable (page Logs).
        var log = aiLogService.latest().get(0);
        assertTrue(log.debug().contains("[INTENT]"), "bloc [INTENT] présent");
        assertTrue(log.debug().contains("[AGENT]"), "bloc [AGENT] présent");
        assertTrue(log.debug().contains("[COACH]"), "bloc [COACH] présent");
        assertTrue(log.debug().contains("theme="));
        assertTrue(aiLogService.promptOf(log.id()).contains("=== PROMPT SYSTÈME ==="));
        assertTrue(log.charCount() > 0, "le compteur de caractères envoyés est renseigné");
    }

    @Test
    void unknownProjectOnFinancingRequestAsksForClarificationWithoutCoachCall() {
        String question = "Je voudrais un crédit";
        // PRÉCONDITIONS du scénario, vérifiées sur le classifieur démo : intention de financement ET
        // projet inconnu. Le test échoue ici (et non sur le chat) si le comportement du mock change.
        var classification = aiServiceFactory.get(AIModels.AIProvider.MOCK)
                .classifyIntent(question, "", AIModels.AIProvider.MOCK);
        assertEquals(ProjectType.UNKNOWN, classification.getProjectType(), "précondition : projet inconnu");
        assertTrue(classification.needsClarification(), "précondition : clarification nécessaire");

        int before = aiLogService.latest().size();

        ChatModels.ChatResponse response = chatController.chat(request("chat-test-2", question));

        assertEquals(CoachContextBuilder.CLARIFICATION_MESSAGE, response.answer(),
                "message de clarification inchangé après extraction");
        assertEquals(before, aiLogService.latest().size(),
                "une clarification ne déclenche AUCUN appel au Coach (donc aucune trace [COACH])");
    }

    @Test
    void laterMessageInTheSameSessionReusesTheConversation() {
        chatController.chat(request("chat-test-3", "Je veux acheter une voiture à 15000 euros"));

        ChatModels.ChatResponse second = chatController.chat(request("chat-test-3",
                "Et pour mon projet, sur quelle durée puis-je emprunter ?"));

        assertTrue(second.inScope());
        assertNotNull(second.answer());
        assertFalse(second.answer().isBlank());
        assertNotNull(second.conversationSummary());
    }

    @Test
    void withTheGuardDisabledTheMessageStaysInScope() {
        ChatModels.ChatResponse response = chatController.chat(new ChatModels.ChatRequest("chat-test-4",
                "Quelle est la météo demain ?", AIModels.AIProvider.MOCK, true));

        assertTrue(response.inScope(), "le contrôle hors-sujet est désactivé par l'utilisateur");
        assertNotNull(response.answer());
        assertFalse(response.answer().isBlank());
    }

    @Test
    void outOfScopeQuestionIsRefusedWithoutProducts() {
        ChatModels.ChatResponse response = chatController.chat(request("chat-test-5",
                "Quelle est la météo demain à Paris ?"));

        assertFalse(response.inScope(), "question hors périmètre financier");
        assertNotNull(response.answer());
    }
}
