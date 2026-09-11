package com.coach.financier.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modèles du module <b>Qualité &amp; Satisfaction du Coach IA</b>.
 * <p>
 * Deux dimensions doivent rester STRICTEMENT séparées (règle absolue du module) :
 * <ul>
 *   <li><b>Satisfaction client</b> : ce que le client a ressenti (note, motifs, commentaire) ;</li>
 *   <li><b>Qualité / conformité du Coach</b> : ce que les contrôles automatiques ont constaté.</li>
 * </ul>
 * Une mauvaise note n'est JAMAIS convertie automatiquement en anomalie du Coach.
 */
public final class QualityModels {

    private QualityModels() {
    }

    // ------------------------------------------------------------------ motifs de feedback client

    public static final String NOT_ANSWERING_QUESTION = "NOT_ANSWERING_QUESTION";
    public static final String HARD_TO_UNDERSTAND = "HARD_TO_UNDERSTAND";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String TOO_REPETITIVE = "TOO_REPETITIVE";
    public static final String PRODUCT_NOT_RELEVANT = "PRODUCT_NOT_RELEVANT";
    public static final String MISSING_INFORMATION = "MISSING_INFORMATION";
    public static final String ACTION_NOT_POSSIBLE = "ACTION_NOT_POSSIBLE";
    public static final String OTHER = "OTHER";

    /** Motifs proposés au client (ordre d'affichage), quand la note est basse (§6). */
    public static final List<String> FEEDBACK_REASONS = List.of(
            NOT_ANSWERING_QUESTION, HARD_TO_UNDERSTAND, TOO_LONG, TOO_REPETITIVE,
            PRODUCT_NOT_RELEVANT, MISSING_INFORMATION, ACTION_NOT_POSSIBLE, OTHER);

    /**
     * Libellés client : conservés SÉPARÉMENT des codes pour pouvoir être reformulés sans changer
     * le modèle analytique (le code reste la clé de stockage et d'agrégation).
     */
    private static final Map<String, String> REASON_LABELS = Map.of(
            NOT_ANSWERING_QUESTION, "La réponse ne répondait pas à ma question",
            HARD_TO_UNDERSTAND, "Les explications étaient difficiles à comprendre",
            TOO_LONG, "Les réponses étaient trop longues",
            TOO_REPETITIVE, "Les réponses étaient trop répétitives",
            PRODUCT_NOT_RELEVANT, "Les produits proposés ne correspondaient pas à mon besoin",
            MISSING_INFORMATION, "Il manquait des informations",
            ACTION_NOT_POSSIBLE, "Je n'ai pas pu réaliser l'action souhaitée",
            OTHER, "Autre");

    public static Map<String, String> reasonLabels() {
        return new LinkedHashMap<>(REASON_LABELS);
    }

    public static String reasonLabel(String code) {
        if (code == null) {
            return "";
        }
        return REASON_LABELS.getOrDefault(code.trim().toUpperCase(Locale.ROOT), code);
    }

    /** Normalise un motif reçu (tolérant : code inconnu ou vide → {@link #OTHER}). */
    public static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return FEEDBACK_REASONS.contains(code) ? code : OTHER;
    }

    // ------------------------------------------------------------------ contrôles automatiques

    public static final String PRODUCT_MISMATCH = "PRODUCT_MISMATCH";
    public static final String UNANSWERED_REQUEST = "UNANSWERED_REQUEST";
    public static final String MISSING_DATA_NOT_RETRIEVED = "MISSING_DATA_NOT_RETRIEVED";
    public static final String UNNECESSARY_ADVISOR_REDIRECT = "UNNECESSARY_ADVISOR_REDIRECT";
    public static final String EXCESSIVE_REPETITION = "EXCESSIVE_REPETITION";
    public static final String CREDIT_SIMULATION_VIOLATION = "CREDIT_SIMULATION_VIOLATION";
    public static final String UNSUPPORTED_PRODUCT_CLAIM = "UNSUPPORTED_PRODUCT_CLAIM";
    public static final String INVENTED_DATA = "INVENTED_DATA";
    public static final String INVENTED_URL = "INVENTED_URL";
    public static final String CONVERSATION_CONTEXT_LOST = "CONVERSATION_CONTEXT_LOST";

    /** Tous les contrôles décrits par la spécification (implémentés ou non). */
    public static final List<String> CHECK_TYPES = List.of(
            PRODUCT_MISMATCH, UNANSWERED_REQUEST, MISSING_DATA_NOT_RETRIEVED, UNNECESSARY_ADVISOR_REDIRECT,
            EXCESSIVE_REPETITION, CREDIT_SIMULATION_VIOLATION, UNSUPPORTED_PRODUCT_CLAIM,
            INVENTED_DATA, INVENTED_URL, CONVERSATION_CONTEXT_LOST);

    private static final Map<String, String> CHECK_LABELS = Map.of(
            PRODUCT_MISMATCH, "Produit incompatible",
            UNANSWERED_REQUEST, "Demande non résolue",
            MISSING_DATA_NOT_RETRIEVED, "Donnée demandée non récupérée",
            UNNECESSARY_ADVISOR_REDIRECT, "Redirection conseiller inutile",
            EXCESSIVE_REPETITION, "Répétition excessive",
            CREDIT_SIMULATION_VIOLATION, "Simulation de crédit interdite",
            UNSUPPORTED_PRODUCT_CLAIM, "Promesse produit non étayée",
            INVENTED_DATA, "Donnée inventée",
            INVENTED_URL, "URL inventée",
            CONVERSATION_CONTEXT_LOST, "Contexte de conversation perdu");

    public static Map<String, String> checkLabels() {
        return new LinkedHashMap<>(CHECK_LABELS);
    }

    public static String checkLabel(String checkType) {
        if (checkType == null) {
            return "";
        }
        return CHECK_LABELS.getOrDefault(checkType.trim().toUpperCase(Locale.ROOT), checkType);
    }

    public static boolean isKnownCheck(String checkType) {
        return checkType != null && CHECK_TYPES.contains(checkType.trim().toUpperCase(Locale.ROOT));
    }

    public static final String SEVERITY_LOW = "LOW";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_HIGH = "HIGH";
    public static final List<String> SEVERITIES = List.of(SEVERITY_LOW, SEVERITY_MEDIUM, SEVERITY_HIGH);

    public static String normalizeSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEVERITY_MEDIUM;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return SEVERITIES.contains(value) ? value : SEVERITY_MEDIUM;
    }

    public static final String SOURCE_POPUP = "END_CONVERSATION_POPUP";
    public static final String SOURCE_AUTOMATIC = "AUTOMATIC_CHECK";
    public static final String SOURCE_DEMO = "DEMO";

    // ------------------------------------------------------------------ feedback client

    /** Corps reçu depuis la pop-in de fin de conversation (§3). */
    public record FeedbackRequest(Integer rating, List<String> selectedReasons, String comment) {
        public FeedbackRequest {
            selectedReasons = selectedReasons == null ? List.of() : List.copyOf(selectedReasons);
        }
    }

    /**
     * Feedback stocké (anonymisé).
     *
     * @param customerSelectedReasons motifs EXPLICITEMENT choisis par le client
     * @param aiDetectedReasons       motifs détectés par une analyse IA du commentaire — champ prévu par
     *                                la spécification (§13) mais NON alimenté dans cette version (P2) :
     *                                il reste vide et les thèmes affichés proviennent d'une heuristique
     *                                locale déterministe, jamais d'une IA.
     */
    public record CoachFeedback(
            String feedbackId,
            String timestamp,
            String sessionId,
            String anonymousCustomerId,
            Integer rating,
            List<String> customerSelectedReasons,
            List<String> aiDetectedReasons,
            String aiSentiment,
            String aiSummary,
            Double aiConfidence,
            String comment,
            String source,
            String createdAt) {

        public CoachFeedback {
            customerSelectedReasons = customerSelectedReasons == null
                    ? List.of() : List.copyOf(customerSelectedReasons);
            aiDetectedReasons = aiDetectedReasons == null ? List.of() : List.copyOf(aiDetectedReasons);
        }

        /** Note 4 ou 5. */
        public boolean positive() {
            return rating != null && rating >= 4;
        }

        /** Note 1 ou 2. */
        public boolean negative() {
            return rating != null && rating <= 2;
        }

        /** Note 3 (neutre). */
        public boolean neutral() {
            return rating != null && rating == 3;
        }

        public boolean hasComment() {
            return comment != null && !comment.isBlank();
        }
    }

    /** Commentaire anonymisé transmis à l'IA (jamais de données personnelles, §45). */
    public record AnonymizedComment(String commentRef, String text) {
    }

    // ------------------------------------------------------------------ contrôles stockés

    public record QualityCheck(
            String checkId,
            String timestamp,
            String sessionId,
            String checkType,
            String severity,
            boolean detected,
            String source,
            String details,
            Double confidence) {
    }

    // ------------------------------------------------------------------ filtres & agrégats

    public record QualityFilter(String checkType, String severity, Integer rating, String reason) {
        public static QualityFilter none() {
            return new QualityFilter(null, null, null, null);
        }

        public boolean isEmpty() {
            return blank(checkType) && blank(severity) && rating == null && blank(reason);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record RatingBucket(int rating, long count, double share) {
    }

    public record ReasonMetric(String reason, String label, long count, double share,
                               Long previousCount, Double evolutionPercent) {
    }

    public record CheckMetric(String checkType, String label, String severity, long checksRun, long detected,
                              Double detectionRate, Long previousDetected, Double evolutionPercent) {
    }

    public record CommentTheme(String theme, String label, long count, double share) {
    }

    /**
     * KPI de SATISFACTION (calculés par le code, jamais par l'IA).
     *
     * @param conversationsClosed conversations terminées observées sur la période (contrôles exécutés)
     * @param participationRate   avis reçus / conversations terminées ({@code null} si dénominateur nul)
     */
    public record SatisfactionKpis(
            long conversationsClosed,
            long feedbackCount,
            Double participationRate,
            Double averageRating,
            long positiveCount,
            long negativeCount,
            long neutralCount,
            Double positiveRate,
            Double negativeRate,
            Long previousFeedbackCount,
            Double previousAverageRating,
            Double averageRatingEvolutionPercent,
            Double positiveRateEvolutionPercent,
            boolean sufficientSample) {
    }

    /** KPI de QUALITÉ / CONFORMITÉ : uniquement les contrôles réellement exécutés (§25). */
    public record ConformityKpis(long checksRun, long anomalies, long highAnomalies,
                                 long mediumAnomalies, long lowAnomalies) {
    }

    /**
     * Croisement satisfaction × conformité (§28) :
     * A = satisfait + conforme, B = satisfait + anomalie, C = insatisfait + conforme (important),
     * D = insatisfait + anomalie (prioritaire). Les notes neutres (3) sont exclues du croisement.
     */
    public record SatisfactionComplianceMatrix(long satisfiedCompliant, long satisfiedAnomaly,
                                               long unsatisfiedCompliant, long unsatisfiedAnomaly,
                                               Double unsatisfiedCompliantShare, Double unsatisfiedAnomalyShare,
                                               long unratedWithAnomaly) {
    }

    public record QualityDailyPoint(String date, long feedbackCount, Double averageRating,
                                    long positiveCount, long negativeCount, long anomalies) {
    }

    /**
     * Agrégats transmis à l'agent IA Qualité et exposés à la page.
     * <p>
     * Aucun chiffre n'y est inventé : tout est calculé par {@code QualityAnalyticsService}.
     */
    public record QualityAggregates(
            String dateFrom,
            String dateTo,
            SatisfactionKpis satisfaction,
            ConformityKpis conformity,
            List<RatingBucket> ratingDistribution,
            List<ReasonMetric> feedbackCategories,
            List<CommentTheme> commentThemes,
            List<CheckMetric> qualityChecks,
            List<String> implementedChecks,
            List<String> notImplementedChecks,
            SatisfactionComplianceMatrix satisfactionVsCompliance,
            List<QualityDailyPoint> series,
            List<MarketingModels.TrendMetric> trends,
            List<AnonymizedComment> anonymizedComments,
            long invalidLines,
            boolean demo,
            String generatedAt) {

        public QualityAggregates {
            ratingDistribution = ratingDistribution == null ? List.of() : List.copyOf(ratingDistribution);
            feedbackCategories = feedbackCategories == null ? List.of() : List.copyOf(feedbackCategories);
            commentThemes = commentThemes == null ? List.of() : List.copyOf(commentThemes);
            qualityChecks = qualityChecks == null ? List.of() : List.copyOf(qualityChecks);
            implementedChecks = implementedChecks == null ? List.of() : List.copyOf(implementedChecks);
            notImplementedChecks = notImplementedChecks == null ? List.of() : List.copyOf(notImplementedChecks);
            series = series == null ? List.of() : List.copyOf(series);
            trends = trends == null ? List.of() : List.copyOf(trends);
            anonymizedComments = anonymizedComments == null ? List.of() : List.copyOf(anonymizedComments);
        }
    }

    // ------------------------------------------------------------------ rapport IA (structure du prompt)

    public record ReportPeriod(String from, String to) {
    }

    /** {@code status} ∈ {GOOD, WATCH, ATTENTION, INSUFFICIENT_DATA}. */
    public record ExecutiveSummary(String status, String summary) {
    }

    public record SatisfactionAnalysis(String summary, List<String> positivePoints, List<String> mainIrritants) {
        public SatisfactionAnalysis {
            positivePoints = positivePoints == null ? List.of() : List.copyOf(positivePoints);
            mainIrritants = mainIrritants == null ? List.of() : List.copyOf(mainIrritants);
        }
    }

    public record QualityAndCompliance(String summary, List<String> mainIssues, List<String> criticalIssues) {
        public QualityAndCompliance {
            mainIssues = mainIssues == null ? List.of() : List.copyOf(mainIssues);
            criticalIssues = criticalIssues == null ? List.of() : List.copyOf(criticalIssues);
        }
    }

    public record SatisfactionVsCompliance(String summary, List<String> notableCases) {
        public SatisfactionVsCompliance {
            notableCases = notableCases == null ? List.of() : List.copyOf(notableCases);
        }
    }

    /** Règle correctement appliquée MAIS générant de la frustration (§18 du prompt). */
    public record RuleFriction(String rule, String observation, Boolean coachCompliant, String recommendation) {
    }

    /** {@code type} ∈ {IMPROVING, DEGRADING, STABLE, NEW, INSUFFICIENT_DATA}. */
    public record QualityTrend(String type, String topic, String observation) {
    }

    public record PriorityImprovement(String priority, String title, String observation,
                                      String recommendation, String expectedBenefit) {
    }

    /** {@code level} ∈ {INFO, WATCH, IMPORTANT}. */
    public record QualityAlert(String level, String title, String description) {
    }

    public record QualityReport(
            String reportDate,
            ReportPeriod period,
            ExecutiveSummary executiveSummary,
            SatisfactionAnalysis satisfactionAnalysis,
            QualityAndCompliance qualityAndCompliance,
            SatisfactionVsCompliance satisfactionVsCompliance,
            List<RuleFriction> ruleFriction,
            List<QualityTrend> trends,
            List<PriorityImprovement> priorityImprovements,
            List<QualityAlert> alerts,
            String finalAssessment,
            String generatedAt,
            String model,
            Boolean aiGenerated,
            String error) {

        public QualityReport {
            ruleFriction = ruleFriction == null ? List.of() : List.copyOf(ruleFriction);
            trends = trends == null ? List.of() : List.copyOf(trends);
            priorityImprovements = priorityImprovements == null ? List.of() : List.copyOf(priorityImprovements);
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }
    }

    // ------------------------------------------------------------------ normalisation du rapport IA

    public static final String STATUS_GOOD = "GOOD";
    public static final String STATUS_WATCH = "WATCH";
    public static final String STATUS_ATTENTION = "ATTENTION";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_DATA";

    /** Valeurs autorisées par le prompt (§30) : une valeur inattendue est ramenée à un état prudent. */
    public static String normalizeReportStatus(String raw) {
        String value = upper(raw);
        return switch (value) {
            case STATUS_GOOD, STATUS_WATCH, STATUS_ATTENTION, STATUS_INSUFFICIENT -> value;
            default -> STATUS_INSUFFICIENT;
        };
    }

    public static String normalizePriority(String raw) {
        String value = upper(raw);
        return switch (value) {
            case SEVERITY_HIGH, SEVERITY_MEDIUM, SEVERITY_LOW -> value;
            default -> SEVERITY_MEDIUM;
        };
    }

    public static String normalizeAlertLevel(String raw) {
        String value = upper(raw);
        return switch (value) {
            case "INFO", "WATCH", "IMPORTANT" -> value;
            default -> "WATCH";
        };
    }

    public static String normalizeQualityTrendType(String raw) {
        String value = upper(raw);
        return switch (value) {
            case "IMPROVING", "DEGRADING", "STABLE", "NEW", "INSUFFICIENT_DATA" -> value;
            default -> "INSUFFICIENT_DATA";
        };
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ anonymisation
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}");
    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("(\\+?\\d[\\d\\s.()-]{7,}\\d)");
    private static final java.util.regex.Pattern LONG_NUMBER =
            java.util.regex.Pattern.compile("\\b\\d{9,}\\b");

    /**
     * Nettoie un commentaire client avant usage analytique ou affichage interne (§45) : emails,
     * téléphones et longues suites de chiffres (compte/IBAN) masqués, espaces normalisés, longueur
     * bornée. Le commentaire reste une donnée NON FIABLE : il n'est jamais interprété comme une
     * instruction système.
     */
    public static String sanitizeComment(String comment, int maxLength) {
        if (comment == null) {
            return "";
        }
        String cleaned = comment.replace('\r', ' ').replace('\n', ' ');
        cleaned = EMAIL.matcher(cleaned).replaceAll("[email masqué]");
        cleaned = PHONE.matcher(cleaned).replaceAll("[numéro masqué]");
        cleaned = LONG_NUMBER.matcher(cleaned).replaceAll("[numéro masqué]");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        int max = maxLength <= 0 ? 1000 : maxLength;
        return cleaned.length() > max ? cleaned.substring(0, max) + "…" : cleaned;
    }
}
