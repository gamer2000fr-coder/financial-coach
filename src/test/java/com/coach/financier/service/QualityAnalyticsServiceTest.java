package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moteur analytique Qualité : calculs déterministes, séparation satisfaction / conformité,
 * croisement (cas C : insatisfait mais Coach conforme) et absence de statistiques inventées.
 */
class QualityAnalyticsServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private QualityFeedbackStore feedbackStore;
    private QualityCheckStore checkStore;
    private QualityAnalyticsService analytics;
    private QualityProperties properties;

    @BeforeEach
    void setUp() {
        properties = new QualityProperties(true, false, tempDir.toString(), "salt", 1000, 30, 3,
                "quality-report-v1", "", "HIGH", "HIGH", "HIGH", "MEDIUM", "MEDIUM", "LOW");
        feedbackStore = new QualityFeedbackStore(properties, objectMapper);
        checkStore = new QualityCheckStore(properties, objectMapper);
        analytics = new QualityAnalyticsService(feedbackStore, checkStore, properties);
    }

    @Test
    void emptyPeriod_inventsNoStatistics() {
        LocalDate today = LocalDate.now();

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                QualityModels.QualityFilter.none());

        assertEquals(0, aggregates.satisfaction().feedbackCount());
        assertEquals(0, aggregates.satisfaction().conversationsClosed());
        assertNull(aggregates.satisfaction().averageRating());
        assertNull(aggregates.satisfaction().participationRate(), "Aucune division par zéro");
        assertNull(aggregates.satisfaction().positiveRate());
        assertFalse(aggregates.satisfaction().sufficientSample());
        assertEquals(5, aggregates.ratingDistribution().size());
        assertTrue(aggregates.feedbackCategories().isEmpty());
        assertTrue(aggregates.commentThemes().isEmpty());
        assertTrue(aggregates.anonymizedComments().isEmpty());
        // Les contrôles implémentés sont listés avec 0 exécution ; les non implémentés sont absents.
        assertEquals(properties.enabledChecks().size(), aggregates.qualityChecks().size());
        assertTrue(aggregates.qualityChecks().stream().allMatch(check -> check.checksRun() == 0));
        assertEquals(0, aggregates.conformity().anomalies());
        assertTrue(aggregates.notImplementedChecks().contains(QualityModels.INVENTED_DATA));
    }

    @Test
    void computesSatisfactionKpisDistributionAndParticipation() {
        LocalDate today = LocalDate.now();
        // 4 conversations terminées (contrôles), 3 avis : 5, 4 et 2 étoiles.
        for (int index = 0; index < 4; index++) {
            checkStore.append(List.of(check("s" + index, QualityModels.EXCESSIVE_REPETITION, false, today)));
        }
        feedbackStore.appendDemo(List.of(
                feedback("f1", "s0", 5, List.of(), "Échanges clairs et utiles", today),
                feedback("f2", "s1", 4, List.of(), "", today),
                feedback("f3", "s2", 2, List.of(QualityModels.TOO_REPETITIVE), "Le Coach répétait les mêmes chiffres", today)));

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                QualityModels.QualityFilter.none());

        assertEquals(4, aggregates.satisfaction().conversationsClosed());
        assertEquals(3, aggregates.satisfaction().feedbackCount());
        assertEquals(0.75, aggregates.satisfaction().participationRate());
        assertEquals(3.6667, aggregates.satisfaction().averageRating());
        assertEquals(2, aggregates.satisfaction().positiveCount());
        assertEquals(1, aggregates.satisfaction().negativeCount());
        assertEquals(0.6667, aggregates.satisfaction().positiveRate());
        assertEquals(0.3333, aggregates.satisfaction().negativeRate());
        assertTrue(aggregates.satisfaction().sufficientSample());

        QualityModels.RatingBucket five = aggregates.ratingDistribution().stream()
                .filter(bucket -> bucket.rating() == 5).findFirst().orElseThrow();
        assertEquals(1, five.count());
        assertEquals(0.3333, five.share());

        QualityModels.ReasonMetric reason = aggregates.feedbackCategories().get(0);
        assertEquals(QualityModels.TOO_REPETITIVE, reason.reason());
        assertEquals("Les réponses étaient trop répétitives", reason.label());
        assertEquals(1, reason.count());

        // Thèmes de commentaires (heuristique locale, jamais une IA).
        assertTrue(aggregates.commentThemes().stream()
                        .anyMatch(theme -> QualityModels.TOO_REPETITIVE.equals(theme.theme())),
                "Le commentaire sur la répétition doit être thématisé");
        assertEquals(2, aggregates.anonymizedComments().size(), "Seuls les avis commentés sont transmis");
    }

    @Test
    void crossMatrix_distinguishesUnsatisfiedCompliantFromUnsatisfiedAnomaly() {
        LocalDate today = LocalDate.now();
        // s1 : note 1 MAIS Coach conforme (le client critique le refus de simuler un crédit).
        checkStore.append(List.of(check("s1", QualityModels.CREDIT_SIMULATION_VIOLATION, false, today)));
        // s2 : note 1 ET anomalie réelle confirmée.
        checkStore.append(List.of(check("s2", QualityModels.CREDIT_SIMULATION_VIOLATION, true, today)));
        // s3 : client satisfait mais anomalie détectée.
        checkStore.append(List.of(check("s3", QualityModels.PRODUCT_MISMATCH, true, today)));
        // s4 : satisfait et conforme, sans avis (anomalie non ressentie exclue ici).
        checkStore.append(List.of(check("s4", QualityModels.INVENTED_URL, false, today)));

        feedbackStore.appendDemo(List.of(
                feedback("f1", "s1", 1, List.of(QualityModels.ACTION_NOT_POSSIBLE), "Pas de mensualité fournie", today),
                feedback("f2", "s2", 1, List.of(QualityModels.ACTION_NOT_POSSIBLE), "Chiffres douteux", today),
                feedback("f3", "s3", 5, List.of(), "Très bien", today),
                feedback("f4", "s5", 5, List.of(), "Parfait", today)));

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                QualityModels.QualityFilter.none());
        QualityModels.SatisfactionComplianceMatrix matrix = aggregates.satisfactionVsCompliance();

        assertEquals(1, matrix.satisfiedCompliant());
        assertEquals(1, matrix.satisfiedAnomaly());
        assertEquals(1, matrix.unsatisfiedCompliant(), "Cas C : insatisfaction sans anomalie du Coach");
        assertEquals(1, matrix.unsatisfiedAnomaly(), "Cas D : cas prioritaire");
        assertEquals(0.5, matrix.unsatisfiedCompliantShare());
        assertEquals(0, matrix.unratedWithAnomaly(), "Toutes les sessions avec anomalie ont un avis ici");
    }

    @Test
    void badRatingNeverCreatesAQualityAnomaly() {
        LocalDate today = LocalDate.now();
        checkStore.append(List.of(check("s1", QualityModels.CREDIT_SIMULATION_VIOLATION, false, today)));
        feedbackStore.appendDemo(List.of(
                feedback("f1", "s1", 1, List.of(QualityModels.ACTION_NOT_POSSIBLE), "", today)));

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                QualityModels.QualityFilter.none());

        assertEquals(1, aggregates.satisfaction().negativeCount());
        assertEquals(0, aggregates.conformity().anomalies(),
                "Une mauvaise note ne devient JAMAIS une anomalie du Coach");
        assertEquals(properties.enabledChecks().size(), aggregates.qualityChecks().size(),
                "Seuls les contrôles IMPLÉMENTÉS sont exposés (avec leurs compteurs, jamais de faux KPI)");
        QualityModels.CheckMetric credit = aggregates.qualityChecks().stream()
                .filter(metric -> QualityModels.CREDIT_SIMULATION_VIOLATION.equals(metric.checkType()))
                .findFirst().orElseThrow();
        assertEquals(1, credit.checksRun());
        assertEquals(0, credit.detected());
    }

    @Test
    void invalidJsonlLineIsCountedAndSkipped() throws IOException {
        LocalDate today = LocalDate.now();
        Path dir = tempDir.resolve("feedback");
        Files.createDirectories(dir);
        String valid = objectMapper.writeValueAsString(
                feedback("f1", "s1", 4, List.of(), "", today));
        Files.writeString(dir.resolve("coach_feedback_" + today + ".jsonl"),
                "{ ligne corrompue\n" + valid + "\n", StandardCharsets.UTF_8);

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                QualityModels.QualityFilter.none());

        assertEquals(1, aggregates.invalidLines());
        assertEquals(1, aggregates.satisfaction().feedbackCount());
    }

    @Test
    void filterByRatingKeepsOnlyMatchingFeedback() {
        LocalDate today = LocalDate.now();
        feedbackStore.appendDemo(List.of(
                feedback("f1", "s1", 1, List.of(QualityModels.TOO_LONG), "Trop long", today),
                feedback("f2", "s2", 5, List.of(), "", today)));

        QualityModels.QualityAggregates aggregates = analytics.compute(today, today,
                new QualityModels.QualityFilter(null, null, 1, null));

        assertEquals(1, aggregates.satisfaction().feedbackCount());
        assertEquals("Trop long", aggregates.anonymizedComments().get(0).text());
    }

    @Test
    void duplicateFeedbackForSameSessionIsRejected() {
        LocalDate today = LocalDate.now();
        QualityModels.CoachFeedback feedback = feedback("f1", "s1", 4, List.of(), "", today);

        assertEquals(1, feedbackStore.appendDemo(List.of(feedback)).size());
        assertEquals(0, feedbackStore.appendDemo(List.of(feedback)).size(), "Aucun doublon au rejeu");
        assertNotNull(feedbackStore.findBySession("s1").orElse(null));
        assertEquals(1, analytics.compute(today, today, QualityModels.QualityFilter.none())
                .satisfaction().feedbackCount());
    }

    // ------------------------------------------------------------------ helpers

    private static QualityModels.CoachFeedback feedback(String id, String session, int rating,
                                                        List<String> reasons, String comment, LocalDate day) {
        return new QualityModels.CoachFeedback(id, day + "T10:00:00", session, "customer_hash_demo", rating,
                reasons, List.of(), null, null, null, comment, QualityModels.SOURCE_POPUP, day + "T10:00:01");
    }

    private static QualityModels.QualityCheck check(String session, String type, boolean detected, LocalDate day) {
        return new QualityModels.QualityCheck("chk-" + session + "-" + type, day + "T10:05:00", session, type,
                detected ? QualityModels.SEVERITY_HIGH : QualityModels.SEVERITY_MEDIUM, detected,
                QualityModels.SOURCE_AUTOMATIC, detected ? "Anomalie de test" : "Aucune anomalie",
                detected ? 0.8 : null);
    }
}
