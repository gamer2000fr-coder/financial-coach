package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.MarketingModels;
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

/** Tests du moteur analytique Marketing : cas limites et agrégations déterministes (§51). */
class MarketingAnalyticsServiceTest {

    @TempDir
    Path tempDir;

    private MarketingEventStore store;
    private MarketingAnalyticsService analytics;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        MarketingProperties properties = new MarketingProperties(true, false, tempDir.toString(), "salt",
                "2000,5000,10000,15000,30000", "marketing-events-v1", "marketing-extractor-v1",
                1, 0, 2, 3, 2, 4, 5, -5);
        store = new MarketingEventStore(properties, objectMapper);
        analytics = new MarketingAnalyticsService(store, properties);
    }

    @Test
    void emptyStore_producesEmptyAggregatesWithoutDivisionByZero() {
        MarketingModels.MarketingAggregates aggregates = analytics.compute(
                LocalDate.now(), LocalDate.now(), MarketingModels.MarketingFilter.none());

        assertEquals(0, aggregates.overview().conversationCount());
        assertTrue(aggregates.products().isEmpty());
        assertTrue(aggregates.rejections().isEmpty());
        assertEquals(0, aggregates.invalidEventLines());
        // Aucune division par zéro : les séries couvrent bien la période d'un jour.
        assertEquals(1, aggregates.series().size());
    }

    @Test
    void computesOverviewProductScoringCrossSellAndRejections() {
        LocalDate today = LocalDate.now();
        store.append(List.of(
                event("e1", "s1", MarketingModels.PROJECT_DETECTED, "VEHICLE", "10000_15000",
                        null, null, null, null, null, today),
                event("e2", "s1", MarketingModels.PRODUCT_RECOMMENDED, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", null, null, today),
                event("e3", "s1", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", "HIGH", "DETAIL_REQUEST", today),
                event("e4", "s1", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p2", "Assurance Auto", "INSURANCE_AUTO", "MEDIUM", "PRICE_REQUEST", today),
                event("e5", "s2", MarketingModels.PRODUCT_REJECTED, "VEHICLE", null,
                        "p3", "Crédit Expresso", "PERSONAL_LOAN", null, "PRICE", today)));

        MarketingModels.MarketingAggregates aggregates = analytics.compute(
                today, today, MarketingModels.MarketingFilter.none());

        assertEquals(2, aggregates.overview().conversationCount());
        assertEquals(1, aggregates.overview().sessionsWithInterest());
        assertEquals(1, aggregates.overview().highInterestCount());
        assertEquals(1, aggregates.overview().uniqueCustomers());

        MarketingModels.ProductMetric p1 = product(aggregates, "p1");
        assertEquals(1, p1.recommendedSessions());
        assertEquals(1, p1.interestedSessions());
        assertEquals(1.0, p1.interestRate());
        assertEquals(4.0, p1.score()); // recommandé (+1) + intérêt HIGH (+3)

        assertFalse(aggregates.crossSell().isEmpty(), "Prêt Auto + Assurance Auto = association");
        assertEquals("PRICE", aggregates.rejections().get(0).reasonCategory());
        assertEquals(1, aggregates.projects().size());
        assertEquals("VEHICLE", aggregates.projects().get(0).projectType());
    }

    @Test
    void invalidJsonLineIsIgnoredAndCounted() throws IOException {
        Path dir = tempDir.resolve("events");
        Files.createDirectories(dir);
        LocalDate today = LocalDate.now();
        String valid = objectMapper.writeValueAsString(event("e1", "s1", MarketingModels.PROJECT_DETECTED,
                "SAVINGS", null, null, null, null, null, null, today));
        Files.writeString(dir.resolve("marketing_events_" + today + ".jsonl"),
                "{ ligne corrompue\n" + valid + "\n", StandardCharsets.UTF_8);

        MarketingModels.MarketingAggregates aggregates = analytics.compute(
                today, today, MarketingModels.MarketingFilter.none());

        assertEquals(1, aggregates.invalidEventLines());
        assertEquals(1, aggregates.overview().conversationCount());
    }

    @Test
    void appendIsIdempotentForTheSameEventId() {
        LocalDate today = LocalDate.now();
        MarketingModels.MarketingEvent event = event("same-id", "s1", MarketingModels.PROJECT_DETECTED,
                "TRAVEL", null, null, null, null, null, null, today);

        assertEquals(1, store.append(List.of(event)).size());
        assertEquals(0, store.append(List.of(event)).size(), "relance : aucun doublon");

        MarketingModels.MarketingAggregates aggregates = analytics.compute(
                today, today, MarketingModels.MarketingFilter.none());
        assertEquals(1, aggregates.overview().conversationCount());
    }

    @Test
    void filtersByProductAndInterestLevel() {
        LocalDate today = LocalDate.now();
        store.append(List.of(
                event("e1", "s1", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", "HIGH", "DETAIL_REQUEST", today),
                event("e2", "s2", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p2", "Assurance Auto", "INSURANCE_AUTO", "MEDIUM", "PRICE_REQUEST", today)));

        MarketingModels.MarketingAggregates onlyP1 = analytics.compute(today, today,
                new MarketingModels.MarketingFilter("p1", null, null, null, null));
        assertEquals(1, onlyP1.overview().highInterestCount());
        assertEquals("p1", onlyP1.products().get(0).productId());

        MarketingModels.MarketingAggregates onlyHigh = analytics.compute(today, today,
                new MarketingModels.MarketingFilter(null, null, null, "HIGH", null));
        assertEquals(1, onlyHigh.overview().conversationCount());
    }

    @Test
    void productWithoutRecommendationHasUndefinedInterestRate() {
        LocalDate today = LocalDate.now();
        store.append(List.of(event("e1", "s1", MarketingModels.PRODUCT_INTEREST, "SAVINGS", null,
                "p9", "Livret A", "SAVINGS_PRODUCT", "HIGH", "DETAIL_REQUEST", today)));

        MarketingModels.ProductMetric metric = product(analytics.compute(today, today,
                MarketingModels.MarketingFilter.none()), "p9");
        assertEquals(0, metric.recommendedSessions());
        assertNull(metric.interestRate(), "Pas de division par zéro : taux non calculable");
    }

    @Test
    void lowConfidenceEventIsKeptButConfidenceIsPreserved() throws IOException {
        LocalDate today = LocalDate.now();
        Path dir = tempDir.resolve("events");
        Files.createDirectories(dir);
        MarketingModels.MarketingEvent low = new MarketingModels.MarketingEvent("e-low", "UNMET_NEED",
                today + "T10:00:00", "s1", "customer_hash_x", "ELECTRONICS", null, null, null, null, null,
                "NO_SUITABLE_PRODUCT", "Besoin de financement très court.", null, 0.3,
                "marketing-events-v1", "marketing-extractor-v1", "MOCK", today + "T10:00:01", Boolean.FALSE);
        Files.writeString(dir.resolve("marketing_events_" + today + ".jsonl"),
                objectMapper.writeValueAsString(low) + "\n", StandardCharsets.UTF_8);

        MarketingModels.MarketingAggregates aggregates = analytics.compute(
                today, today, MarketingModels.MarketingFilter.none());

        assertEquals(1, aggregates.overview().unmetNeedCount());
        MarketingModels.UnmetNeedMetric need = aggregates.unmetNeeds().get(0);
        assertEquals(1, need.count());
        assertEquals(0.3, need.averageConfidence());
    }

    @Test
    void multipleDaysProduceSeriesAndTrends() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        store.append(List.of(
                event("d1", "s1", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", "HIGH", "DETAIL_REQUEST", yesterday),
                event("d2", "s2", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", "HIGH", "DETAIL_REQUEST", today),
                event("d3", "s3", MarketingModels.PRODUCT_INTEREST, "VEHICLE", null,
                        "p1", "Prêt Auto", "AUTO_LOAN", "MEDIUM", "PRICE_REQUEST", today)));

        MarketingModels.MarketingAggregates both = analytics.compute(
                yesterday, today, MarketingModels.MarketingFilter.none());

        assertEquals(2, both.series().size());
        assertEquals(3, both.overview().conversationCount());

        // Période courante = aujourd'hui (2 sessions intéressées), période précédente = hier (1 session).
        MarketingModels.MarketingAggregates todayOnly = analytics.compute(
                today, today, MarketingModels.MarketingFilter.none());
        assertEquals(1, todayOnly.series().size());

        MarketingModels.TrendMetric trend = both.trends().stream()
                .filter(item -> "PRODUCT".equals(item.entityType()) && "p1".equals(item.entityId()))
                .findFirst().orElse(null);
        assertNotNull(trend);
        assertEquals(3, trend.current());
        assertEquals(0, trend.previous());
        assertNull(trend.evolutionPercent(), "Aucune division par zéro : base précédente nulle");

        MarketingModels.TrendMetric dailyTrend = todayOnly.trends().stream()
                .filter(item -> "PRODUCT".equals(item.entityType()) && "p1".equals(item.entityId()))
                .findFirst().orElse(null);
        assertNotNull(dailyTrend);
        assertEquals(2, dailyTrend.current());
        assertEquals(1, dailyTrend.previous());
        assertEquals(100.0, dailyTrend.evolutionPercent());
    }

    // ------------------------------------------------------------------ helpers

    private static MarketingModels.ProductMetric product(MarketingModels.MarketingAggregates aggregates,
                                                         String productId) {
        return aggregates.products().stream()
                .filter(item -> productId.equals(item.productId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Produit absent : " + productId));
    }

    private static MarketingModels.MarketingEvent event(String id, String session, String type, String project,
                                                        String range, String productId, String productName,
                                                        String family, String interestLevel, String reason,
                                                        LocalDate day) {
        return new MarketingModels.MarketingEvent(id, type, day + "T10:00:00", session, "customer_hash_demo",
                project, range, productId, productName, family, interestLevel, reason, null, null,
                interestLevel == null ? 0.8 : 0.95, "marketing-events-v1", "marketing-extractor-v1", "MOCK",
                day + "T10:00:01", Boolean.FALSE);
    }
}
