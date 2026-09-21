package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.LogEntry;
import com.coach.financier.model.QualityModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrôles automatiques qualité (§15 à §21) : le point crucial est qu'une mauvaise note client ne
 * devient JAMAIS une anomalie du Coach, qu'une règle correctement appliquée (redirection vers le
 * simulateur officiel) n'est jamais signalée comme violation, et qu'à l'inverse une simulation ISSUE DE
 * LA GRILLE DE TAUX, annoncée comme non contractuelle, est désormais conforme (§10 du prompt).
 */
class CoachQualityCheckServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private QualityProperties properties;
    private QualityCheckStore checkStore;
    private CoachQualityCheckService service;

    @BeforeEach
    void setUp() {
        properties = new QualityProperties(true, false, tempDir.toString(), "salt", 1000, 30, 10,
                "quality-report-v1", "", "HIGH", "HIGH", "HIGH", "MEDIUM", "MEDIUM", "LOW");
        checkStore = new QualityCheckStore(properties, objectMapper);
        service = new CoachQualityCheckService(properties, checkStore,
                new ProjectProductMappingService(), new ProductUrlIndex(objectMapper, "./target/inexistant"),
                new AILogService(), "https://particuliers.sg.fr/vos-rendez-vous");
    }

    @Test
    void creditSimulationViolation_whenCoachComputesItself() {
        ConversationModels.Conversation conversation = conversation(
                "Combien je rembourserais pour 15000 € ?",
                "Avec un taux de 4,5 %, la mensualité serait de 279 € par mois sur 60 mois.");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertTrue(check.detected(), "Un chiffrage produit par le Coach doit être détecté");
        assertEquals("HIGH", check.severity());
    }

    @Test
    void noCreditSimulationViolation_whenCoachRedirectsToOfficialSimulator() {
        // Cas A du prompt : le client critique le refus, mais le Coach est CONFORME.
        ConversationModels.Conversation conversation = conversation(
                "Calculez-moi la mensualité de mon crédit !",
                "Je ne peux pas réaliser de simulation de crédit : la mensualité doit être calculée dans "
                        + "l'outil officiel. Vous pouvez l'estimer avec le simulateur officiel de la banque "
                        + "(https://particuliers.sg.fr/vos-rendez-vous).");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertFalse(check.detected(), "Une redirection vers le simulateur officiel est conforme");
    }

    @Test
    void noCreditSimulationViolation_whenTheCoachSimulatesFromTheProvidedRateGrid() {
        // Cas B du prompt : le Coach chiffre à partir de la GRILLE DE TAUX et rappelle que la souscription fait foi.
        ConversationModels.Conversation conversation = conversation(
                "Combien je rembourserais pour 15 000 € ?",
                "À partir de la grille de taux du crédit à la consommation, pour 15 000 € sur 48 mois, le TAEG "
                        + "indicatif est de 5,49 % : la mensualité serait d'environ 348 € par mois. Cette "
                        + "simulation est indicative et non contractuelle : seuls le contrat de prêt et la "
                        + "souscription signés font foi.");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertFalse(check.detected(),
                "Un chiffrage issu de la grille de taux, annoncé comme non contractuel, est conforme");
    }

    @Test
    void creditSimulationViolation_whenTheFigureIsPresentedAsContractual() {
        // Cas C du prompt : aucun taux de référence, et le chiffrage est présenté comme un engagement.
        ConversationModels.Conversation conversation = conversation(
                "Combien je rembourserais pour 15 000 € ?",
                "Pour 15 000 € sur 48 mois, la mensualité est de 348 € par mois : c'est l'engagement ferme "
                        + "de la banque.");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertTrue(check.detected(),
                "Un chiffrage sans grille de taux ni mention indicative doit rester une violation");
    }

    @Test
    void noCreditSimulationViolation_forExistingCreditReminder() {
        ConversationModels.Conversation conversation = conversation(
                "Où en est mon crédit ?",
                "Votre crédit en cours comporte une mensualité actuelle de 956 € par mois.");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertFalse(check.detected(), "Rappeler un engagement existant n'est pas une simulation");
    }

    @Test
    void creditSimulationViolation_whenATableOfFiguresIsGivenWithoutTheRateGrid() {
        // Le prompt autorise un TABLEAU comparant plusieurs durées : le contrôle doit aussi le couvrir.
        ConversationModels.Conversation conversation = conversation(
                "Et si j'allonge la durée ?",
                "Voici le comparatif :\n\n"
                        + "| Durée | Mensualité | TAEG | Coût total | Montant total dû |\n"
                        + "|---|---|---|---|---|\n"
                        + "| 48 mois | 347,87 € | 5,49 % | 1 697,61 € | 16 697,61 € |\n"
                        + "| 60 mois | 285,00 € | 5,90 % | 1 600,00 € | 16 600,00 € |");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.CREDIT_SIMULATION_VIOLATION);

        assertTrue(check.detected(),
                "Un tableau de chiffrage sans grille de taux ni mention indicative reste une violation");
    }

    @Test
    void productMismatch_detectedWhenFamilyNotAllowedForProject() {
        ConversationModels.Conversation conversation = conversationWithProduct("MORTGAGE");
        conversation.setCurrentProject(project(com.coach.financier.model.ProjectType.VEHICLE));

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.PRODUCT_MISMATCH);

        assertTrue(check.detected());
        assertTrue(check.details().contains("MORTGAGE"));
    }

    @Test
    void productMismatch_notDetectedForCompatibleProduct() {
        ConversationModels.Conversation conversation = conversationWithProduct("AUTO_LOAN");
        conversation.setCurrentProject(project(com.coach.financier.model.ProjectType.VEHICLE));

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.PRODUCT_MISMATCH);

        assertFalse(check.detected());
    }

    @Test
    void inventedUrl_detectedWhenUrlUnknown() {
        ConversationModels.Conversation conversation = conversation(
                "Quel est le taux ?",
                "Retrouvez l'offre sur https://banque-exemple-inconnue.test/offre-auto.");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.INVENTED_URL);

        assertTrue(check.detected());
        assertTrue(check.details().contains("banque-exemple-inconnue"));
    }

    @Test
    void unansweredRequest_detectedWhenConversationEndsOnClientMessage() {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s-unanswered");
        conversation.addMessage("user", "Et si je veux rembourser plus tôt ?");

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.UNANSWERED_REQUEST);

        assertTrue(check.detected());
    }

    @Test
    void missingDataNotRetrieved_detectedFromAiLogs() {
        ConversationModels.Conversation conversation = conversation("Bonjour", "Bonjour, comment puis-je aider ?");
        AILogService logs = new AILogService();
        logs.log("s1", "message", List.of(), 1, 10, com.coach.financier.model.AIModels.AIStatus.NEED_DATA,
                List.of("transactions_2026_08"), "agent", "", "", "");

        List<QualityModels.QualityCheck> checks = service.evaluate("s1", conversation, logs.latest());
        QualityModels.QualityCheck check = checks.stream()
                .filter(item -> QualityModels.MISSING_DATA_NOT_RETRIEVED.equals(item.checkType()))
                .findFirst().orElseThrow();

        assertTrue(check.detected());
        assertTrue(check.details().contains("transactions_2026_08"));
    }

    @Test
    void excessiveRepetition_detectedWhenSameSentenceRepeated() {
        String sentence = "Votre solde de compte courant est de 1516,89 euros au 31 août 2026.";
        ConversationModels.Conversation conversation = conversation("Et mon solde ?", sentence + " " + sentence);

        QualityModels.QualityCheck check = checkOf(conversation, QualityModels.EXCESSIVE_REPETITION);

        assertTrue(check.detected());
        assertEquals("LOW", check.severity());
    }

    @Test
    void checksAreOnlyTheImplementedOnes_andIdsAreDeterministic() {
        ConversationModels.Conversation conversation = conversation("Bonjour", "Bonjour !");

        List<QualityModels.QualityCheck> checks = service.evaluate("s1", conversation, List.of());

        assertEquals(properties.enabledChecks().size(), checks.size(),
                "Un contrôle non implémenté ne doit jamais produire d'événement");
        assertEquals(CoachQualityCheckService.checkId("s1", QualityModels.PRODUCT_MISMATCH),
                checks.get(1).checkId(), "L'identifiant doit être déterministe (idempotence)");
        assertNotNull(checks.get(0).timestamp());
    }

    @Test
    void evaluateDoesNotPersist_andRunIsIdempotent() {
        ConversationModels.Conversation conversation = conversation("Bonjour", "Bonjour !");

        // Évaluation pure : aucun événement écrit sur disque.
        assertFalse(service.evaluate("s-run", conversation, List.of()).isEmpty());
        assertTrue(checkStore.read(null, null).values().isEmpty(), "evaluate() n'écrit rien");

        assertEquals(properties.enabledChecks().size(), service.run("s-run", conversation).size());
        assertEquals(0, service.run("s-run", conversation).size(),
                "Relancer les contrôles de la même session ne crée aucun doublon");
    }

    @Test
    void sanitizeComment_masksPersonalData() {
        String sanitized = QualityModels.sanitizeComment(
                "Rappelez-moi au 06 12 34 56 78 ou par mail jean.dupont@example.com (IBAN 1234567890123456)", 1000);

        assertFalse(sanitized.contains("06 12 34 56 78"));
        assertFalse(sanitized.contains("jean.dupont@example.com"));
        assertFalse(sanitized.contains("1234567890123456"));
        assertTrue(sanitized.contains("[numéro masqué]"));
        assertTrue(sanitized.contains("[email masqué]"));
    }

    // ------------------------------------------------------------------ helpers

    private QualityModels.QualityCheck checkOf(ConversationModels.Conversation conversation, String checkType) {
        return service.evaluate("s-qualite", conversation, List.of()).stream()
                .filter(item -> checkType.equals(item.checkType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Contrôle absent : " + checkType));
    }

    private static ConversationModels.Conversation conversation(String userMessage, String coachMessage) {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s-qualite");
        conversation.addMessage("user", userMessage);
        conversation.addMessage("assistant", coachMessage);
        return conversation;
    }

    private static ConversationModels.Conversation conversationWithProduct(String family) {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s-qualite");
        conversation.addMessage("user", "Que me conseillez-vous ?");
        conversation.addMessage("assistant", "Voici une offre qui pourrait correspondre.");
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", "produit-1");
        compact.put("name", "Produit de test");
        compact.put("family", family);
        conversation.addDiscussedProducts(new ArrayList<>(List.of(compact)));
        return conversation;
    }

    private static com.coach.financier.model.CurrentProject project(
            com.coach.financier.model.ProjectType type) {
        com.coach.financier.model.CurrentProject project = new com.coach.financier.model.CurrentProject();
        project.setType(type);
        return project;
    }

    /** Utilisé pour vérifier qu'un log d'erreur déclenche la non-résolution. */
    @Test
    void unansweredRequest_detectedOnAiError() {
        ConversationModels.Conversation conversation = conversation("Bonjour", "Bonjour !");
        List<LogEntry> logs = List.of(new LogEntry(1L, Instant.now().toString(), "s-qualite", "message",
                List.of(), 1, 10, "ERROR", "agent", List.of(), "", "", "", ""));

        QualityModels.QualityCheck check = service.evaluate("s-qualite", conversation, logs).stream()
                .filter(item -> QualityModels.UNANSWERED_REQUEST.equals(item.checkType()))
                .findFirst().orElseThrow();

        assertTrue(check.detected());
        assertNotNull(check.confidence(), "Une anomalie détectée porte un indice de confiance");
    }
}
