package com.coach.financier.model;

import java.util.List;
import java.util.Map;

/**
 * Modèles du module MARKETING INTELLIGENCE (POC, sans base de données).
 * <p>
 * Chaîne : conversation → signaux IA ({@code marketingEvents}) → événements JSONL →
 * agrégations déterministes (code Java) → rapport IA marketing → API → page {@code #/marketing}.
 * <p>
 * Tous les CHIFFRES des agrégats sont calculés par le code, jamais par le LLM.
 */
public final class MarketingModels {
    private MarketingModels() {}

    // ------------------------------------------------------------------ types d'événements (§6)

    public static final String PROJECT_DETECTED = "PROJECT_DETECTED";
    public static final String PRODUCT_RECOMMENDED = "PRODUCT_RECOMMENDED";
    public static final String PRODUCT_INTEREST = "PRODUCT_INTEREST";
    public static final String PRODUCT_REJECTED = "PRODUCT_REJECTED";
    public static final String PRODUCT_COMPARISON = "PRODUCT_COMPARISON";
    public static final String SUBSCRIPTION_INTEREST = "SUBSCRIPTION_INTEREST";
    public static final String APPOINTMENT_INTEREST = "APPOINTMENT_INTEREST";
    public static final String ADVISOR_HANDOFF = "ADVISOR_HANDOFF";
    public static final String UNMET_NEED = "UNMET_NEED";
    public static final String MISSING_PRODUCT_INFORMATION = "MISSING_PRODUCT_INFORMATION";
    // Événements QA « qualité du parcours IA », à ne pas mélanger avec l'intérêt client (§6/§13).
    public static final String COACH_DATA_MISSING = "COACH_DATA_MISSING";
    public static final String COACH_PRODUCT_MISMATCH = "COACH_PRODUCT_MISMATCH";
    public static final String COACH_UNANSWERED_REQUEST = "COACH_UNANSWERED_REQUEST";

    public static final List<String> EVENT_TYPES = List.of(
            PROJECT_DETECTED, PRODUCT_RECOMMENDED, PRODUCT_INTEREST, PRODUCT_REJECTED, PRODUCT_COMPARISON,
            SUBSCRIPTION_INTEREST, APPOINTMENT_INTEREST, ADVISOR_HANDOFF, UNMET_NEED,
            MISSING_PRODUCT_INFORMATION, COACH_DATA_MISSING, COACH_PRODUCT_MISMATCH, COACH_UNANSWERED_REQUEST);

    public static boolean isCoachQuality(String eventType) {
        return COACH_DATA_MISSING.equals(eventType)
                || COACH_PRODUCT_MISMATCH.equals(eventType)
                || COACH_UNANSWERED_REQUEST.equals(eventType);
    }

    // ------------------------------------------------------------------ événements

    /**
     * Brouillon d'événement produit par l'IA de fin de conversation (même appel que le dossier de
     * suivi) : uniquement des signaux qualitatifs. Le backend ajoute les identifiants techniques
     * (eventId, session, client pseudonymisé, horodatage, versions, modèle).
     */
    public record MarketingEventDraft(
            String eventType,
            String projectType,
            String projectAmountRange,
            String productId,
            String productName,
            String productFamily,
            String interestLevel,
            String reasonCategory,
            String reason,
            Boolean advisorFollowUpRecommended,
            Double confidence
    ) {}

    /** Événement persisté : une ligne du fichier JSONL quotidien. */
    public record MarketingEvent(
            String eventId,
            String eventType,
            String timestamp,
            String sessionId,
            String anonymousCustomerId,
            String projectType,
            String projectAmountRange,
            String productId,
            String productName,
            String productFamily,
            String interestLevel,
            String reasonCategory,
            String reason,
            Boolean advisorFollowUpRecommended,
            Double confidence,
            String extractorVersion,
            String promptVersion,
            String model,
            String createdAt,
            Boolean demo
    ) {}

    // ------------------------------------------------------------------ agrégats (calculés par le code)

    /** KPI globaux (§19). */
    public record MarketingOverview(
            long conversationCount,
            long sessionsWithInterest,
            long uniqueCustomers,
            long highInterestCount,
            long subscriptionIntentCount,
            long appointmentRequestCount,
            long unmetNeedCount,
            long missingInformationCount,
            long coachQualityIssueCount
    ) {}

    /** Métriques par produit (§20/§24). */
    public record ProductMetric(
            String productId,
            String productName,
            String productFamily,
            long recommendedSessions,
            long interestedSessions,
            long uniqueInterestedCustomers,
            long highCount,
            long mediumCount,
            long lowCount,
            long rejectedCount,
            long comparisonCount,
            long subscriptionIntentCount,
            long appointmentRequestCount,
            Double interestRate,
            double score,
            Long previousInterestedSessions,
            Double evolutionPercent
    ) {}

    /** Compteur produit simple (projets). */
    public record ProductCount(String productId, String productName, long count) {}

    /** Métriques par projet (§21). */
    public record ProjectMetric(
            String projectType,
            long volume,
            Double share,
            Map<String, Long> amountRanges,
            long interestCount,
            long rejectedCount,
            long unmetNeedCount,
            List<ProductCount> topProducts,
            Long previousVolume,
            Double evolutionPercent
    ) {}

    /** Analyse des refus (§22). */
    public record RejectionMetric(
            String reasonCategory,
            String productId,
            String productName,
            long count,
            Double share
    ) {}

    /** Associations de produits (§23). */
    public record CrossSellMetric(
            String sourceProductId,
            String sourceProductName,
            String targetProductId,
            String targetProductName,
            long commonSessions,
            long sourceSessions,
            Double rate
    ) {}

    /** Besoins non couverts (§12). */
    public record UnmetNeedMetric(
            String projectType,
            String reasonCategory,
            String reason,
            long count,
            Double averageConfidence
    ) {}

    /** Informations produit manquantes (§13). */
    public record MissingInfoMetric(
            String productId,
            String productName,
            String reasonCategory,
            long count
    ) {}

    /** Filtres appliqués côté backend avant agrégation (§30). Champs {@code null} = non filtré. */
    public record MarketingFilter(
            String productId,
            String productFamily,
            String projectType,
            String interestLevel,
            String eventType
    ) {
        public static MarketingFilter none() {
            return new MarketingFilter(null, null, null, null, null);
        }

        public MarketingFilter {
            productId = blankToNull(productId);
            productFamily = blankToNull(productFamily);
            projectType = blankToNull(projectType);
            interestLevel = blankToNull(interestLevel);
            eventType = blankToNull(eventType);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        public boolean isEmpty() {
            return productId == null && productFamily == null && projectType == null
                    && interestLevel == null && eventType == null;
        }
    }

    /** Point de série temporelle (un jour). */
    public record DailyPoint(
            String date,
            long conversationCount,
            long interestCount,
            long highInterestCount,
            long subscriptionIntentCount,
            long appointmentRequestCount
    ) {}

    /** Tendance (courant vs période précédente), calculée par code (§25). */
    public record TrendMetric(
            String entityType,
            String entityId,
            String entityName,
            long current,
            long previous,
            Double evolutionPercent
    ) {}

    /** Agrégats complets d'une période (ce que consomment l'API et l'IA analyste). */
    public record MarketingAggregates(
            String dateFrom,
            String dateTo,
            MarketingOverview overview,
            List<ProductMetric> products,
            List<ProjectMetric> projects,
            List<RejectionMetric> rejections,
            List<CrossSellMetric> crossSell,
            List<UnmetNeedMetric> unmetNeeds,
            List<MissingInfoMetric> missingInformation,
            List<DailyPoint> series,
            List<TrendMetric> trends,
            boolean demo,
            long invalidEventLines,
            String generatedAt
    ) {}

    // ------------------------------------------------------------------ rapport IA marketing (§21)

    public record ReportItem(String title, String description, String importance) {}

    public record MainTrend(String type, String entityType, String entityId, String entityName, String observation) {}

    public record RecommendationPerformance(String productId, String productName, String observation) {}

    public record FrictionItem(String category, String observation) {}

    public record CrossSellInsight(String sourceProduct, String targetProduct, String observation) {}

    public record UnmetNeedInsight(String projectType, String observation) {}

    public record MissingInfoInsight(String productId, String productName, String observation) {}

    public record CoachQualityItem(String type, String observation) {}

    public record MarketingAlert(String level, String title, String description) {}

    public record MarketingOpportunity(String title, String description, String recommendation) {}

    /** Rapport marketing généré par l'IA analyste (chiffres fournis, jamais recalculés par l'IA). */
    public record MarketingReport(
            String reportDate,
            List<ReportItem> executiveSummary,
            List<MainTrend> mainTrends,
            List<RecommendationPerformance> recommendationPerformance,
            List<FrictionItem> customerFriction,
            List<CrossSellInsight> crossSellInsights,
            List<UnmetNeedInsight> unmetNeeds,
            List<MissingInfoInsight> missingProductInformation,
            List<CoachQualityItem> aiCoachQuality,
            List<MarketingAlert> alerts,
            List<MarketingOpportunity> opportunities,
            String finalSummary,
            String generatedAt,
            String model,
            Boolean aiGenerated,
            String error
    ) {}
}
