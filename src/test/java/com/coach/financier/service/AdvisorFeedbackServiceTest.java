package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.config.MarketingProperties;
import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.QualityModels;
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

/**
 * Saisie du feedback conseiller : facultatif par défaut, idempotent, versionné (historique conservé),
 * jamais bloquant, et conservation SÉPARÉE de la valeur IA et de la correction du conseiller.
 */
class AdvisorFeedbackServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private AdvisorFeedbackProperties properties;
    private AdvisorFeedbackStore store;
    private AdvisorFeedbackService service;

    @BeforeEach
    void setUp() {
        properties = new AdvisorFeedbackProperties(true, false, tempDir.toString(), "salt", 30, 3,
                "advisor-feedback-v1");
        store = new AdvisorFeedbackStore(properties, objectMapper);
        service = new AdvisorFeedbackService(store, properties,
                new AnonymousIdService(marketingProperties()));
    }

    @Test
    void positiveFeedbackNeedsNothingElse() {
        var response = service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest(
                "s-ok", AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(),
                null, null, null, null, null));

        assertEquals("SAVED", response.status());
        assertEquals(AdvisorFeedbackModels.EVENT_CREATED, response.event());
        assertEquals(1, response.version());
        var stored = store.latestBySession("s-ok").orElseThrow();
        assertTrue(stored.issues().isEmpty(), "Pas de détail exigé pour un feedback positif");
        assertEquals(AdvisorFeedbackModels.SOURCE_ADVISOR, stored.source());
        assertNotNull(stored.advisorIdHash());
        assertFalse(stored.advisorIdHash().contains("s-ok"), "Le conseiller n'est identifié que par un hash");
    }

    @Test
    void incorrectFeedbackKeepsAreasAndReasons() {
        var response = service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest(
                "s-ko", AdvisorFeedbackModels.INCORRECT,
                List.of(new AdvisorFeedbackModels.AdvisorIssue(AdvisorFeedbackModels.AREA_PROJECT_DETECTION,
                                AdvisorFeedbackModels.REASON_WRONG, "projet confondu"),
                        new AdvisorFeedbackModels.AdvisorIssue("zone-inconnue", "motif-inconnu", "")),
                List.of(), List.of(), null, AdvisorFeedbackModels.EMAIL_MAJOR, null, "Réécriture nécessaire", null));

        assertEquals("SAVED", response.status());
        var stored = store.latestBySession("s-ko").orElseThrow();
        assertEquals(2, stored.issues().size());
        assertEquals(AdvisorFeedbackModels.AREA_PROJECT_DETECTION, stored.issues().get(0).area());
        assertEquals(AdvisorFeedbackModels.AREA_OTHER, stored.issues().get(1).area(),
                "Une zone inconnue retombe sur OTHER");
        assertEquals(AdvisorFeedbackModels.REASON_OTHER, stored.issues().get(1).reason());
        assertEquals(AdvisorFeedbackModels.EMAIL_MAJOR, stored.clientEmailAssessment());
    }

    @Test
    void relevantFeedbackIgnoresAreasButKeepsComment() {
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-pos", AdvisorFeedbackModels.RELEVANT,
                List.of(new AdvisorFeedbackModels.AdvisorIssue(AdvisorFeedbackModels.AREA_SUMMARY, null, "")),
                List.of(), List.of(), null, null, null, "Bon travail", "conseiller-1"));

        var stored = store.latestBySession("s-pos").orElseThrow();
        assertTrue(stored.issues().isEmpty(), "Les zones ne sont demandées que pour un avis négatif");
        assertEquals("Bon travail", stored.comment());
    }

    @Test
    void interestCorrectionKeepsBothValues() {
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-level", AdvisorFeedbackModels.RELEVANT,
                List.of(),
                List.of(new AdvisorFeedbackModels.ProductFeedback("sg_pea", "PEA", "HIGH", null, "MEDIUM",
                        null, "")),
                List.of("sg_auto_tous_risques"), null, null, null, null, null));

        var product = store.latestBySession("s-level").orElseThrow().productFeedback().get(0);
        assertEquals("HIGH", product.aiInterestLevel(), "La valeur IA est conservée");
        assertEquals("MEDIUM", product.advisorInterestLevel(), "La correction du conseiller est conservée");
        assertEquals(AdvisorFeedbackModels.PRODUCT_RELEVANT, product.advisorAssessment(),
                "Une correction de niveau est enregistrée comme intérêt toujours pertinent");
        assertEquals(List.of("sg_auto_tous_risques"),
                store.latestBySession("s-level").orElseThrow().missingProductIds());
    }

    @Test
    void doubleClickIsNotDuplicated() {
        AdvisorFeedbackModels.AdvisorFeedbackRequest request = new AdvisorFeedbackModels.AdvisorFeedbackRequest(
                "s-dbl", AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(),
                null, null, null, null, null);

        assertEquals("SAVED", service.submit(request).status());
        var second = service.submit(request);
        assertEquals("DUPLICATE", second.status(), "Double clic / retry : aucun doublon");
        assertEquals(1, second.version(), "La version courante reste la première");
        assertEquals(1, store.read(null, null).feedback().size());
    }

    @Test
    void revisionCreatesANewVersionAndKeepsHistory() {
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-rev",
                AdvisorFeedbackModels.NEEDS_IMPROVEMENT, List.of(), List.of(), List.of(),
                null, AdvisorFeedbackModels.EMAIL_MINOR, "MINOR_EDIT", "première version", null));
        var second = service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-rev",
                AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(),
                null, AdvisorFeedbackModels.EMAIL_READY, "NO_EDIT", "corrigé après échange", null));

        assertEquals(2, second.version());
        assertEquals(AdvisorFeedbackModels.EVENT_UPDATED, second.event());
        assertEquals(2, store.read(null, null).feedback().size(), "L'historique des versions est conservé");
        assertEquals(AdvisorFeedbackModels.RELEVANT, store.latestBySession("s-rev").orElseThrow()
                .overallAssessment(), "La version courante est la dernière");
    }

    @Test
    void storageFailureDoesNotBreakTheAdvisorDossier() {
        AdvisorFeedbackStore failing = org.mockito.Mockito.mock(AdvisorFeedbackStore.class);
        org.mockito.Mockito.when(failing.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AdvisorFeedbackStore.SaveResult(false, false, null, "disque plein"));
        org.mockito.Mockito.when(failing.nextVersion("s-err", null)).thenReturn(1);
        AdvisorFeedbackService failingService = new AdvisorFeedbackService(failing, properties,
                new AnonymousIdService(marketingProperties()));

        var response = failingService.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest(
                "s-err", AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(),
                null, null, null, null, null));

        assertEquals("STORAGE_ERROR", response.status());
        assertNotNull(response.message());
    }

    @Test
    void invalidRequestsAreRejectedWithoutWriting() {
        assertEquals("INVALID", service.submit(null).status());
        assertEquals("INVALID", service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest(
                "s-x", null, List.of(), List.of(), List.of(), null, null, null, null, null)).status());
        assertTrue(store.read(null, null).feedback().isEmpty());
    }

    @Test
    void commentsAreSanitizedAndTruncated() {
        // Longueur confortable pour vérifier le masquage ; la troncature est vérifiée séparément ci-dessous.
        AdvisorFeedbackProperties large = new AdvisorFeedbackProperties(true, false, tempDir.toString(), "salt",
                1000, 3, "advisor-feedback-v1");
        AdvisorFeedbackService withLargeComment = new AdvisorFeedbackService(store, large,
                new AnonymousIdService(marketingProperties()));
        withLargeComment.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-clean",
                AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(), null, null, null,
                "Rappeler le conseiller au 06 12 34 56 78 ou jean.dupont@example.com", null));

        String comment = store.latestBySession("s-clean").orElseThrow().comment();
        assertFalse(comment.contains("06 12 34 56 78"), "Téléphone masqué");
        assertFalse(comment.contains("jean.dupont@example.com"), "Email masqué");
        assertTrue(comment.contains("masqué"), "Les données personnelles sont masquées avant stockage");

        // Troncature : la longueur maximale configurée est respectée.
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-short",
                AdvisorFeedbackModels.RELEVANT, List.of(), List.of(), List.of(), null, null, null,
                "a".repeat(120), null));
        assertEquals(31, store.latestBySession("s-short").orElseThrow().comment().length(),
                "Commentaire borné par app.advisor-feedback.comment-max-length");
    }

    @Test
    void feedbackIsWrittenInADailyJsonlFile() {
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-file", AdvisorFeedbackModels.RELEVANT,
                List.of(), List.of(), List.of(), null, null, null, null, null));

        Path file = tempDir.resolve("events").resolve("advisor_feedback_" + LocalDate.now() + ".jsonl");
        assertTrue(java.nio.file.Files.isRegularFile(file),
                "Format imposé : events/advisor_feedback_YYYY-MM-DD.jsonl");
    }

    @Test
    void analyticsIgnorePreviousVersionsOfTheSameSession() {
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-agg", AdvisorFeedbackModels.INCORRECT,
                List.of(), List.of(), List.of(), null, null, null, null, null));
        service.submit(new AdvisorFeedbackModels.AdvisorFeedbackRequest("s-agg", AdvisorFeedbackModels.RELEVANT,
                List.of(), List.of(), List.of(), null, null, null, null, null));

        QualityProperties qualityProperties = new QualityProperties(true, false, tempDir.toString(), "salt",
                1000, 30, 10, "quality-report-v1", "", "", "", "", "", "", "");
        AdvisorFeedbackAnalyticsService analytics = new AdvisorFeedbackAnalyticsService(store, properties,
                new QualityCheckStore(qualityProperties, objectMapper));
        var aggregates = analytics.compute(LocalDate.now(), LocalDate.now(),
                AdvisorFeedbackModels.AdvisorFeedbackFilter.none());

        assertEquals(1, aggregates.kpis().feedbackCount(),
                "Seule la version courante est agrégée (la révision ne compte pas deux fois)");
        assertEquals(1.0, aggregates.kpis().relevantRate());
        assertEquals(0.0, aggregates.kpis().incorrectRate(),
                "Après révision, le taux « incorrect » retombe à 0 (la version précédente est ignorée)");
        assertEquals(1, aggregates.kpis().sessionsEvaluated());
    }

    @Test
    void emptyPeriodInventsNoStatistics() {
        QualityProperties qualityProperties = new QualityProperties(true, false, tempDir.toString(), "salt",
                1000, 30, 10, "quality-report-v1", "", "", "", "", "", "", "");
        AdvisorFeedbackAnalyticsService analytics = new AdvisorFeedbackAnalyticsService(store, properties,
                new QualityCheckStore(qualityProperties, objectMapper));

        var aggregates = analytics.compute(LocalDate.now(), LocalDate.now(),
                AdvisorFeedbackModels.AdvisorFeedbackFilter.none());

        assertEquals(0, aggregates.kpis().feedbackCount());
        assertNull(aggregates.kpis().relevantRate(), "Aucune division par zéro");
        assertNull(aggregates.kpis().participationRate());
        assertFalse(aggregates.kpis().sufficientSample());
        assertTrue(aggregates.products().isEmpty());
        assertTrue(aggregates.emailQuality().isEmpty());
        assertTrue(aggregates.areas().isEmpty());
        assertEquals(3, aggregates.assessments().size(), "Les 3 évaluations sont exposées avec 0");
        assertFalse(aggregates.assessments().isEmpty());
        assertEquals(QualityModels.SEVERITY_HIGH,
                new QualityProperties(true, false, tempDir.toString(), "salt", 1000, 30, 10,
                        "quality-report-v1", "", "", "", "", "", "", "").severityFor("PRODUCT_MISMATCH"),
                "Les deux modules restent indépendants (sévérités du module Qualité inchangées)");
    }

    private static MarketingProperties marketingProperties() {
        return new MarketingProperties(true, false, "./target/marketing-test", "salt",
                "2000,5000,10000,15000,30000", "marketing-events-v1", "marketing-extractor-v1",
                1, 0, 2, 3, 2, 4, 5, -5);
    }
}
