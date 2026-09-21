package com.coach.financier.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modèles du module <b>Feedback Conseiller</b> : le conseiller bancaire évalue la pertinence du
 * travail produit par le Coach IA (résumé, besoin, produits, niveaux d'intérêt, suivi, email client).
 * <p>
 * Principe : <b>le conseiller donne son expertise → le système structure → l'IA analyse les tendances
 * → l'humain décide</b>. Aucun feedback ne modifie automatiquement un prompt, une règle, un seuil,
 * un catalogue ou du code.
 * <p>
 * Ce module est INDÉPENDANT du module Qualité : Qualité = « le client est-il satisfait et le Coach
 * respecte-t-il les règles ? » ; Feedback Conseiller = « le travail produit est-il pertinent du point
 * de vue professionnel du conseiller ? ». Le point de jonction est le {@code sessionId}.
 */
public final class AdvisorFeedbackModels {

    private AdvisorFeedbackModels() {
    }

    // ------------------------------------------------------------------ évaluation globale (§4)

    public static final String RELEVANT = "RELEVANT";
    public static final String NEEDS_IMPROVEMENT = "NEEDS_IMPROVEMENT";
    public static final String INCORRECT = "INCORRECT";
    public static final List<String> ASSESSMENTS = List.of(RELEVANT, NEEDS_IMPROVEMENT, INCORRECT);

    private static final Map<String, String> ASSESSMENT_LABELS = Map.of(
            RELEVANT, "Pertinente",
            NEEDS_IMPROVEMENT, "À améliorer",
            INCORRECT, "Incorrecte");

    // ------------------------------------------------------------------ domaines de correction (§5)

    public static final String AREA_SUMMARY = "SUMMARY";
    public static final String AREA_CUSTOMER_NEED = "CUSTOMER_NEED";
    public static final String AREA_PROJECT_DETECTION = "PROJECT_DETECTION";
    public static final String AREA_PRODUCT_RELEVANCE = "PRODUCT_RELEVANCE";
    public static final String AREA_INTEREST_LEVEL = "INTEREST_LEVEL";
    public static final String AREA_NEXT_ACTION = "NEXT_ACTION";
    public static final String AREA_CLIENT_EMAIL = "CLIENT_EMAIL";
    public static final String AREA_MISSING_INFORMATION = "MISSING_INFORMATION";
    public static final String AREA_OTHER = "OTHER";

    public static final List<String> AREAS = List.of(AREA_SUMMARY, AREA_CUSTOMER_NEED, AREA_PROJECT_DETECTION,
            AREA_PRODUCT_RELEVANCE, AREA_INTEREST_LEVEL, AREA_NEXT_ACTION, AREA_CLIENT_EMAIL,
            AREA_MISSING_INFORMATION, AREA_OTHER);

    private static final Map<String, String> AREA_LABELS = Map.of(
            AREA_SUMMARY, "Résumé de la conversation",
            AREA_CUSTOMER_NEED, "Compréhension du besoin client",
            AREA_PROJECT_DETECTION, "Projet détecté",
            AREA_PRODUCT_RELEVANCE, "Produit proposé / produit d'intérêt",
            AREA_INTEREST_LEVEL, "Niveau d'intérêt du client",
            AREA_NEXT_ACTION, "Suivi conseillé",
            AREA_CLIENT_EMAIL, "Email préparé pour le client",
            AREA_MISSING_INFORMATION, "Information importante manquante",
            AREA_OTHER, "Autre");

    // ------------------------------------------------------------------ motifs structurés (§6)

    public static final String REASON_WRONG = "WRONG";
    public static final String REASON_INCOMPLETE = "INCOMPLETE";
    public static final String REASON_NOT_RELEVANT = "NOT_RELEVANT";
    public static final String REASON_TOO_VERBOSE = "TOO_VERBOSE";
    public static final String REASON_TOO_GENERIC = "TOO_GENERIC";
    public static final String REASON_MISSING_CONTEXT = "MISSING_CONTEXT";
    public static final String REASON_WRONG_PRODUCT = "WRONG_PRODUCT";
    public static final String REASON_WRONG_INTEREST_LEVEL = "WRONG_INTEREST_LEVEL";
    public static final String REASON_UNSUPPORTED_RECOMMENDATION = "UNSUPPORTED_RECOMMENDATION";
    public static final String REASON_OTHER = "OTHER";

    public static final List<String> REASONS = List.of(REASON_WRONG, REASON_INCOMPLETE, REASON_NOT_RELEVANT,
            REASON_TOO_VERBOSE, REASON_TOO_GENERIC, REASON_MISSING_CONTEXT, REASON_WRONG_PRODUCT,
            REASON_WRONG_INTEREST_LEVEL, REASON_UNSUPPORTED_RECOMMENDATION, REASON_OTHER);

    private static final Map<String, String> REASON_LABELS = Map.of(
            REASON_WRONG, "Erreur",
            REASON_INCOMPLETE, "Incomplet",
            REASON_NOT_RELEVANT, "Non pertinent",
            REASON_TOO_VERBOSE, "Trop verbeux",
            REASON_TOO_GENERIC, "Trop générique",
            REASON_MISSING_CONTEXT, "Contexte manquant",
            REASON_WRONG_PRODUCT, "Mauvais produit",
            REASON_WRONG_INTEREST_LEVEL, "Mauvais niveau d'intérêt",
            REASON_UNSUPPORTED_RECOMMENDATION, "Recommandation non étayée",
            REASON_OTHER, "Autre");

    // ------------------------------------------------------------------ feedback produit (§7/§8)

    public static final String PRODUCT_RELEVANT = "RELEVANT_PRODUCT";
    public static final String PRODUCT_NOT_RELEVANT = "NOT_RELEVANT";
    public static final List<String> PRODUCT_ASSESSMENTS = List.of(PRODUCT_RELEVANT, PRODUCT_NOT_RELEVANT);

    public static final String PRODUCT_REASON_NOT_INTERESTED = "CUSTOMER_NOT_INTERESTED";
    public static final String PRODUCT_REASON_INCOMPATIBLE = "INCOMPATIBLE_WITH_NEED";
    public static final String PRODUCT_REASON_OVERESTIMATED = "INTEREST_OVERESTIMATED";
    public static final String PRODUCT_REASON_MENTION_ONLY = "MENTION_ONLY";

    public static final List<String> PRODUCT_REASONS = List.of(PRODUCT_REASON_NOT_INTERESTED,
            PRODUCT_REASON_INCOMPATIBLE, PRODUCT_REASON_OVERESTIMATED, PRODUCT_REASON_MENTION_ONLY,
            REASON_OTHER);

    private static final Map<String, String> PRODUCT_REASON_LABELS = Map.of(
            PRODUCT_REASON_NOT_INTERESTED, "Client non intéressé",
            PRODUCT_REASON_INCOMPATIBLE, "Produit incompatible avec le besoin",
            PRODUCT_REASON_OVERESTIMATED, "Intérêt surestimé",
            PRODUCT_REASON_MENTION_ONLY, "Produit seulement mentionné",
            REASON_OTHER, "Autre");

    // ------------------------------------------------------------------ niveaux d'intérêt (§9)

    /** Valeurs identiques au dossier de suivi : HIGH / MEDIUM / LOW / REJECTED. */
    public static final List<String> INTEREST_LEVELS = List.of("HIGH", "MEDIUM", "LOW", "REJECTED");

    // ------------------------------------------------------------------ email client (§11/§12)

    public static final String EMAIL_READY = "READY_TO_USE";
    public static final String EMAIL_MINOR = "MINOR_EDITS";
    public static final String EMAIL_MAJOR = "MAJOR_EDITS";
    public static final String EMAIL_UNUSABLE = "UNUSABLE";
    public static final List<String> EMAIL_ASSESSMENTS = List.of(EMAIL_READY, EMAIL_MINOR, EMAIL_MAJOR, EMAIL_UNUSABLE);

    private static final Map<String, String> EMAIL_LABELS = Map.of(
            EMAIL_READY, "Prêt à utiliser",
            EMAIL_MINOR, "Modifications mineures",
            EMAIL_MAJOR, "Modifications importantes",
            EMAIL_UNUSABLE, "Non utilisable");

    public static final List<String> EDIT_LEVELS = List.of("NO_EDIT", "MINOR_EDIT", "MAJOR_EDIT", "FULL_REWRITE");

    public static final String SOURCE_ADVISOR = "ADVISOR_FEEDBACK";
    public static final String SOURCE_DEMO = "DEMO";
    public static final String EVENT_CREATED = "CREATED";
    public static final String EVENT_UPDATED = "UPDATED";

    // ------------------------------------------------------------------ libellés & normalisation

    public static Map<String, String> assessmentLabels() {
        return new LinkedHashMap<>(ASSESSMENT_LABELS);
    }

    public static Map<String, String> areaLabels() {
        return new LinkedHashMap<>(AREA_LABELS);
    }

    public static Map<String, String> reasonLabels() {
        return new LinkedHashMap<>(REASON_LABELS);
    }

    public static Map<String, String> productReasonLabels() {
        return new LinkedHashMap<>(PRODUCT_REASON_LABELS);
    }

    public static Map<String, String> emailLabels() {
        return new LinkedHashMap<>(EMAIL_LABELS);
    }

    public static String assessmentLabel(String code) {
        return label(ASSESSMENT_LABELS, code);
    }

    public static String areaLabel(String code) {
        return label(AREA_LABELS, code);
    }

    public static String reasonLabel(String code) {
        return label(REASON_LABELS, code);
    }

    public static String productReasonLabel(String code) {
        return label(PRODUCT_REASON_LABELS, code);
    }

    public static String emailLabel(String code) {
        return label(EMAIL_LABELS, code);
    }

    private static String label(Map<String, String> labels, String code) {
        if (code == null) {
            return "";
        }
        return labels.getOrDefault(code.trim().toUpperCase(Locale.ROOT), code);
    }

    public static String normalizeAssessment(String raw) {
        String value = upper(raw);
        return ASSESSMENTS.contains(value) ? value : null;
    }

    public static String normalizeArea(String raw) {
        return normalize(raw, AREAS, AREA_OTHER);
    }

    public static String normalizeReason(String raw) {
        return normalize(raw, REASONS, REASON_OTHER);
    }

    public static String normalizeProductAssessment(String raw) {
        String value = upper(raw);
        return PRODUCT_ASSESSMENTS.contains(value) ? value : null;
    }

    public static String normalizeProductReason(String raw) {
        return normalize(raw, PRODUCT_REASONS, REASON_OTHER);
    }

    public static String normalizeEmailAssessment(String raw) {
        return normalize(raw, EMAIL_ASSESSMENTS, null);
    }

    public static String normalizeEditLevel(String raw) {
        String value = upper(raw);
        return EDIT_LEVELS.contains(value) ? value : null;
    }

    /** Niveau d'intérêt normalisé (HIGH/MEDIUM/LOW/REJECTED) ou {@code null} si non exploitable. */
    public static String normalizeInterestLevel(String raw) {
        String value = upper(raw);
        return INTEREST_LEVELS.contains(value) ? value : null;
    }

    private static String normalize(String raw, List<String> allowed, String fallback) {
        String value = upper(raw).replace('-', '_').replace(' ', '_');
        if (allowed.contains(value)) {
            return value;
        }
        return fallback;
    }

    private static String upper(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ filtre

    public record AdvisorFeedbackFilter(String assessment, String area, String productId, String emailAssessment) {
        public static AdvisorFeedbackFilter none() {
            return new AdvisorFeedbackFilter(null, null, null, null);
        }

        public boolean isEmpty() {
            return blank(assessment) && blank(area) && blank(productId) && blank(emailAssessment);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    // ------------------------------------------------------------------ feedback

    /** Élément corrigé par le conseiller (zone + motif + commentaire facultatif). */
    public record AdvisorIssue(String area, String reason, String comment) {
    }

    /** Évaluation d'un produit détecté, avec conservation de la valeur IA ET de la correction. */
    public record ProductFeedback(String productId, String productName, String aiInterestLevel,
                                  String advisorAssessment, String advisorInterestLevel,
                                  String reason, String comment) {
    }

    /** Corps reçu depuis l'IHM de saisie conseiller. */
    public record AdvisorFeedbackRequest(
            String sessionId,
            String overallAssessment,
            List<AdvisorIssue> issues,
            List<ProductFeedback> productFeedback,
            List<String> missingProductIds,
            String nextActionAssessment,
            String clientEmailAssessment,
            String editLevel,
            String comment,
            String advisorId) {

        public AdvisorFeedbackRequest {
            issues = issues == null ? List.of() : List.copyOf(issues);
            productFeedback = productFeedback == null ? List.of() : List.copyOf(productFeedback);
            missingProductIds = missingProductIds == null ? List.of() : List.copyOf(missingProductIds);
        }
    }

    /**
     * Feedback conseiller stocké (anonymisé : le conseiller n'est identifié que par un hash).
     *
     * @param version version du feedback pour la même session (1 = création, &gt; 1 = mise à jour) :
     *                l'historique des versions est CONSERVÉ (aucun écrasement silencieux)
     */
    public record AdvisorFeedback(
            String feedbackId,
            String timestamp,
            String sessionId,
            String advisorIdHash,
            String overallAssessment,
            List<AdvisorIssue> issues,
            List<ProductFeedback> productFeedback,
            List<String> missingProductIds,
            String nextActionAssessment,
            String clientEmailAssessment,
            String editLevel,
            String comment,
            String source,
            String event,
            int version,
            String createdAt) {

        public AdvisorFeedback {
            issues = issues == null ? List.of() : List.copyOf(issues);
            productFeedback = productFeedback == null ? List.of() : List.copyOf(productFeedback);
            missingProductIds = missingProductIds == null ? List.of() : List.copyOf(missingProductIds);
        }

        public boolean revised() {
            return version > 1;
        }
    }

    // ------------------------------------------------------------------ KPI & agrégats

    /**
     * KPI calculés par le backend (jamais par le LLM).
     *
     * @param sessionsEvaluated      dossiers évalués (sessions distinctes)
     * @param participationRate      avis conseiller / conversations clôturées ({@code null} si inconnu)
     * @param emailsReadyOrMinor     emails « prêts à utiliser » ou « modifications mineures »
     * @param productRelevanceRate   produits jugés pertinents / produits évalués
     */
    public record AdvisorKpis(
            long sessionsEvaluated,
            long feedbackCount,
            Double participationRate,
            Double relevantRate,
            Double needsImprovementRate,
            Double incorrectRate,
            long productAssessments,
            long relevantProducts,
            long notRelevantProducts,
            Double productRelevanceRate,
            long emailsReadyOrMinor,
            Double emailReadyOrMinorRate,
            long interestCorrections,
            Long previousFeedbackCount,
            Double previousRelevantRate,
            Double relevantRateEvolutionPercent,
            boolean sufficientSample) {
    }

    public record AssessmentMetric(String code, String label, long count, double share, Double evolutionPercent) {
    }

    public record AreaMetric(String area, String label, long count, double share, Double evolutionPercent) {
    }

    public record ReasonMetric(String reason, String label, long count, Double evolutionPercent) {
    }

    public record ProductFeedbackMetric(String productId, String productName, long assessments, long relevant,
                                        long notRelevant, Double relevanceRate, long interestCorrections,
                                        long addedByAdvisor, Double evolutionPercent) {
    }

    /** Correction de niveau d'intérêt : la valeur IA et la valeur conseiller sont toutes deux conservées. */
    public record InterestCorrectionMetric(String productId, String productName, String fromLevel, String toLevel,
                                           long count) {
    }

    public record EmailQualityMetric(String code, String label, long count, double share, Double evolutionPercent) {
    }

    public record AdvisorDailyPoint(String date, long feedbackCount, long relevant,
                                    long needsImprovement, long incorrect, long productNotRelevant) {
    }

    public record AdvisorFeedbackAggregates(
            String dateFrom,
            String dateTo,
            AdvisorKpis kpis,
            List<AssessmentMetric> assessments,
            List<AreaMetric> areas,
            List<ReasonMetric> reasons,
            List<ProductFeedbackMetric> products,
            List<InterestCorrectionMetric> interestCorrections,
            List<EmailQualityMetric> emailQuality,
            List<AdvisorDailyPoint> series,
            List<MarketingModels.TrendMetric> trends,
            List<AnonymizedComment> anonymizedComments,
            long invalidLines,
            boolean demo,
            String generatedAt) {

        public AdvisorFeedbackAggregates {
            assessments = assessments == null ? List.of() : List.copyOf(assessments);
            areas = areas == null ? List.of() : List.copyOf(areas);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            products = products == null ? List.of() : List.copyOf(products);
            interestCorrections = interestCorrections == null ? List.of() : List.copyOf(interestCorrections);
            emailQuality = emailQuality == null ? List.of() : List.copyOf(emailQuality);
            series = series == null ? List.of() : List.copyOf(series);
            trends = trends == null ? List.of() : List.copyOf(trends);
            anonymizedComments = anonymizedComments == null ? List.of() : List.copyOf(anonymizedComments);
        }
    }

    /** Commentaire anonymisé transmis à l'analyste (jamais de donnée personnelle). */
    public record AnonymizedComment(String commentRef, String text) {
    }

    // ------------------------------------------------------------------ rapport IA (structure du prompt)

    public record AdvisorReportPeriod(String from, String to) {
    }

    /** {@code status} ∈ {GOOD, WATCH, ATTENTION, INSUFFICIENT_DATA}. */
    public record AdvisorExecutiveSummary(String status, String summary) {
    }

    public record StrengthItem(String title, String observation) {
    }

    public record IssueItem(String area, String observation, String severity) {
    }

    /** {@code signal} ∈ {POSITIVE, NEGATIVE, MIXED, INSUFFICIENT_DATA}. */
    public record ProductAnalysisItem(String productId, String productName, String observation, String signal) {
    }

    public record InterestLevelAnalysis(String summary, List<String> overestimationSignals,
                                        List<String> underestimationSignals) {
        public InterestLevelAnalysis {
            overestimationSignals = overestimationSignals == null ? List.of() : List.copyOf(overestimationSignals);
            underestimationSignals = underestimationSignals == null ? List.of() : List.copyOf(underestimationSignals);
        }
    }

    /** Section avec résumé + liste de problèmes (suivi conseillé, email client). */
    public record SectionAnalysis(String summary, List<String> issues) {
        public SectionAnalysis {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /** {@code type} ∈ {IMPROVING, DEGRADING, STABLE, NEW, INSUFFICIENT_DATA}. */
    public record AdvisorTrend(String type, String topic, String observation) {
    }

    public record AdvisorPriorityImprovement(String priority, String title, String observation,
                                             String recommendation, String expectedBenefit) {
    }

    public record AdvisorFeedbackReport(
            String reportDate,
            AdvisorReportPeriod period,
            AdvisorExecutiveSummary executiveSummary,
            List<StrengthItem> strengths,
            List<IssueItem> mainIssues,
            List<ProductAnalysisItem> productAnalysis,
            InterestLevelAnalysis interestLevelAnalysis,
            SectionAnalysis nextActionAnalysis,
            SectionAnalysis clientEmailAnalysis,
            List<AdvisorTrend> trends,
            List<AdvisorPriorityImprovement> priorityImprovements,
            List<String> watchPoints,
            String finalAssessment,
            String generatedAt,
            String model,
            Boolean aiGenerated,
            String error) {

        public AdvisorFeedbackReport {
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            mainIssues = mainIssues == null ? List.of() : List.copyOf(mainIssues);
            productAnalysis = productAnalysis == null ? List.of() : List.copyOf(productAnalysis);
            trends = trends == null ? List.of() : List.copyOf(trends);
            priorityImprovements = priorityImprovements == null ? List.of() : List.copyOf(priorityImprovements);
            watchPoints = watchPoints == null ? List.of() : List.copyOf(watchPoints);
        }
    }

    // ------------------------------------------------------------------ dossier évaluable (lien du mail)

    /** Statut d'évaluation d'un dossier (§48) : aucune demande, en attente, ou complétée. */
    public static final String STATUS_NOT_REQUESTED = "NOT_REQUESTED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Produit d'intérêt tel que présenté au conseiller dans le dossier de suivi. */
    public record DossierProduct(String productId, String name, String category, String interestLevel,
                                 String interestReason, String productUrl) {
    }

    /** Contenu d'email (conseiller ou brouillon client) conservé pour l'écran d'évaluation. */
    public record DossierEmail(String subject, String body) {
    }

    /** Message d'un échange client ↔ Coach, conservé avec le dossier (annuaire du centre d'appels). */
    public record DossierMessage(String role, String content, String timestamp) {
    }

    /**
     * Identité « métier » d'un dossier, utilisée par l'ANNUAIRE DES CONVERSATIONS du centre d'appels :
     * client, titre lisible et catégorie (filtre). Aucune donnée n'est inventée : un dossier ancien
     * (écrit avant l'introduction de ces champs) reste lisible avec des valeurs vides.
     */
    public record DossierClient(String customerId, String title, String category, String categoryLabel) {
    }

    /**
     * SCORE DE SENS COMMERCIAL conservé avec le dossier (voir {@code CommercialScoreService}) :
     * il permet au conseiller de prioriser ses relances et de comprendre le score (raisons + critères).
     */
    public record DossierScore(Integer score, String priority, String label, List<String> reasons,
                               List<String> details, Boolean proposedByAi) {
        public DossierScore {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            details = details == null ? List.of() : List.copyOf(details);
        }

        /** « 72/100 — Priorité haute », ou chaîne vide si le dossier n'a pas de score. */
        public String display() {
            return score == null ? "" : score + "/100 — " + (label == null ? "" : label);
        }
    }

    /**
     * Dossier de suivi PERSISTÉ à la clôture : c'est ce que le conseiller retrouve lorsqu'il clique sur
     * le lien d'évaluation reçu par email (§41 à §43).
     * <p>
     * Aucune donnée personnelle n'est exposée dans l'URL : seul le {@code sessionId} identifie le dossier
     * (§45), et le backend reste seul juge de son existence et de son accessibilité (§46).
     * <p>
     * {@code client}, {@code score} et {@code transcript} alimentent l'ANNUAIRE DES CONVERSATIONS
     * (centre d'appels) : ils sont absents des dossiers écrits avant cette évolution.
     */
    public record AdvisorDossier(
            String dossierId,
            String timestamp,
            String sessionId,
            String mainProject,
            List<String> otherProjects,
            List<String> preferences,
            List<DossierProduct> productsOfInterest,
            List<String> nextActions,
            DossierEmail advisorEmail,
            DossierEmail customerEmail,
            String feedbackUrl,
            String createdAt,
            DossierClient client,
            DossierScore score,
            List<DossierMessage> transcript) {

        public AdvisorDossier {
            otherProjects = otherProjects == null ? List.of() : List.copyOf(otherProjects);
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
            productsOfInterest = productsOfInterest == null ? List.of() : List.copyOf(productsOfInterest);
            nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
        }

        /** Compatibilité : dossier sans identité client, sans score et sans transcript. */
        public AdvisorDossier(String dossierId, String timestamp, String sessionId, String mainProject,
                              List<String> otherProjects, List<String> preferences,
                              List<DossierProduct> productsOfInterest, List<String> nextActions,
                              DossierEmail advisorEmail, DossierEmail customerEmail, String feedbackUrl,
                              String createdAt) {
            this(dossierId, timestamp, sessionId, mainProject, otherProjects, preferences, productsOfInterest,
                    nextActions, advisorEmail, customerEmail, feedbackUrl, createdAt, null, null, List.of());
        }
    }


    /** Dossier + feedback éventuel + statut, tels que servis à l'écran d'évaluation. */
    public record DossierView(AdvisorDossier dossier, AdvisorFeedback feedback, String feedbackStatus) {
    }

    // ------------------------------------------------------------------ normalisation du rapport IA
    public static final String STATUS_GOOD = "GOOD";
    public static final String STATUS_WATCH = "WATCH";
    public static final String STATUS_ATTENTION = "ATTENTION";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT_DATA";

    public static String normalizeReportStatus(String raw) {
        String value = upper(raw);
        return switch (value) {
            case STATUS_GOOD, STATUS_WATCH, STATUS_ATTENTION, STATUS_INSUFFICIENT -> value;
            default -> STATUS_INSUFFICIENT;
        };
    }

    public static String normalizeSignal(String raw) {
        String value = upper(raw);
        return switch (value) {
            case "POSITIVE", "NEGATIVE", "MIXED", "INSUFFICIENT_DATA" -> value;
            default -> "INSUFFICIENT_DATA";
        };
    }

    public static String normalizeTrendType(String raw) {
        String value = upper(raw);
        return switch (value) {
            case "IMPROVING", "DEGRADING", "STABLE", "NEW", "INSUFFICIENT_DATA" -> value;
            default -> "INSUFFICIENT_DATA";
        };
    }

    public static String normalizePriority(String raw) {
        String value = upper(raw);
        return switch (value) {
            case "HIGH", "MEDIUM", "LOW" -> value;
            default -> "MEDIUM";
        };
    }
}
