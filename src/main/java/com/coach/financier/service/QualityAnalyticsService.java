package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.LogEntry;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.QualityModels;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MOTEUR ANALYTIQUE du module Qualité (calculs DÉTERMINISTES — le LLM ne calcule jamais un chiffre).
 * <p>
 * Il agrège deux familles INDÉPENDANTES (§26) :
 * <ul>
 *   <li><b>satisfaction client</b> : note moyenne, distribution, avis positifs/négatifs, motifs ;</li>
 *   <li><b>qualité / conformité</b> : contrôles exécutés, anomalies, par type et par sévérité.</li>
 * </ul>
 * puis les CROISE (§28) sans jamais transformer une mauvaise note en anomalie.
 * <p>
 * Aucune statistique n'est inventée : un indicateur dont le dénominateur est nul vaut {@code null},
 * un contrôle non implémenté n'apparaît pas, et l'absence de données reste un état vide explicite.
 */
@Service
public class QualityAnalyticsService {

    private final QualityFeedbackStore feedbackStore;
    private final QualityCheckStore checkStore;
    private final QualityProperties properties;

    public QualityAnalyticsService(QualityFeedbackStore feedbackStore,
                                   QualityCheckStore checkStore,
                                   QualityProperties properties) {
        this.feedbackStore = feedbackStore;
        this.checkStore = checkStore;
        this.properties = properties;
    }

    /** Agrégats complets d'une période (bornes incluses) pour la page et l'agent IA Qualité. */
    public QualityModels.QualityAggregates compute(LocalDate from, LocalDate to,
                                                   QualityModels.QualityFilter filter) {
        QualityModels.QualityFilter effective = filter == null ? QualityModels.QualityFilter.none() : filter;
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        QualityFeedbackStore.ReadResult feedbackResult = feedbackStore.read(start, end);
        JsonlFiles.ReadResult<QualityModels.QualityCheck> checkResult = checkStore.read(start, end);

        List<QualityModels.CoachFeedback> feedback = feedbackResult.feedback().stream()
                .filter(item -> matches(effective, item))
                .toList();
        List<QualityModels.QualityCheck> checks = checkResult.values().stream()
                .filter(item -> matches(effective, item))
                .toList();

        LocalDate previousStart = start.minusDays(ChronoUnit.DAYS.between(start, end) + 1);
        LocalDate previousEnd = start.minusDays(1);
        QualityFeedbackStore.ReadResult previousFeedbackResult = feedbackStore.read(previousStart, previousEnd);
        JsonlFiles.ReadResult<QualityModels.QualityCheck> previousCheckResult =
                checkStore.read(previousStart, previousEnd);
        List<QualityModels.CoachFeedback> previousFeedback = previousFeedbackResult.feedback().stream()
                .filter(item -> matches(effective, item))
                .toList();
        List<QualityModels.QualityCheck> previousChecks = previousCheckResult.values().stream()
                .filter(item -> matches(effective, item))
                .toList();

        QualityModels.SatisfactionKpis satisfaction = satisfaction(checks, feedback, previousFeedback);
        QualityModels.ConformityKpis conformity = conformity(checks);
        List<QualityModels.RatingBucket> distribution = distribution(feedback);
        List<QualityModels.ReasonMetric> categories = reasonMetrics(feedback, previousFeedback);
        List<QualityModels.CommentTheme> themes = commentThemes(feedback);
        List<QualityModels.CheckMetric> checkMetrics = checkMetrics(checks, previousChecks);
        QualityModels.SatisfactionComplianceMatrix matrix = matrix(feedback, checks);
        List<QualityModels.QualityDailyPoint> series = series(start, end, feedback, checks);
        List<MarketingModels.TrendMetric> trends = trends(feedback, previousFeedback, checks, previousChecks);

        return new QualityModels.QualityAggregates(
                start.toString(), end.toString(), satisfaction, conformity, distribution, categories, themes,
                checkMetrics, List.copyOf(properties.enabledChecks()), properties.notImplementedChecks(),
                matrix, series, trends, anonymizedComments(feedback),
                feedbackResult.invalidLines() + checkResult.invalidLines(),
                feedback.stream().anyMatch(item -> QualityModels.SOURCE_DEMO.equals(item.source()))
                        || checks.stream().anyMatch(item -> QualityModels.SOURCE_DEMO.equals(item.source())),
                Instant.now().toString());
    }

    /** Feedbacks bruts d'une période (export / diagnostic). */
    public List<QualityModels.CoachFeedback> feedback(LocalDate from, LocalDate to) {
        return feedbackStore.read(from, to).feedback();
    }

    /** Contrôles bruts d'une période (export / diagnostic). */
    public List<QualityModels.QualityCheck> checks(LocalDate from, LocalDate to) {
        return checkStore.read(from, to).values();
    }

    /** Feedback enregistré pour une session (idempotence de la pop-in). */
    public java.util.Optional<QualityModels.CoachFeedback> feedbackOf(String sessionId) {
        return feedbackStore.findBySession(sessionId);
    }

    /** Jours disposant de données de qualité (feedback ou contrôles). */
    public List<LocalDate> availableDays() {
        Set<LocalDate> days = new LinkedHashSet<>(feedbackStore.availableDays());
        days.addAll(checkStore.availableDays());
        List<LocalDate> sorted = new ArrayList<>(days);
        sorted.sort(LocalDate::compareTo);
        return sorted;
    }

    /** Contrôles exécutés pour une session (utilisé par le croisement et les tests). */
    public List<QualityModels.QualityCheck> checksOf(String sessionId) {
        return checkStore.read(null, null).values().stream()
                .filter(check -> sessionId != null && sessionId.equals(check.sessionId()))
                .toList();
    }

    /** Logs IA d'une session (indicateur « conversation non résolue » côté page Logs). */
    public static List<LogEntry> sessionLogs(List<LogEntry> logs, String sessionId) {
        return logs == null ? List.of() : logs.stream()
                .filter(entry -> sessionId != null && sessionId.equals(entry.sessionId()))
                .toList();
    }

    // ------------------------------------------------------------------ satisfaction

    private QualityModels.SatisfactionKpis satisfaction(List<QualityModels.QualityCheck> checks,
                                                        List<QualityModels.CoachFeedback> feedback,
                                                        List<QualityModels.CoachFeedback> previousFeedback) {
        long closed = checks.stream()
                .map(QualityModels.QualityCheck::sessionId)
                .filter(sessionId -> sessionId != null && !sessionId.isBlank())
                .distinct()
                .count();

        List<Integer> ratings = feedback.stream()
                .map(QualityModels.CoachFeedback::rating)
                .filter(rating -> rating != null && rating >= 1 && rating <= 5)
                .toList();
        long count = ratings.size();
        long positive = feedback.stream().filter(QualityModels.CoachFeedback::positive).count();
        long negative = feedback.stream().filter(QualityModels.CoachFeedback::negative).count();
        long neutral = feedback.stream().filter(QualityModels.CoachFeedback::neutral).count();

        Double average = count == 0 ? null : round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0));
        Double positiveRate = rate(positive, count);
        Double negativeRate = rate(negative, count);

        List<Integer> previousRatings = previousFeedback.stream()
                .map(QualityModels.CoachFeedback::rating)
                .filter(rating -> rating != null && rating >= 1 && rating <= 5)
                .toList();
        Double previousAverage = previousRatings.isEmpty() ? null
                : round(previousRatings.stream().mapToInt(Integer::intValue).average().orElse(0));
        long previousPositive = previousFeedback.stream().filter(QualityModels.CoachFeedback::positive).count();
        Double previousPositiveRate = rate(previousPositive, previousRatings.size());

        return new QualityModels.SatisfactionKpis(
                closed,
                count,
                rate(count, closed),
                average,
                positive,
                negative,
                neutral,
                positiveRate,
                negativeRate,
                previousRatings.isEmpty() ? null : (long) previousRatings.size(),
                previousAverage,
                evolution(average, previousAverage),
                evolution(positiveRate, previousPositiveRate),
                count >= properties.sufficientSampleSize());
    }

    private static List<QualityModels.RatingBucket> distribution(List<QualityModels.CoachFeedback> feedback) {
        long total = feedback.stream()
                .map(QualityModels.CoachFeedback::rating)
                .filter(rating -> rating != null && rating >= 1 && rating <= 5)
                .count();
        List<QualityModels.RatingBucket> buckets = new ArrayList<>();
        for (int rating = 5; rating >= 1; rating--) {
            final int value = rating;
            long count = feedback.stream()
                    .filter(item -> item.rating() != null && item.rating() == value)
                    .count();
            buckets.add(new QualityModels.RatingBucket(rating, count, rate(count, total) == null
                    ? 0.0 : rate(count, total)));
        }
        return buckets;
    }

    private static List<QualityModels.ReasonMetric> reasonMetrics(List<QualityModels.CoachFeedback> feedback,
                                                                 List<QualityModels.CoachFeedback> previous) {
        Map<String, Long> current = countReasons(feedback, false);
        Map<String, Long> previousCounts = countReasons(previous, false);
        long total = current.values().stream().mapToLong(Long::longValue).sum();
        List<QualityModels.ReasonMetric> metrics = new ArrayList<>();
        for (String reason : QualityModels.FEEDBACK_REASONS) {
            long count = current.getOrDefault(reason, 0L);
            Long previousCount = previousCounts.get(reason);
            if (count == 0 && (previousCount == null || previousCount == 0)) {
                continue;
            }
            metrics.add(new QualityModels.ReasonMetric(reason, QualityModels.reasonLabel(reason), count,
                    rate(count, total) == null ? 0.0 : rate(count, total), previousCount,
                    evolution((double) count, previousCount == null ? null : previousCount.doubleValue())));
        }
        metrics.sort(Comparator.comparingLong(QualityModels.ReasonMetric::count).reversed());
        return metrics;
    }

    /**
     * Thèmes détectés dans les commentaires clients (§40).
     * <p>
     * IMPORTANT : l'analyse IA des commentaires n'est PAS implémentée dans cette version (P2). Les
     * thèmes proviennent d'une <b>heuristique locale déterministe</b> (aucun appel IA, donc aucun coût
     * et aucun risque d'invention) ; les motifs éventuellement détectés par une IA seraient ajoutés
     * sans jamais remplacer les motifs choisis par le client.
     */
    private static List<QualityModels.CommentTheme> commentThemes(List<QualityModels.CoachFeedback> feedback) {
        Map<String, Long> themes = new LinkedHashMap<>();
        for (QualityModels.CoachFeedback item : feedback) {
            Set<String> codes = new LinkedHashSet<>();
            if (item.hasComment()) {
                codes.addAll(detectThemes(item.comment()));
            }
            if (!item.aiDetectedReasons().isEmpty()) {
                item.aiDetectedReasons().forEach(reason -> codes.add(QualityModels.normalizeReason(reason)));
            }
            codes.forEach(code -> themes.merge(code, 1L, Long::sum));
        }
        long total = themes.values().stream().mapToLong(Long::longValue).sum();
        List<QualityModels.CommentTheme> metrics = new ArrayList<>();
        themes.forEach((reason, count) -> metrics.add(new QualityModels.CommentTheme(reason,
                QualityModels.reasonLabel(reason), count, rate(count, total) == null ? 0.0 : rate(count, total))));
        metrics.sort(Comparator.comparingLong(QualityModels.CommentTheme::count).reversed());
        return metrics;
    }

    /** Heuristique locale sur un commentaire (accents normalisés) — jamais un jugement définitif. */
    static Set<String> detectThemes(String comment) {
        String text = normalizeForKeywords(comment);
        Set<String> themes = new LinkedHashSet<>();
        if (matches(text, "repet|toujours les memes|meme chose|redit|se repete|redite")) {
            themes.add(QualityModels.TOO_REPETITIVE);
        }
        if (matches(text, "\\blong|verbeux|trop de texte|trop de detail")) {
            themes.add(QualityModels.TOO_LONG);
        }
        if (matches(text, "comprend|compris|clair|compliqu|confus|jargon|technique")) {
            themes.add(QualityModels.HARD_TO_UNDERSTAND);
        }
        if (matches(text, "manqu|manque|incomplet|pas assez|peu d'information|pas precis")) {
            themes.add(QualityModels.MISSING_INFORMATION);
        }
        if (matches(text, "produit|offre|pas adapt|hors sujet|ne correspond")) {
            themes.add(QualityModels.PRODUCT_NOT_RELEVANT);
        }
        if (matches(text, "simulateur|mensualit|impossible|pas possible|je n'ai pas pu|bloque")) {
            themes.add(QualityModels.ACTION_NOT_POSSIBLE);
        }
        if (matches(text, "reponse|repondu|question")) {
            themes.add(QualityModels.NOT_ANSWERING_QUESTION);
        }
        if (themes.isEmpty()) {
            themes.add(QualityModels.OTHER);
        }
        return themes;
    }

    private static String normalizeForKeywords(String raw) {
        String value = java.text.Normalizer.normalize(raw == null ? "" : raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean matches(String text, String regex) {
        return java.util.regex.Pattern.compile(regex).matcher(text).find();
    }

    private static Map<String, Long> countReasons(List<QualityModels.CoachFeedback> feedback, boolean detected) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (QualityModels.CoachFeedback item : feedback) {
            List<String> reasons = detected ? item.aiDetectedReasons() : item.customerSelectedReasons();
            for (String reason : reasons) {
                counts.merge(QualityModels.normalizeReason(reason), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static List<QualityModels.AnonymizedComment> anonymizedComments(
            List<QualityModels.CoachFeedback> feedback) {
        // Les commentaires sont des données NON FIABLES : nettoyés avant tout usage analytique (§45).
        List<QualityModels.AnonymizedComment> comments = new ArrayList<>();
        for (QualityModels.CoachFeedback item : feedback) {
            if (!item.hasComment()) {
                continue;
            }
            String sanitized = QualityModels.sanitizeComment(item.comment(), 1000);
            if (sanitized.isBlank()) {
                continue;
            }
            comments.add(new QualityModels.AnonymizedComment(item.feedbackId(), sanitized));
        }
        return comments;
    }

    // ------------------------------------------------------------------ conformité

    private static QualityModels.ConformityKpis conformity(List<QualityModels.QualityCheck> checks) {
        long anomalies = checks.stream().filter(QualityModels.QualityCheck::detected).count();
        long high = checks.stream().filter(check -> check.detected()
                && QualityModels.SEVERITY_HIGH.equals(check.severity())).count();
        long medium = checks.stream().filter(check -> check.detected()
                && QualityModels.SEVERITY_MEDIUM.equals(check.severity())).count();
        long low = checks.stream().filter(check -> check.detected()
                && QualityModels.SEVERITY_LOW.equals(check.severity())).count();
        return new QualityModels.ConformityKpis(checks.size(), anomalies, high, medium, low);
    }

    private List<QualityModels.CheckMetric> checkMetrics(List<QualityModels.QualityCheck> checks,
                                                         List<QualityModels.QualityCheck> previousChecks) {
        List<QualityModels.CheckMetric> metrics = new ArrayList<>();
        for (String checkType : properties.enabledChecks()) {
            long run = checks.stream().filter(check -> checkType.equals(check.checkType())).count();
            long detected = checks.stream()
                    .filter(check -> checkType.equals(check.checkType()) && check.detected()).count();
            long previousDetected = previousChecks.stream()
                    .filter(check -> checkType.equals(check.checkType()) && check.detected()).count();
            boolean hadPrevious = previousChecks.stream()
                    .anyMatch(check -> checkType.equals(check.checkType()));
            metrics.add(new QualityModels.CheckMetric(checkType, QualityModels.checkLabel(checkType),
                    properties.severityFor(checkType), run, detected, rate(detected, run),
                    hadPrevious ? previousDetected : null,
                    evolution((double) detected, hadPrevious ? (double) previousDetected : null)));
        }
        metrics.sort(Comparator.comparingLong(QualityModels.CheckMetric::detected).reversed()
                .thenComparing(QualityModels.CheckMetric::checkType));
        return metrics;
    }

    // ------------------------------------------------------------------ croisement satisfaction × conformité

    private static QualityModels.SatisfactionComplianceMatrix matrix(List<QualityModels.CoachFeedback> feedback,
                                                                     List<QualityModels.QualityCheck> checks) {
        Set<String> sessionsWithAnomaly = new LinkedHashSet<>();
        for (QualityModels.QualityCheck check : checks) {
            if (check.detected() && check.sessionId() != null) {
                sessionsWithAnomaly.add(check.sessionId());
            }
        }
        long satisfiedCompliant = 0;
        long satisfiedAnomaly = 0;
        long unsatisfiedCompliant = 0;
        long unsatisfiedAnomaly = 0;
        Set<String> ratedSessions = new LinkedHashSet<>();
        for (QualityModels.CoachFeedback item : feedback) {
            if (item.sessionId() != null) {
                ratedSessions.add(item.sessionId());
            }
            boolean anomaly = item.sessionId() != null && sessionsWithAnomaly.contains(item.sessionId());
            if (item.positive()) {
                if (anomaly) satisfiedAnomaly++; else satisfiedCompliant++;
            } else if (item.negative()) {
                if (anomaly) unsatisfiedAnomaly++; else unsatisfiedCompliant++;
            }
        }
        long unsatisfiedTotal = unsatisfiedCompliant + unsatisfiedAnomaly;
        long unratedWithAnomaly = sessionsWithAnomaly.stream().filter(s -> !ratedSessions.contains(s)).count();
        return new QualityModels.SatisfactionComplianceMatrix(satisfiedCompliant, satisfiedAnomaly,
                unsatisfiedCompliant, unsatisfiedAnomaly,
                rate(unsatisfiedCompliant, unsatisfiedTotal), rate(unsatisfiedAnomaly, unsatisfiedTotal),
                unratedWithAnomaly);
    }

    // ------------------------------------------------------------------ séries & tendances

    private static List<QualityModels.QualityDailyPoint> series(LocalDate from, LocalDate to,
                                                               List<QualityModels.CoachFeedback> feedback,
                                                               List<QualityModels.QualityCheck> checks) {
        List<QualityModels.QualityDailyPoint> points = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= 0 || days > 400) {
            return points; // période déraisonnable : aucune série calculée
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String reference = day.toString();
            List<QualityModels.CoachFeedback> dayFeedback = feedback.stream()
                    .filter(item -> reference.equals(dayOf(item.timestamp(), item.createdAt())))
                    .toList();
            List<QualityModels.QualityCheck> dayChecks = checks.stream()
                    .filter(item -> reference.equals(dayOf(item.timestamp(), null)))
                    .toList();
            List<Integer> ratings = dayFeedback.stream()
                    .map(QualityModels.CoachFeedback::rating)
                    .filter(rating -> rating != null && rating >= 1 && rating <= 5)
                    .toList();
            points.add(new QualityModels.QualityDailyPoint(
                    reference,
                    ratings.size(),
                    ratings.isEmpty() ? null
                            : round(ratings.stream().mapToInt(Integer::intValue).average().orElse(0)),
                    dayFeedback.stream().filter(QualityModels.CoachFeedback::positive).count(),
                    dayFeedback.stream().filter(QualityModels.CoachFeedback::negative).count(),
                    dayChecks.stream().filter(QualityModels.QualityCheck::detected).count()));
        }
        return points;
    }

    private static List<MarketingModels.TrendMetric> trends(List<QualityModels.CoachFeedback> feedback,
                                                            List<QualityModels.CoachFeedback> previousFeedback,
                                                            List<QualityModels.QualityCheck> checks,
                                                            List<QualityModels.QualityCheck> previousChecks) {
        List<MarketingModels.TrendMetric> trends = new ArrayList<>();
        long currentFeedback = feedback.size();
        long previousFeedbackCount = previousFeedback.size();
        long currentAnomalies = checks.stream().filter(QualityModels.QualityCheck::detected).count();
        long previousAnomalies = previousChecks.stream().filter(QualityModels.QualityCheck::detected).count();

        trends.add(new MarketingModels.TrendMetric("OVERVIEW", "feedback", "Avis reçus",
                currentFeedback, previousFeedbackCount, evolution((double) currentFeedback,
                (double) previousFeedbackCount)));
        trends.add(new MarketingModels.TrendMetric("OVERVIEW", "anomalies", "Anomalies détectées",
                currentAnomalies, previousAnomalies, evolution((double) currentAnomalies,
                (double) previousAnomalies)));

        Map<String, Long> currentThemes = countReasons(feedback, false);
        Map<String, Long> previousThemes = countReasons(previousFeedback, false);
        currentThemes.forEach((reason, count) -> {
            Long previousCount = previousThemes.get(reason);
            trends.add(new MarketingModels.TrendMetric("REASON", reason, QualityModels.reasonLabel(reason),
                    count, previousCount == null ? 0 : previousCount,
                    evolution(count.doubleValue(), previousCount == null ? null : previousCount.doubleValue())));
        });
        previousThemes.forEach((reason, count) -> {
            if (!currentThemes.containsKey(reason)) {
                trends.add(new MarketingModels.TrendMetric("REASON", reason, QualityModels.reasonLabel(reason),
                        0, count, evolution(0.0, count.doubleValue())));
            }
        });

        Set<String> types = new LinkedHashSet<>();
        checks.forEach(check -> types.add(check.checkType()));
        previousChecks.forEach(check -> types.add(check.checkType()));
        for (String checkType : types) {
            long current = checks.stream()
                    .filter(check -> checkType.equals(check.checkType()) && check.detected()).count();
            long previous = previousChecks.stream()
                    .filter(check -> checkType.equals(check.checkType()) && check.detected()).count();
            trends.add(new MarketingModels.TrendMetric("CHECK", checkType, QualityModels.checkLabel(checkType),
                    current, previous, evolution((double) current, (double) previous)));
        }

        trends.sort((a, b) -> {
            double ea = a.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(a.evolutionPercent());
            double eb = b.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(b.evolutionPercent());
            return Double.compare(eb, ea);
        });
        return trends;
    }

    // ------------------------------------------------------------------ filtres & utilitaires

    private static boolean matches(QualityModels.QualityFilter filter, QualityModels.CoachFeedback feedback) {
        if (filter.isEmpty()) {
            return true;
        }
        if (filter.rating() != null && !filter.rating().equals(feedback.rating())) {
            return false;
        }
        if (filter.reason() != null && !filter.reason().isBlank()
                && feedback.customerSelectedReasons().stream().noneMatch(reason -> reason.equals(filter.reason()))) {
            return false;
        }
        // Un feedback reste visible pour un filtre de contrôle : le croisement en a besoin.
        return true;
    }

    private static boolean matches(QualityModels.QualityFilter filter, QualityModels.QualityCheck check) {
        if (filter.checkType() != null && !filter.checkType().isBlank()
                && !filter.checkType().equals(check.checkType())) {
            return false;
        }
        if (filter.severity() != null && !filter.severity().isBlank()
                && !filter.severity().equals(check.severity())) {
            return false;
        }
        if (filter.rating() != null || (filter.reason() != null && !filter.reason().isBlank())) {
            // Filtre portant sur la satisfaction : on ne conserve que les contrôles des mêmes sessions.
            return false;
        }
        return true;
    }

    private static String dayOf(String timestamp, String fallback) {
        String reference = timestamp != null && !timestamp.isBlank() ? timestamp : fallback;
        return reference == null || reference.length() < 10 ? null : reference.substring(0, 10);
    }

    /** Taux borné : {@code null} si le dénominateur est nul (aucune division par zéro). */
    private static Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return round((double) numerator / denominator);
    }

    /** Variation en % ; {@code null} si la base est absente ou nulle. */
    private static Double evolution(Double current, Double previous) {
        if (current == null || previous == null || previous == 0.0) {
            return null;
        }
        return round((current - previous) / previous * 100);
    }

    private static Double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
