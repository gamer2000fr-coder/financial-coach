package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pop-in de satisfaction : caractère FACULTATIF (Passer), idempotence (§44) et robustesse (§43) —
 * un échec d'enregistrement ne doit jamais empêcher la clôture de la conversation.
 */
class QualityFeedbackServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private QualityProperties properties;
    private QualityFeedbackStore store;
    private QualityFeedbackService service;

    @BeforeEach
    void setUp() {
        properties = new QualityProperties(true, false, tempDir.toString(), "salt", 20, 30, 10,
                "quality-report-v1", "", "", "", "", "", "", "");
        store = new QualityFeedbackStore(properties, objectMapper);
        service = new QualityFeedbackService(store, properties,
                new AnonymousIdService(marketingProperties()), mock(BankingDataRepository.class));
    }

    @Test
    void positiveRatingNeedsNoReasonAndKeepsOptionalComment() {
        QualityFeedbackService.FeedbackResponse response = service.submit("s-5",
                new QualityModels.FeedbackRequest(5, List.of(QualityModels.TOO_LONG), "  Bravo  "));

        assertEquals("SAVED", response.status());
        assertTrue(response.selectedReasons().isEmpty(), "Note positive : aucun motif n'est demandé (§7)");
        QualityModels.CoachFeedback stored = store.findBySession("s-5").orElseThrow();
        assertEquals(5, stored.rating());
        assertTrue(stored.positive());
        assertTrue(stored.aiDetectedReasons().isEmpty());
        assertEquals("Bravo", stored.comment(), "Le commentaire est nettoyé et conservé");
    }

    @Test
    void lowRatingKeepsNormalisedReasons() {
        QualityFeedbackService.FeedbackResponse response = service.submit("s-1",
                new QualityModels.FeedbackRequest(1,
                        List.of("too-repetitive", QualityModels.MISSING_INFORMATION, "INCONNU"), ""));

        assertEquals("SAVED", response.status());
        assertEquals(List.of(QualityModels.TOO_REPETITIVE, QualityModels.MISSING_INFORMATION,
                QualityModels.OTHER), response.selectedReasons(),
                "Les motifs sont normalisés, un code inconnu devient OTHER");
        assertEquals("", store.findBySession("s-1").orElseThrow().comment());
    }

    @Test
    void passMeansNoRatingAndNothingStored() {
        QualityFeedbackService.FeedbackResponse response = service.submit("s-pass", null);

        assertEquals("SKIPPED", response.status());
        assertNull(response.feedbackId());
        assertTrue(store.read(null, null).feedback().isEmpty(), "« Passer » n'enregistre rien");
    }

    @Test
    void invalidRatingIsRejected() {
        assertEquals("INVALID", service.submit("s-x", new QualityModels.FeedbackRequest(7, List.of(), "")).status());
        assertEquals("INVALID", service.submit("", new QualityModels.FeedbackRequest(4, List.of(), "")).status());
    }

    @Test
    void doubleClickProducesASingleStoredFeedback() {
        QualityFeedbackService.FeedbackResponse first = service.submit("s-dbl",
                new QualityModels.FeedbackRequest(4, List.of(), ""));
        QualityFeedbackService.FeedbackResponse second = service.submit("s-dbl",
                new QualityModels.FeedbackRequest(4, List.of(), ""));

        assertEquals("SAVED", first.status());
        assertEquals("DUPLICATE", second.status(), "Double clic / retry : aucun doublon (§44)");
        assertEquals(first.feedbackId(), second.feedbackId(), "L'identifiant est déterminé par la session");
        assertEquals(1, store.read(null, null).feedback().size());
    }

    @Test
    void storageFailureDoesNotBlockTheConversation() {
        QualityFeedbackStore failing = mock(QualityFeedbackStore.class);
        when(failing.save(any())).thenReturn(new QualityFeedbackStore.SaveResult(false, false, null, "disque plein"));
        QualityFeedbackService failingService = new QualityFeedbackService(failing, properties,
                new AnonymousIdService(marketingProperties()), mock(BankingDataRepository.class));

        QualityFeedbackService.FeedbackResponse response = failingService.submit("s-err",
                new QualityModels.FeedbackRequest(2, List.of(QualityModels.TOO_LONG), "trop long"));

        assertEquals("STORAGE_ERROR", response.status());
        assertNotNull(response.feedbackId(), "L'avis reste identifiable pour un diagnostic");
        assertFalse(response.status().startsWith("5"), "Aucun statut bloquant pour le client");
    }

    @Test
    void longCommentsAreTruncated() {
        String comment = "a".repeat(120);
        service.submit("s-long", new QualityModels.FeedbackRequest(3, List.of(), comment));

        String stored = store.findBySession("s-long").orElseThrow().comment();
        assertEquals(21, stored.length(), "Commentaire borné par app.quality.comment-max-length");
    }

    @Test
    void customerReferenceIsPseudonymisedNeverStoredInClear() throws Exception {
        // Dépôt réel construit sur un fichier temporaire : la référence client ne doit jamais
        // apparaître en clair dans le feedback stocké (§45).
        java.nio.file.Path bankingFile = tempDir.resolve("banking.json");
        java.nio.file.Files.writeString(bankingFile, "{\"customer\":{\"customerId\":\"CUSTOMER-42\"}}");
        BankingDataRepository repository = new BankingDataRepository(objectMapper, bankingFile.toString());
        QualityFeedbackService withRepository = new QualityFeedbackService(store, properties,
                new AnonymousIdService(marketingProperties()), repository);

        withRepository.submit("s-hash", new QualityModels.FeedbackRequest(5, List.of(), ""));

        String anonymousId = store.findBySession("s-hash").orElseThrow().anonymousCustomerId();
        assertNotNull(anonymousId);
        assertTrue(anonymousId.startsWith("customer_hash_"));
        assertFalse(anonymousId.contains("CUSTOMER-42"));
    }

    @Test
    void feedbackIsWrittenInADailyJsonlFile() {
        service.submit("s-file", new QualityModels.FeedbackRequest(5, List.of(), ""));

        Path file = tempDir.resolve("feedback")
                .resolve("coach_feedback_" + LocalDate.now() + ".jsonl");
        assertTrue(java.nio.file.Files.isRegularFile(file),
                "Format imposé : feedback/coach_feedback_YYYY-MM-DD.jsonl");
    }

    private static com.coach.financier.config.MarketingProperties marketingProperties() {
        return new com.coach.financier.config.MarketingProperties(true, false, "./target/marketing-test", "salt",
                "2000,5000,10000,15000,30000", "marketing-events-v1", "marketing-extractor-v1",
                1, 0, 2, 3, 2, 4, 5, -5);
    }
}
