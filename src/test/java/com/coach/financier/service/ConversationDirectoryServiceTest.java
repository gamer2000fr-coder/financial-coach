package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.DirectoryModels;
import com.coach.financier.model.SuiviModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Vérifie l'ANNUAIRE DES CONVERSATIONS (page Centre d'appels) : filtres (période, catégorie, recherche),
 * tri par score, et détail qui reprend la synthèse CONSEILLER (jamais le brouillon client).
 */
class ConversationDirectoryServiceTest {
    private static final String DEMO_PHONE = "0644910925";

    @TempDir
    Path tempDir;

    private AdvisorDossierStore dossierStore;
    private AdvisorFeedbackStore feedbackStore;
    private CallCenterStatusStore statusStore;
    private ConversationDirectoryService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        AdvisorFeedbackProperties properties = new AdvisorFeedbackProperties(true, false,
                tempDir.toString(), "salt", 1000, 5, "advisor-feedback-v1");
        dossierStore = new AdvisorDossierStore(properties, mapper);
        feedbackStore = mock(AdvisorFeedbackStore.class);
        when(feedbackStore.read(any(), any()))
                .thenReturn(new AdvisorFeedbackStore.ReadResult(List.of(), 0));
        statusStore = new CallCenterStatusStore(mapper, tempDir.resolve("call-center").toString());
        service = new ConversationDirectoryService(dossierStore, feedbackStore,
                new AdvisorDossierService(dossierStore, feedbackStore, properties, "http://localhost:9898"),
                statusStore, DEMO_PHONE);
    }

    @Test
    void listsClosedConversationsWithTheirScoreAndCategory() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture d'occasion", 82, 1));
        save(dossier("s-epargne", "DEMO002", "EPARGNE", "Épargne de précaution", 41, 2));

        DirectoryModels.DirectoryList list = service.list(10, null, null, null, null);

        assertEquals(2, list.total());
        DirectoryModels.DirectoryRow credit = row(list, "s-credit");
        assertEquals("DEMO001", credit.customerId());
        assertEquals("Crédit conso", credit.categoryLabel());
        assertEquals(82, credit.score());
        assertEquals(SuiviModels.PRIORITY_VERY_HIGH, credit.priority());
        assertTrue(credit.priorityLabel().toLowerCase().contains("très haute"));
        assertEquals(2, list.categories().size(), "Les catégories disponibles sont renvoyées pour le filtre");
        assertEquals(1, list.byPriority().get(SuiviModels.PRIORITY_VERY_HIGH));
    }

    @Test
    void filtersByCategory() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));
        save(dossier("s-epargne", "DEMO002", "EPARGNE", "Épargne", 41, 2));

        DirectoryModels.DirectoryList list = service.list(10, "EPARGNE", null, null, null);

        assertEquals(1, list.total());
        assertEquals("s-epargne", list.rows().get(0).sessionId());
        assertEquals(2, list.categories().size(), "Le filtre ne change pas les valeurs proposées");
    }

    @Test
    void filtersByPeriod() {
        save(dossier("s-recente", "DEMO001", "CREDIT_CONSO", "Projet récent", 70, 1));
        save(dossier("s-ancienne", "DEMO002", "CREDIT_CONSO", "Projet ancien", 70, 20));
        assertEquals(2, service.list(30, null, null, null, null).total());
        assertEquals(1, service.list(5, null, null, null, null).total(),
                "La période « 5 derniers jours » exclut la conversation de 20 jours");
        assertEquals(2, service.list(0, null, null, null, null).total(), "0 = tout l'historique");
    }

    @Test
    void searchesOnCustomerTitleOrProduct() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture d'occasion", 82, 1));
        save(dossier("s-epargne", "DEMO002", "EPARGNE", "Épargne de précaution", 41, 2));

        assertEquals(1, service.list(10, null, "DEMO002", null, null).total());
        assertEquals(1, service.list(10, null, "voiture", null, null).total());
        assertEquals(1, service.list(10, null, "épargne de précaution", null, null).total());
        assertEquals(0, service.list(10, null, "introuvable", null, null).total());
    }

    @Test
    void sortsByScoreWhenAsked() {
        save(dossier("s-bas", "DEMO001", "CREDIT_CONSO", "Petit projet", 22, 1));
        save(dossier("s-haut", "DEMO002", "CREDIT_CONSO", "Gros projet", 91, 2));
        save(dossier("s-moyen", "DEMO003", "EPARGNE", "Projet moyen", 58, 3));

        DirectoryModels.DirectoryList desc = service.list(10, null, null, "score", "desc");
        assertEquals(List.of("s-haut", "s-moyen", "s-bas"),
                desc.rows().stream().map(DirectoryModels.DirectoryRow::sessionId).toList());

        DirectoryModels.DirectoryList asc = service.list(10, null, null, "score", "asc");
        assertEquals("s-bas", asc.rows().get(0).sessionId());
    }

    @Test
    void detailReturnsTheAdvisorSynthesisTheScoreAndTheTranscript() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture d'occasion", 82, 1));

        DirectoryModels.DirectoryDetail detail = service.detail("s-credit").orElseThrow();

        assertEquals("Voiture d'occasion", detail.row().title());
        assertTrue(detail.advisorBody().contains("Synthèse conseiller"),
                "Le détail porte la même synthèse que le mail envoyé au conseiller");
        assertFalse(detail.advisorBody().contains("Brouillon client"),
                "Le brouillon destiné au client reste une PIÈCE JOINTE : il n'est pas noyé dans la synthèse");
        assertEquals("Brouillon client", detail.customerEmailSubject());
        assertTrue(detail.customerEmailBody().contains("Brouillon client à vérifier"),
                "La pièce jointe du mail conseiller est servie à la pop-in du centre d'appels");
        assertEquals(List.of("Rappeler le client"), detail.nextActions());
        assertEquals(1, detail.products().size());
        assertEquals(1, detail.scoreReasons().size());
        assertEquals("Maturité du projet : 27/30 — vehicle, 15000 EUR", detail.scoreCriteria().get(0));
        assertEquals(2, detail.transcript().size(), "Le transcript permet l'affichage repliable");
        assertEquals(DEMO_PHONE, detail.contactPhone(), "Le numéro de démonstration est servi par la config");
        assertTrue(detail.feedbackUrl().endsWith("#/advisor-feedback/session/s-credit"));
        assertTrue(detail.conversationUrl().endsWith("#/conversation/s-credit"));
        assertFalse(detail.evaluated(), "Aucun avis conseiller n'a encore été déposé");
    }

    @Test
    void anUnknownSessionHasNoDetail() {
        assertEquals(Optional.empty(), service.detail("s-inconnue"));
    }

    @Test
    void everyDossierStartsAsNewUntilTheAdvisorMovesIt() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));

        DirectoryModels.DirectoryRow row = row(service.list(10, null, null, null, null), "s-credit");

        assertEquals("NOUVEAU", row.status(), "un dossier qui vient d'arriver est « Nouveau »");
        assertEquals("Nouveau", row.statusLabel());
        assertEquals(null, row.statusUpdatedAt(), "aucun changement de statut n'a encore eu lieu");
        assertEquals(0, row.noteCount(), "aucun message n'a encore été laissé");
        assertEquals(0, service.detail("s-credit").orElseThrow().statusHistory().size());
    }

    @Test
    void theAdvisorCanMoveTheDossierForwardAndTheHistoryIsKept() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));

        DirectoryModels.DirectoryDetail contacte = service
                .updateStatus("s-credit", "CONTACTE", "Client joint, rappellera lundi").orElseThrow();
        assertEquals("CONTACTE", contacte.row().status());
        assertEquals("Contacté", contacte.row().statusLabel());
        assertNotNull(contacte.row().statusUpdatedAt());

        DirectoryModels.DirectoryDetail conclu = service
                .updateStatus("s-credit", "CONCLU", null).orElseThrow();
        assertEquals("CONCLU", conclu.row().status());
        assertEquals(2, conclu.statusHistory().size(), "chaque changement est conservé");
        assertEquals("NOUVEAU", conclu.statusHistory().get(0).previousStatus());
        assertEquals("CONTACTE", conclu.statusHistory().get(0).status());
        assertEquals("Client joint, rappellera lundi", conclu.statusHistory().get(0).comment(),
                "le commentaire du changement est conservé");
        assertEquals("CONTACTE", conclu.statusHistory().get(1).previousStatus());
        assertEquals("CONCLU", conclu.statusHistory().get(1).status());
        assertEquals(null, conclu.statusHistory().get(1).comment());

        // Statut inchangé : aucune nouvelle ligne (l'historique ne se remplit pas de doublons).
        assertEquals(2, service.updateStatus("s-credit", "CONCLU", null).orElseThrow()
                .statusHistory().size());
    }

    @Test
    void aMessageCanBeLeftWithoutChangingTheStatus() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));

        DirectoryModels.DirectoryDetail note = service
                .updateStatus("s-credit", "NOUVEAU", "Client absent, rappellera demain matin").orElseThrow();

        assertEquals("NOUVEAU", note.row().status(), "un message seul ne change pas le statut");
        assertEquals(1, note.statusHistory().size(), "le message est journalisé");
        assertEquals("NOUVEAU", note.statusHistory().get(0).previousStatus());
        assertEquals("NOUVEAU", note.statusHistory().get(0).status());
        assertEquals("Client absent, rappellera demain matin", note.statusHistory().get(0).comment());
        assertEquals(1, note.row().noteCount(), "la ligne du tableau indique qu'un message a été laissé");

        // Statut inchangé SANS message : rien n'est écrit (pas de journal vide).
        assertEquals(1, service.updateStatus("s-credit", "NOUVEAU", "   ").orElseThrow()
                .statusHistory().size());
        assertEquals(1, service.updateStatus("s-credit", "NOUVEAU", null).orElseThrow()
                .statusHistory().size());

        // Le compteur de messages est aussi porté par la liste (colonne Statut du tableau).
        assertEquals(1, row(service.list(10, null, null, null, null), "s-credit").noteCount());
    }

    @Test
    void theStatusIsAFilterOfTheCallCenterWorkList() {
        save(dossier("s-nouveau", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));
        save(dossier("s-conclu", "DEMO002", "EPARGNE", "Épargne", 58, 2));
        save(dossier("s-contacte", "DEMO003", "ASSURANCE", "Assurance auto", 35, 3));
        service.updateStatus("s-conclu", "CONCLU", null);
        service.updateStatus("s-contacte", "CONTACTE", null);

        DirectoryModels.DirectoryList tous = service.list(10, null, null, null, null);
        assertEquals(3, tous.total());
        assertEquals(3, tous.statuses().size(), "les statuts présents sont renvoyés pour le filtre");

        assertEquals(1, service.list(10, null, null, null, null, "NOUVEAU").total());
        assertEquals("s-nouveau", service.list(10, null, null, null, null, "NOUVEAU").rows().get(0).sessionId());
        assertEquals(1, service.list(10, null, null, null, null, "CONCLU").total());
        assertEquals(0, service.list(10, null, null, null, null, "PERDU").total());
        assertEquals(3, service.list(10, null, null, null, null, "").total(),
                "un filtre vide n'exclut rien");
    }

    @Test
    void anUnknownStatusOrAnUnknownSessionIsRefused() {
        save(dossier("s-credit", "DEMO001", "CREDIT_CONSO", "Voiture", 82, 1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus("s-credit", "EN_COURS", null));
        assertTrue(error.getMessage().contains("Statut inconnu"), "aucun statut n'est deviné");
        assertEquals(0, service.detail("s-credit").orElseThrow().statusHistory().size(),
                "un refus n'écrit rien");

        assertEquals(Optional.empty(), service.updateStatus("s-inconnue", "CONTACTE", null));
    }

    @Test
    void aLegacyDossierWithoutScoreIsStillListed() {
        // Dossier écrit AVANT l'ajout du score / de l'identité client : il doit rester consultable, avec
        // des valeurs vides — jamais des valeurs inventées.
        dossierStore.save(new AdvisorFeedbackModels.AdvisorDossier("dossier-ancien",
                Instant.now().toString(), "s-ancien", "Projet ancien", List.of(), List.of(), List.of(),
                List.of(), new AdvisorFeedbackModels.DossierEmail("Sujet", "Corps"), null, null,
                Instant.now().toString()));

        DirectoryModels.DirectoryRow row = row(service.list(10, null, null, null, null), "s-ancien");

        assertNull(row.score());
        assertNull(row.customerId());
        assertEquals("Autre", row.categoryLabel(), "Sans produit, la catégorie reste « Autre »");
        assertEquals("Projet ancien", row.title(), "Le titre retombe sur le projet principal du dossier");
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static DirectoryModels.DirectoryRow row(DirectoryModels.DirectoryList list, String sessionId) {
        return list.rows().stream().filter(row -> sessionId.equals(row.sessionId())).findFirst().orElseThrow();
    }

    private void save(AdvisorFeedbackModels.AdvisorDossier dossier) {
        assertTrue(dossierStore.save(dossier).isPresent(), "Le dossier de test doit être persisté");
    }

    /** Dossier représentatif : identité client, score et transcript. */
    private static AdvisorFeedbackModels.AdvisorDossier dossier(String sessionId, String customerId,
                                                               String category, String title,
                                                               int score, int daysAgo) {
        String timestamp = Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString();
        return new AdvisorFeedbackModels.AdvisorDossier("dossier-" + sessionId, timestamp, sessionId, title,
                List.of(), List.of("préférence"),
                List.of(new AdvisorFeedbackModels.DossierProduct("p1", "Crédit Auto Expresso", "AUTO_LOAN",
                        "HIGH", "intérêt client", null)),
                List.of("Rappeler le client"),
                new AdvisorFeedbackModels.DossierEmail("Synthèse conseiller", "Synthèse conseiller : projet "
                        + title + "."),
                new AdvisorFeedbackModels.DossierEmail("Brouillon client", "Brouillon client à vérifier."),
                "http://localhost:9898/#/advisor-feedback/session/" + sessionId, timestamp,
                new AdvisorFeedbackModels.DossierClient(customerId, title, category, labelOf(category)),
                new AdvisorFeedbackModels.DossierScore(score, CommercialScoreService.priorityOf(score),
                        CommercialScoreService.labelOf(score), List.of("Projet identifié et chiffré."),
                        List.of("Maturité du projet : 27/30 — vehicle, 15000 EUR"), false),
                List.of(new AdvisorFeedbackModels.DossierMessage("user", "Bonjour", timestamp),
                        new AdvisorFeedbackModels.DossierMessage("assistant", "Bonjour, comment puis-je vous aider ?",
                                timestamp)));
    }

    private static String labelOf(String category) {
        return switch (category) {
            case "CREDIT_CONSO" -> "Crédit conso";
            case "CREDIT_IMMO" -> "Crédit immobilier";
            case "EPARGNE" -> "Épargne";
            case "ASSURANCE" -> "Assurance";
            default -> "Autre";
        };
    }
}
