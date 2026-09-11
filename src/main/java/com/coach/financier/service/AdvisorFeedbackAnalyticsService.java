package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.QualityModels;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MOTEUR ANALYTIQUE du module Feedback Conseiller : tous les KPI sont calculés ici, de manière
 * DÉTERMINISTE (le LLM n'en calcule aucun — §2 du prompt).
 * <p>
 * Deux précautions structurelles :
 * <ul>
 *   <li>seule la <b>version courante</b> de chaque session est agrégée (une révision ne compte pas
 *       deux fois), l'historique restant conservé sur disque ;</li>
 *   <li>un taux dont le dénominateur est nul vaut {@code null} : aucune statistique n'est inventée.</li>
 * </ul>
 */
@Service
public class AdvisorFeedbackAnalyticsService {

    private final AdvisorFeedbackStore store;
    private final AdvisorFeedbackProperties properties;
    private final QualityCheckStore qualityCheckStore;

    public AdvisorFeedbackAnalyticsService(AdvisorFeedbackStore store,
                                           AdvisorFeedbackProperties properties,
                                           QualityCheckStore qualityCheckStore) {
        this.store = store;
        this.properties = properties;
        this.qualityCheckStore = qualityCheckStore;
    }

    /** Agrégats complets d'une période (bornes incluses). */
    public AdvisorFeedbackModels.AdvisorFeedbackAggregates compute(LocalDate from, LocalDate to,
                                                                  AdvisorFeedbackModels.AdvisorFeedbackFilter filter) {
        AdvisorFeedbackModels.AdvisorFeedbackFilter effective =
                filter == null ? AdvisorFeedbackModels.AdvisorFeedbackFilter.none() : filter;
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? LocalDate.now() : to;

        AdvisorFeedbackStore.ReadResult result = store.read(start, end);
        List<AdvisorFeedbackModels.AdvisorFeedback> current = currentVersions(result.feedback()).stream()
                .filter(item -> matches(effective, item))
                .toList();

        AdvisorFeedbackStore.ReadResult previousResult = store.read(
                start.minusDays(ChronoUnit.DAYS.between(start, end) + 1), start.minusDays(1));
        List<AdvisorFeedbackModels.AdvisorFeedback> previous = currentVersions(previousResult.feedback()).stream()
                .filter(item -> matches(effective, item))
                .toList();

        long closedSessions = closedSessions(qualityCheckStore.read(start, end).values());

        return new AdvisorFeedbackModels.AdvisorFeedbackAggregates(
                start.toString(), end.toString(),
                kpis(current, previous, closedSessions),
                assessments(current, previous),
                areas(current, previous),
                reasons(current, previous),
                products(current, previous),
                interestCorrections(current),
                emailQuality(current, previous),
                series(start, end, current),
                trends(current, previous),
                anonymizedComments(current),
                result.invalidLines(),
                current.stream().anyMatch(item -> AdvisorFeedbackModels.SOURCE_DEMO.equals(item.source())),
                Instant.now().toString());
    }

    /** Feedbacks bruts d'une période (diagnostic / export). */
    public List<AdvisorFeedbackModels.AdvisorFeedback> feedback(LocalDate from, LocalDate to) {
        return store.read(from, to).feedback();
    }

    /** Feedback courant d'une session (affichage avant révision). */
    public java.util.Optional<AdvisorFeedbackModels.AdvisorFeedback> currentOf(String sessionId) {
        return store.latestBySession(sessionId);
    }

    /** Jours disposant de feedbacks conseiller. */
    public List<LocalDate> availableDays() {
        return store.availableDays();
    }

    // ------------------------------------------------------------------ KPI (§18 à §21)

    private AdvisorFeedbackModels.AdvisorKpis kpis(List<AdvisorFeedbackModels.AdvisorFeedback> current,
                                                    List<AdvisorFeedbackModels.AdvisorFeedback> previous,
                                                    long closedSessions) {
        long count = current.size();
        long relevant = countOf(current, AdvisorFeedbackModels.RELEVANT);
        long toImprove = countOf(current, AdvisorFeedbackModels.NEEDS_IMPROVEMENT);
        long incorrect = countOf(current, AdvisorFeedbackModels.INCORRECT);

        List<AdvisorFeedbackModels.ProductFeedback> productFeedback = current.stream()
                .flatMap(item -> item.productFeedback().stream())
                .filter(item -> item.advisorAssessment() != null)
                .toList();
        long productRelevant = productFeedback.stream()
                .filter(item -> AdvisorFeedbackModels.PRODUCT_RELEVANT.equals(item.advisorAssessment())).count();
        long productNotRelevant = productFeedback.stream()
                .filter(item -> AdvisorFeedbackModels.PRODUCT_NOT_RELEVANT.equals(item.advisorAssessment())).count();

        long emailsReadyOrMinor = current.stream()
                .filter(item -> AdvisorFeedbackModels.EMAIL_READY.equals(item.clientEmailAssessment())
                        || AdvisorFeedbackModels.EMAIL_MINOR.equals(item.clientEmailAssessment()))
                .count();
        long emailsAssessed = current.stream()
                .filter(item -> item.clientEmailAssessment() != null)
                .count();

        long corrections = current.stream()
                .flatMap(item -> item.productFeedback().stream())
                .filter(this::isCorrection)
                .count();

        long previousCount = previous.size();
        Double previousRelevantRate = rate(countOf(previous, AdvisorFeedbackModels.RELEVANT), previousCount);

        return new AdvisorFeedbackModels.AdvisorKpis(
                distinctSessions(current),
                count,
                rate(count, closedSessions),
                rate(relevant, count),
                rate(toImprove, count),
                rate(incorrect, count),
                productFeedback.size(),
                productRelevant,
                productNotRelevant,
                rate(productRelevant, productFeedback.size()),
                emailsReadyOrMinor,
                rate(emailsReadyOrMinor, emailsAssessed),
                corrections,
                previous.isEmpty() ? null : previousCount,
                previousRelevantRate,
                evolution(rate(relevant, count), previousRelevantRate),
                count >= properties.sufficientSampleSize());
    }

    private List<AdvisorFeedbackModels.AssessmentMetric> assessments(
            List<AdvisorFeedbackModels.AdvisorFeedback> current,
            List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        List<AdvisorFeedbackModels.AssessmentMetric> metrics = new ArrayList<>();
        for (String code : AdvisorFeedbackModels.ASSESSMENTS) {
            long count = countOf(current, code);
            long previousCount = countOf(previous, code);
            metrics.add(new AdvisorFeedbackModels.AssessmentMetric(code,
                    AdvisorFeedbackModels.assessmentLabel(code), count, share(count, current.size()),
                    previous.isEmpty() ? null : evolution((double) count, (double) previousCount)));
        }
        return metrics;
    }

    private List<AdvisorFeedbackModels.AreaMetric> areas(List<AdvisorFeedbackModels.AdvisorFeedback> current,
                                                        List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        Map<String, Long> currentCounts = countByArea(current);
        Map<String, Long> previousCounts = countByArea(previous);
        long total = currentCounts.values().stream().mapToLong(Long::longValue).sum();
        List<AdvisorFeedbackModels.AreaMetric> metrics = new ArrayList<>();
        for (String area : AdvisorFeedbackModels.AREAS) {
            long count = currentCounts.getOrDefault(area, 0L);
            Long previousCount = previousCounts.get(area);
            if (count == 0 && (previousCount == null || previousCount == 0)) {
                continue;
            }
            metrics.add(new AdvisorFeedbackModels.AreaMetric(area, AdvisorFeedbackModels.areaLabel(area), count,
                    share(count, total), previousCount == null ? null
                    : evolution((double) count, (double) previousCount)));
        }
        metrics.sort(Comparator.comparingLong(AdvisorFeedbackModels.AreaMetric::count).reversed());
        return metrics;
    }

    private List<AdvisorFeedbackModels.ReasonMetric> reasons(List<AdvisorFeedbackModels.AdvisorFeedback> current,
                                                            List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        Map<String, Long> currentCounts = countByReason(current);
        Map<String, Long> previousCounts = countByReason(previous);
        List<AdvisorFeedbackModels.ReasonMetric> metrics = new ArrayList<>();
        for (String reason : AdvisorFeedbackModels.REASONS) {
            long count = currentCounts.getOrDefault(reason, 0L);
            Long previousCount = previousCounts.get(reason);
            if (count == 0 && (previousCount == null || previousCount == 0)) {
                continue;
            }
            metrics.add(new AdvisorFeedbackModels.ReasonMetric(reason,
                    AdvisorFeedbackModels.reasonLabel(reason), count, previousCount == null ? null
                    : evolution((double) count, (double) previousCount)));
        }
        metrics.sort(Comparator.comparingLong(AdvisorFeedbackModels.ReasonMetric::count).reversed());
        return metrics;
    }

    private List<AdvisorFeedbackModels.ProductFeedbackMetric> products(
            List<AdvisorFeedbackModels.AdvisorFeedback> current,
            List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        Map<String, ProductAcc> currentAccs = accumulateProducts(current);
        Map<String, ProductAcc> previousAccs = accumulateProducts(previous);
        List<AdvisorFeedbackModels.ProductFeedbackMetric> metrics = new ArrayList<>();
        for (ProductAcc acc : currentAccs.values()) {
            ProductAcc previousAcc = previousAccs.get(acc.id);
            metrics.add(new AdvisorFeedbackModels.ProductFeedbackMetric(acc.id, acc.name, acc.assessments,
                    acc.relevant, acc.notRelevant, rate(acc.relevant, acc.assessments), acc.corrections,
                    acc.added, previousAcc == null ? null
                    : evolution((double) acc.notRelevant, (double) previousAcc.notRelevant)));
        }
        metrics.sort(Comparator.comparingLong(AdvisorFeedbackModels.ProductFeedbackMetric::assessments).reversed()
                .thenComparing(AdvisorFeedbackModels.ProductFeedbackMetric::productId));
        return metrics;
    }

    private List<AdvisorFeedbackModels.InterestCorrectionMetric> interestCorrections(
            List<AdvisorFeedbackModels.AdvisorFeedback> current) {
        Map<String, AdvisorFeedbackModels.InterestCorrectionMetric> counts = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorFeedback feedback : current) {
            for (AdvisorFeedbackModels.ProductFeedback product : feedback.productFeedback()) {
                if (!isCorrection(product)) {
                    continue;
                }
                String key = product.productId() + "|" + product.aiInterestLevel() + "|" + product.advisorInterestLevel();
                AdvisorFeedbackModels.InterestCorrectionMetric existing = counts.get(key);
                counts.put(key, new AdvisorFeedbackModels.InterestCorrectionMetric(product.productId(),
                        product.productName(), product.aiInterestLevel(), product.advisorInterestLevel(),
                        existing == null ? 1 : existing.count() + 1));
            }
        }
        List<AdvisorFeedbackModels.InterestCorrectionMetric> metrics = new ArrayList<>(counts.values());
        metrics.sort(Comparator.comparingLong(AdvisorFeedbackModels.InterestCorrectionMetric::count).reversed());
        return metrics;
    }

    private List<AdvisorFeedbackModels.EmailQualityMetric> emailQuality(
            List<AdvisorFeedbackModels.AdvisorFeedback> current,
            List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        long assessed = current.stream().filter(item -> item.clientEmailAssessment() != null).count();
        long previousAssessed = previous.stream().filter(item -> item.clientEmailAssessment() != null).count();
        List<AdvisorFeedbackModels.EmailQualityMetric> metrics = new ArrayList<>();
        for (String code : AdvisorFeedbackModels.EMAIL_ASSESSMENTS) {
            long count = current.stream().filter(item -> code.equals(item.clientEmailAssessment())).count();
            long previousCount = previous.stream().filter(item -> code.equals(item.clientEmailAssessment())).count();
            if (count == 0 && previousCount == 0) {
                continue;
            }
            metrics.add(new AdvisorFeedbackModels.EmailQualityMetric(code,
                    AdvisorFeedbackModels.emailLabel(code), count, share(count, assessed),
                    previousAssessed == 0 ? null : evolution((double) count, (double) previousCount)));
        }
        return metrics;
    }

    // ------------------------------------------------------------------ séries & tendances

    private static List<AdvisorFeedbackModels.AdvisorDailyPoint> series(
            LocalDate from, LocalDate to, List<AdvisorFeedbackModels.AdvisorFeedback> current) {
        List<AdvisorFeedbackModels.AdvisorDailyPoint> points = new ArrayList<>();
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= 0 || days > 400) {
            return points;
        }
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String reference = day.toString();
            List<AdvisorFeedbackModels.AdvisorFeedback> dayFeedback = current.stream()
                    .filter(item -> reference.equals(dayOf(item)))
                    .toList();
            points.add(new AdvisorFeedbackModels.AdvisorDailyPoint(reference, dayFeedback.size(),
                    countOf(dayFeedback, AdvisorFeedbackModels.RELEVANT),
                    countOf(dayFeedback, AdvisorFeedbackModels.NEEDS_IMPROVEMENT),
                    countOf(dayFeedback, AdvisorFeedbackModels.INCORRECT),
                    dayFeedback.stream().flatMap(item -> item.productFeedback().stream())
                            .filter(item -> AdvisorFeedbackModels.PRODUCT_NOT_RELEVANT
                                    .equals(item.advisorAssessment())).count()));
        }
        return points;
    }

    private static List<MarketingModels.TrendMetric> trends(
            List<AdvisorFeedbackModels.AdvisorFeedback> current,
            List<AdvisorFeedbackModels.AdvisorFeedback> previous) {
        List<MarketingModels.TrendMetric> trends = new ArrayList<>();
        trends.add(new MarketingModels.TrendMetric("OVERVIEW", "feedback", "Dossiers évalués",
                current.size(), previous.size(), evolution((double) current.size(), (double) previous.size())));
        trends.add(new MarketingModels.TrendMetric("OVERVIEW", "incorrect", "Analyses jugées incorrectes",
                countOf(current, AdvisorFeedbackModels.INCORRECT),
                countOf(previous, AdvisorFeedbackModels.INCORRECT),
                evolution((double) countOf(current, AdvisorFeedbackModels.INCORRECT),
                        (double) countOf(previous, AdvisorFeedbackModels.INCORRECT))));

        Map<String, Long> currentAreas = countByArea(current);
        Map<String, Long> previousAreas = countByArea(previous);
        currentAreas.forEach((area, count) -> trends.add(new MarketingModels.TrendMetric("AREA", area,
                AdvisorFeedbackModels.areaLabel(area), count, previousAreas.getOrDefault(area, 0L),
                previousAreas.containsKey(area)
                        ? evolution((double) count, (double) previousAreas.get(area)) : null)));
        previousAreas.forEach((area, count) -> {
            if (!currentAreas.containsKey(area)) {
                trends.add(new MarketingModels.TrendMetric("AREA", area, AdvisorFeedbackModels.areaLabel(area),
                        0, count, evolution(0.0, (double) count)));
            }
        });
        trends.sort((a, b) -> {
            double ea = a.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(a.evolutionPercent());
            double eb = b.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(b.evolutionPercent());
            return Double.compare(eb, ea);
        });
        return trends;
    }

    private static List<AdvisorFeedbackModels.AnonymizedComment> anonymizedComments(
            List<AdvisorFeedbackModels.AdvisorFeedback> current) {
        // Les commentaires sont des données NON FIABLES (§13 du prompt) : nettoyés avant tout usage.
        List<AdvisorFeedbackModels.AnonymizedComment> comments = new ArrayList<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : current) {
            if (item.comment() == null || item.comment().isBlank()) {
                continue;
            }
            String sanitized = QualityModels.sanitizeComment(item.comment(), 1000);
            if (!sanitized.isBlank()) {
                comments.add(new AdvisorFeedbackModels.AnonymizedComment(item.feedbackId(), sanitized));
            }
        }
        return comments;
    }

    // ------------------------------------------------------------------ utilitaires

    /** Ne conserve que la version la plus récente de chaque session (une révision ne compte pas deux fois). */
    static List<AdvisorFeedbackModels.AdvisorFeedback> currentVersions(
            List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        Map<String, AdvisorFeedbackModels.AdvisorFeedback> latest = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : feedback) {
            if (item == null || item.sessionId() == null) {
                continue;
            }
            AdvisorFeedbackModels.AdvisorFeedback existing = latest.get(item.sessionId());
            if (existing == null || item.version() >= existing.version()) {
                latest.put(item.sessionId(), item);
            }
        }
        return List.copyOf(latest.values());
    }

    private static long distinctSessions(List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        Set<String> sessions = new LinkedHashSet<>();
        feedback.forEach(item -> {
            if (item.sessionId() != null) {
                sessions.add(item.sessionId());
            }
        });
        return sessions.size();
    }

    /** Conversations clôturées de la période (module Qualité) : dénominateur du taux de feedback. */
    private static long closedSessions(List<QualityModels.QualityCheck> checks) {
        Set<String> sessions = new LinkedHashSet<>();
        for (QualityModels.QualityCheck check : checks) {
            if (check != null && check.sessionId() != null) {
                sessions.add(check.sessionId());
            }
        }
        return sessions.size();
    }

    private boolean isCorrection(AdvisorFeedbackModels.ProductFeedback product) {
        return product.aiInterestLevel() != null && product.advisorInterestLevel() != null
                && !product.aiInterestLevel().equals(product.advisorInterestLevel());
    }

    private static long countOf(List<AdvisorFeedbackModels.AdvisorFeedback> feedback, String assessment) {
        return feedback.stream().filter(item -> assessment.equals(item.overallAssessment())).count();
    }

    private static Map<String, Long> countByArea(List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : feedback) {
            for (AdvisorFeedbackModels.AdvisorIssue issue : item.issues()) {
                counts.merge(issue.area() == null ? AdvisorFeedbackModels.AREA_OTHER : issue.area(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static Map<String, Long> countByReason(List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : feedback) {
            for (AdvisorFeedbackModels.AdvisorIssue issue : item.issues()) {
                if (issue.reason() == null) {
                    continue;
                }
                counts.merge(issue.reason(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static Map<String, ProductAcc> accumulateProducts(
            List<AdvisorFeedbackModels.AdvisorFeedback> feedback) {
        Map<String, ProductAcc> accs = new LinkedHashMap<>();
        for (AdvisorFeedbackModels.AdvisorFeedback item : feedback) {
            for (AdvisorFeedbackModels.ProductFeedback product : item.productFeedback()) {
                if (product.productId() == null) {
                    continue;
                }
                ProductAcc acc = accs.computeIfAbsent(product.productId(),
                        id -> new ProductAcc(id, product.productName()));
                if (product.productName() != null) {
                    acc.name = product.productName();
                }
                if (product.advisorAssessment() != null) {
                    acc.assessments++;
                    if (AdvisorFeedbackModels.PRODUCT_RELEVANT.equals(product.advisorAssessment())) {
                        acc.relevant++;
                    } else {
                        acc.notRelevant++;
                    }
                }
                if (product.aiInterestLevel() != null && product.advisorInterestLevel() != null
                        && !product.aiInterestLevel().equals(product.advisorInterestLevel())) {
                    acc.corrections++;
                }
            }
            for (String missing : item.missingProductIds()) {
                if (missing == null || missing.isBlank()) {
                    continue;
                }
                ProductAcc acc = accs.computeIfAbsent(missing, id -> new ProductAcc(id, null));
                acc.added++;
            }
        }
        return accs;
    }

    private static boolean matches(AdvisorFeedbackModels.AdvisorFeedbackFilter filter,
                                   AdvisorFeedbackModels.AdvisorFeedback feedback) {
        if (filter.isEmpty()) {
            return true;
        }
        if (filter.assessment() != null && !filter.assessment().isBlank()
                && !filter.assessment().equals(feedback.overallAssessment())) {
            return false;
        }
        if (filter.emailAssessment() != null && !filter.emailAssessment().isBlank()
                && !filter.emailAssessment().equals(feedback.clientEmailAssessment())) {
            return false;
        }
        if (filter.area() != null && !filter.area().isBlank()
                && feedback.issues().stream().noneMatch(issue -> filter.area().equals(issue.area()))) {
            return false;
        }
        if (filter.productId() != null && !filter.productId().isBlank()
                && feedback.productFeedback().stream().noneMatch(p -> filter.productId().equals(p.productId()))
                && !feedback.missingProductIds().contains(filter.productId())) {
            return false;
        }
        return true;
    }

    private static String dayOf(AdvisorFeedbackModels.AdvisorFeedback feedback) {
        String reference = feedback.timestamp() != null && !feedback.timestamp().isBlank()
                ? feedback.timestamp() : feedback.createdAt();
        return reference == null || reference.length() < 10 ? null : reference.substring(0, 10);
    }

    /** Taux borné ; {@code null} si le dénominateur est nul (aucune division par zéro). */
    private static Double rate(long numerator, long denominator) {
        return denominator <= 0 ? null : Math.round((double) numerator / denominator * 10000) / 10000.0;
    }

    /** Part destinée à un champ primitif : 0 si le dénominateur est nul (aucune division par zéro). */
    private static double share(long numerator, long denominator) {
        Double value = rate(numerator, denominator);
        return value == null ? 0.0 : value;
    }

    private static Double evolution(Double current, Double previous) {
        if (current == null || previous == null || previous == 0.0) {
            return null;
        }
        return Math.round((current - previous) / previous * 10000) / 100.0;
    }

    /** Accumulateur interne par produit. */
    private static final class ProductAcc {
        private final String id;
        private String name;
        private long assessments;
        private long relevant;
        private long notRelevant;
        private long corrections;
        private long added;

        private ProductAcc(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
