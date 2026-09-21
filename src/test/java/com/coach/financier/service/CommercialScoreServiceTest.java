package com.coach.financier.service;

import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.SuiviModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie le SCORE DE SENS COMMERCIAL : critères mesurés, urgence, pénalités, bornes, et surtout le fait
 * que la proposition de l'IA est CONSERVÉE tout en restant traçable (le backend ne perd jamais les
 * critères objectifs).
 */
class CommercialScoreServiceTest {
    private FinancialAnalysisService financialAnalysisService;
    private CommercialScoreService service;

    @BeforeEach
    void setUp() {
        financialAnalysisService = mock(FinancialAnalysisService.class);
        when(financialAnalysisService.analyze()).thenReturn(summary(0.22, 350, 0));
        service = new CommercialScoreService(financialAnalysisService);
    }

    @Test
    void aMatureUrgentProjectGetsAHighPriorityAndAnExplanation() {
        ConversationModels.Conversation conversation = conversation(ProjectType.VEHICLE, "voiture d'occasion",
                "15000", 6, "Il me faudrait les fonds avant la fin du mois.");
        SuiviModels.SuiviResult result = result(List.of("sg_credit_auto_expresso"), "HIGH", null);

        SuiviModels.CommercialScore score = service.scoreFor(conversation, result, products());

        assertTrue(score.score() >= 65, "Un projet cadré, urgent et soutenu doit être prioritaire : " + score.score());
        assertTrue(SuiviModels.PRIORITY_VERY_HIGH.equals(score.priority())
                        || SuiviModels.PRIORITY_HIGH.equals(score.priority()),
                "Priorité attendue haute ou très haute, obtenue : " + score.priority());
        assertTrue(score.label().toLowerCase().contains("priorit"), "Le libellé explique la priorité");
        assertFalse(score.reasons().isEmpty(), "Le score est toujours expliqué");
        assertTrue(score.reasons().size() <= 3, "L'explication reste courte (3 raisons au maximum)");
        assertTrue(score.reasons().stream().anyMatch(reason -> reason.toLowerCase().contains("projet")),
                "Une raison porte sur la maturité du projet");
        assertFalse(score.proposedByAi(), "Sans proposition de l'IA, le score est calculé par le système");
        assertEquals(30, points(score, "MATURITY"));
        assertEquals(25, points(score, "INTEREST"));
        assertEquals(20, points(score, "URGENCY"));
        assertEquals(10, points(score, "ENGAGEMENT"));
        assertTrue(score.details().stream().anyMatch(detail -> detail.startsWith("Urgence exprimée")),
                "Les critères mesurés sont conservés pour l'affichage");
    }

    @Test
    void aSingleVagueQuestionStaysLowPriority() {
        ConversationModels.Conversation conversation = conversation(null, null, null, 1,
                "Bonjour, je m'interroge sur mes finances.");

        SuiviModels.CommercialScore score = service.scoreFor(conversation, result(List.of(), null, null), List.of());

        assertTrue(score.score() < 45, "Aucun signal fort ne doit produire un score élevé : " + score.score());
        assertEquals(SuiviModels.PRIORITY_LOW, score.priority());
        assertEquals(0, points(score, "URGENCY"), "Aucune urgence exprimée");
        assertEquals(0, points(score, "MATURITY"), "Aucun projet identifié");
    }

    @Test
    void theUrgencyIsReadFromTheClientMessagesAndNeverFromTheCoach() {
        // Le Coach parle de délais pour tout : seuls les messages du CLIENT comptent.
        ConversationModels.Conversation conversation = conversation(null, null, null, 2,
                "Bonjour, quelles sont vos offres d'épargne ?");
        conversation.addMessage("assistant", "Pour une mise à disposition rapide, comptez un délai de 48 heures.");

        SuiviModels.CommercialScore score = service.scoreFor(conversation, result(List.of(), null, null), List.of());

        assertEquals(0, points(score, "URGENCY"),
                "Le mot « délai » employé par le Coach ne crée pas d'urgence client");
    }

    @Test
    void theUrgencyProposedByTheAiIsUsedWhenTheClientWordingIsIndirect() {
        ConversationModels.Conversation conversation = conversation(null, null, null, 3,
                "Je dois absolument trouver une solution, mon bailleur attend une réponse.");
        SuiviModels.AiScoreProposal proposal = new SuiviModels.AiScoreProposal(70, "HIGH",
                List.of("Le client doit répondre à son bailleur sans délai."));

        SuiviModels.CommercialScore score = service.scoreFor(conversation,
                result(List.of(), null, proposal), List.of());

        assertEquals(20, points(score, "URGENCY"), "L'urgence lue par l'IA est reprise");
        assertEquals(70, score.score(), "Le score proposé par l'IA est conservé (dans les bornes)");
        assertTrue(score.proposedByAi());
        assertEquals(List.of("Le client doit répondre à son bailleur sans délai."), score.reasons(),
                "L'explication de l'IA est reprise telle quelle");
        assertFalse(score.criteria().isEmpty(), "Les critères mesurés restent présents (traçabilité)");
    }

    @Test
    void anAberrantAiScoreIsBounded() {
        ConversationModels.Conversation conversation = conversation(null, null, null, 1, "Bonjour");

        SuiviModels.CommercialScore tooHigh = service.scoreFor(conversation,
                result(List.of(), null, new SuiviModels.AiScoreProposal(300, "HIGH", List.of())), List.of());
        SuiviModels.CommercialScore tooLow = service.scoreFor(conversation,
                result(List.of(), null, new SuiviModels.AiScoreProposal(-40, "NONE", List.of())), List.of());

        assertEquals(100, tooHigh.score(), "Le score est borné à 100");
        assertEquals(0, tooLow.score(), "Le score est borné à 0");
        assertFalse(tooHigh.reasons().isEmpty(), "Un score sans justification reste expliqué par le système");
    }

    @Test
    void rejectedOffersLowerTheScore() {
        ConversationModels.Conversation conversation = conversation(null, null, null, 4, "Bonjour");
        SuiviModels.SuiviResult withRejection = result(List.of("sg_credit_expresso"), "REJECTED", null);
        SuiviModels.SuiviResult withoutRejection = result(List.of("sg_credit_expresso"), "LOW", null);

        int rejected = service.scoreFor(conversation, withRejection, products()).score();
        int neutral = service.scoreFor(conversation, withoutRejection, products()).score();

        assertTrue(rejected < neutral, "Une offre explicitement écartée fait baisser le score");
        assertTrue(service.scoreFor(conversation, withRejection, products()).reasons().stream()
                        .anyMatch(reason -> reason.toLowerCase().contains("écart")),
                "La raison mentionne l'offre écartée");
    }

    @Test
    void aTenseBudgetIsDescribedWithoutInventingABankingThreshold() {
        when(financialAnalysisService.analyze()).thenReturn(summary(0.46, 0, 2));
        ConversationModels.Conversation conversation = conversation(null, null, null, 3, "Bonjour");

        SuiviModels.CommercialScore score = service.scoreFor(conversation, result(List.of(), null, null), List.of());

        assertTrue(points(score, "CAPACITY") <= 4, "Une marge limitée fait baisser le critère capacité");
        assertTrue(score.details().stream().anyMatch(detail -> detail.contains("découvert")),
                "Les découverts constatés sont signalés");
        assertTrue(score.details().stream().noneMatch(detail -> detail.contains("%")
                        && detail.toLowerCase().contains("maximum")),
                "Aucun seuil bancaire n'est introduit dans l'explication");
    }

    @Test
    void aConversationWithoutIndicatorStaysNeutralAndHonest() {
        when(financialAnalysisService.analyze()).thenReturn(null);
        ConversationModels.Conversation conversation = conversation(null, null, null, 2, "Bonjour");

        SuiviModels.CommercialScore score = service.scoreFor(conversation, result(List.of(), null, null), List.of());

        assertEquals(7, points(score, "CAPACITY"), "Sans indicateur : appréciation neutre");
        assertNotNull(score.label());
        assertTrue(score.details().stream().anyMatch(detail -> detail.contains("indisponibles")));
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static ConversationModels.Conversation conversation(ProjectType type, String object, String amount,
                                                               int clientMessages, String firstMessage) {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s-test");
        if (type != null || object != null || amount != null) {
            CurrentProject project = new CurrentProject();
            if (type != null) {
                project.setType(type);
            }
            if (object != null) {
                project.setObject(object);
            }
            if (amount != null) {
                project.setAmount(new BigDecimal(amount));
            }
            conversation.setCurrentProject(project);
        }
        conversation.addMessage("user", firstMessage);
        for (int i = 1; i < clientMessages; i++) {
            conversation.addMessage("user", "Précision " + i);
        }
        conversation.addMessage("assistant", "Voici les éléments utiles.");
        return conversation;
    }

    private static List<Map<String, Object>> products() {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", "sg_credit_auto_expresso");
        product.put("name", "Crédit Auto Expresso");
        product.put("category", "AUTO_LOAN");
        return new ArrayList<>(List.of(product));
    }

    private static SuiviModels.SuiviResult result(List<String> productIds, String interestLevel,
                                                  SuiviModels.AiScoreProposal proposal) {
        List<SuiviModels.ProductOfInterest> interests = new ArrayList<>();
        for (String id : productIds) {
            interests.add(new SuiviModels.ProductOfInterest(id, "Crédit Auto Expresso", "AUTO_LOAN",
                    interestLevel, "motif", null));
        }
        return new SuiviModels.SuiviResult(new SuiviModels.ConversationSummary("", List.of(), List.of()),
                interests, new SuiviModels.EmailContent("", ""), new SuiviModels.EmailContent("", ""),
                List.of(), proposal);
    }

    private static int points(SuiviModels.CommercialScore score, String code) {
        return CommercialScoreService.pointsByCode(score).getOrDefault(code, -1);
    }

    private static FinancialSummary summary(double ratio, double savings, int overdrafts) {
        return new FinancialSummary(14, LocalDate.now().minusMonths(14), LocalDate.now(), 3200, 3200, 2500,
                1500, 1000, savings, 700, 2400, 12000, 450, ratio, 3.5, overdrafts, 150, 900, "Loyer",
                320, Map.of("ALIMENTATION", 400d), 0.2, "3 derniers mois");
    }
}
