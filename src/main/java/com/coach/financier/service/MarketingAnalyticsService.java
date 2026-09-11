package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.MarketingModels;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MOTEUR ANALYTIQUE DÉTERMINISTE (aucun LLM, §2/§20-§25) : lit les événements de la période
 * demandée et calcule KPI, métriques produit/projet, refus, cross-sell, besoins non couverts,
 * informations manquantes, séries temporelles et tendances.
 * <p>
 * Toutes les divisions sont protégées (retour {@code null} = « non calculable »), et les fichiers
 * hors période ne sont même pas ouverts.
 */
@Service
public class MarketingAnalyticsService {

    private final MarketingEventStore eventStore;
    private final MarketingProperties properties;

    public MarketingAnalyticsService(MarketingEventStore eventStore, MarketingProperties properties) {
        this.eventStore = eventStore;
        this.properties = properties;
    }

    /** Agrégats complets pour une période et des filtres optionnels. */
    public MarketingModels.MarketingAggregates compute(LocalDate from, LocalDate to,
                                                       MarketingModels.MarketingFilter filter) {
        MarketingModels.MarketingFilter effective = filter == null ? MarketingModels.MarketingFilter.none() : filter;
        MarketingEventStore.ReadResult window = eventStore.read(from, to);
        List<MarketingModels.MarketingEvent> events = new ArrayList<>();
        for (MarketingModels.MarketingEvent event : window.events()) {
            if (matches(event, effective)) {
                events.add(event);
            }
        }

        long periodDays = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(periodDays - 1);
        List<MarketingModels.MarketingEvent> previousEvents = new ArrayList<>();
        for (MarketingModels.MarketingEvent event : eventStore.read(previousFrom, previousTo).events()) {
            if (matches(event, effective)) {
                previousEvents.add(event);
            }
        }

        Map<String, ProductAcc> products = productAccumulators(events);
        Map<String, Long> previousInterested = interestedSessionsByProduct(previousEvents);

        return new MarketingModels.MarketingAggregates(
                from.toString(),
                to.toString(),
                overview(events),
                productMetrics(products, previousInterested),
                projectMetrics(events, previousEvents),
                rejectionMetrics(events),
                crossSell(events),
                unmetNeeds(events),
                missingInformation(events),
                series(events, from, to),
                trends(events, previousEvents, products),
                properties.isDemoMode(),
                window.invalidLines(),
                Instant.now().toString());
    }

    /** Événements bruts d'une période (filtres appliqués), pour l'IHM si besoin. */
    public List<MarketingModels.MarketingEvent> events(LocalDate from, LocalDate to,
                                                       MarketingModels.MarketingFilter filter) {
        MarketingModels.MarketingFilter effective = filter == null ? MarketingModels.MarketingFilter.none() : filter;
        return eventStore.read(from, to).events().stream().filter(event -> matches(event, effective)).toList();
    }

    public List<LocalDate> availableDays() {
        return eventStore.availableDays();
    }

    // ------------------------------------------------------------------ filtres

    private static boolean matches(MarketingModels.MarketingEvent event, MarketingModels.MarketingFilter filter) {
        if (filter.isEmpty()) {
            return true;
        }
        if (filter.eventType() != null && !filter.eventType().equalsIgnoreCase(event.eventType())) {
            return false;
        }
        if (filter.productId() != null && !filter.productId().equalsIgnoreCase(event.productId())) {
            return false;
        }
        if (filter.productFamily() != null && !filter.productFamily().equalsIgnoreCase(event.productFamily())) {
            return false;
        }
        if (filter.projectType() != null && !filter.projectType().equalsIgnoreCase(event.projectType())) {
            return false;
        }
        return filter.interestLevel() == null
                || (event.interestLevel() != null && filter.interestLevel().equalsIgnoreCase(event.interestLevel()));
    }

    // ------------------------------------------------------------------ KPI globaux (§19)

    private static MarketingModels.MarketingOverview overview(List<MarketingModels.MarketingEvent> events) {
        Set<String> sessions = new LinkedHashSet<>();
        Set<String> interestedSessions = new LinkedHashSet<>();
        Set<String> customers = new LinkedHashSet<>();
        long high = 0;
        long subscription = 0;
        long appointment = 0;
        long unmet = 0;
        long missingInfo = 0;
        long coachQuality = 0;
        for (MarketingModels.MarketingEvent event : events) {
            if (event.sessionId() != null) sessions.add(event.sessionId());
            if (event.anonymousCustomerId() != null) customers.add(event.anonymousCustomerId());
            switch (value(event.eventType())) {
                case MarketingModels.PRODUCT_INTEREST -> {
                    if (event.sessionId() != null) interestedSessions.add(event.sessionId());
                    if ("HIGH".equalsIgnoreCase(value(event.interestLevel()))) high++;
                }
                case MarketingModels.SUBSCRIPTION_INTEREST -> subscription++;
                case MarketingModels.APPOINTMENT_INTEREST -> appointment++;
                case MarketingModels.UNMET_NEED -> unmet++;
                case MarketingModels.MISSING_PRODUCT_INFORMATION -> missingInfo++;
                default -> {
                    if (MarketingModels.isCoachQuality(event.eventType())) coachQuality++;
                }
            }
        }
        return new MarketingModels.MarketingOverview(sessions.size(), interestedSessions.size(), customers.size(),
                high, subscription, appointment, unmet, missingInfo, coachQuality);
    }

    // ------------------------------------------------------------------ métriques produit (§20/§24)

    private static final class ProductAcc {
        final String id;
        final String name;
        final String family;
        final Set<String> recommendedSessions = new LinkedHashSet<>();
        final Set<String> interestedSessions = new LinkedHashSet<>();
        final Set<String> interestedCustomers = new LinkedHashSet<>();
        long high;
        long medium;
        long low;
        long rejected;
        long comparison;
        long subscription;
        long appointment;
        double score;

        ProductAcc(String id, String name, String family) {
            this.id = id;
            this.name = name;
            this.family = family;
        }
    }

    private Map<String, ProductAcc> productAccumulators(List<MarketingModels.MarketingEvent> events) {
        Map<String, ProductAcc> accs = new LinkedHashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (event.productId() == null || event.productId().isBlank()) {
                continue;
            }
            ProductAcc acc = accs.computeIfAbsent(event.productId(),
                    id -> new ProductAcc(id, name(event.productName(), id), blank(event.productFamily())));
            acc.score += properties.scoreFor(event.eventType(), event.interestLevel());
            switch (value(event.eventType())) {
                case MarketingModels.PRODUCT_RECOMMENDED -> addSession(acc.recommendedSessions, event);
                case MarketingModels.PRODUCT_INTEREST -> {
                    addSession(acc.interestedSessions, event);
                    if (event.anonymousCustomerId() != null) acc.interestedCustomers.add(event.anonymousCustomerId());
                    switch (value(event.interestLevel())) {
                        case "HIGH" -> acc.high++;
                        case "MEDIUM" -> acc.medium++;
                        default -> acc.low++;
                    }
                }
                case MarketingModels.PRODUCT_REJECTED -> acc.rejected++;
                case MarketingModels.PRODUCT_COMPARISON -> acc.comparison++;
                case MarketingModels.SUBSCRIPTION_INTEREST -> acc.subscription++;
                case MarketingModels.APPOINTMENT_INTEREST -> acc.appointment++;
                default -> { }
            }
        }
        return accs;
    }

    private List<MarketingModels.ProductMetric> productMetrics(Map<String, ProductAcc> accs,
                                                              Map<String, Long> previousInterested) {
        List<MarketingModels.ProductMetric> metrics = new ArrayList<>();
        for (ProductAcc acc : accs.values()) {
            long interested = acc.interestedSessions.size();
            // Taux d'intérêt = sessions intéressées / sessions où le produit a été RECOMMANDÉ,
            // plafonné à 100 % (un intérêt peut naître sans recommandation explicite : données de démo,
            // produit déjà connu du client… mais un « taux » ne peut pas dépasser 100 %).
            Double rate = acc.recommendedSessions.isEmpty()
                    ? null : round(Math.min(1.0, (double) interested / acc.recommendedSessions.size()), 4);
            Long previous = previousInterested.get(acc.id);
            metrics.add(new MarketingModels.ProductMetric(acc.id, acc.name, acc.family,
                    acc.recommendedSessions.size(), interested, acc.interestedCustomers.size(),
                    acc.high, acc.medium, acc.low, acc.rejected, acc.comparison, acc.subscription,
                    acc.appointment, rate, round(acc.score, 2), previous, evolution(interested, previous)));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.ProductMetric::interestedSessions).reversed()
                .thenComparing(MarketingModels.ProductMetric::productId));
        return metrics;
    }

    private static Map<String, Long> interestedSessionsByProduct(List<MarketingModels.MarketingEvent> events) {
        Map<String, Set<String>> sessions = new HashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (MarketingModels.PRODUCT_INTEREST.equals(event.eventType())
                    && event.productId() != null && event.sessionId() != null) {
                sessions.computeIfAbsent(event.productId(), id -> new LinkedHashSet<>()).add(event.sessionId());
            }
        }
        Map<String, Long> result = new LinkedHashMap<>();
        sessions.forEach((id, set) -> result.put(id, (long) set.size()));
        return result;
    }

    // ------------------------------------------------------------------ métriques projet (§21)

    private List<MarketingModels.ProjectMetric> projectMetrics(List<MarketingModels.MarketingEvent> events,
                                                               List<MarketingModels.MarketingEvent> previousEvents) {
        Map<String, Set<String>> volume = new LinkedHashMap<>();
        Map<String, Map<String, Long>> ranges = new LinkedHashMap<>();
        Map<String, Long> interests = new LinkedHashMap<>();
        Map<String, Long> rejections = new LinkedHashMap<>();
        Map<String, Long> unmet = new LinkedHashMap<>();
        Map<String, Map<String, long[]>> productCounts = new LinkedHashMap<>();
        Map<String, String> productNames = new LinkedHashMap<>();

        for (MarketingModels.MarketingEvent event : events) {
            String project = blank(event.projectType());
            if (project == null) {
                continue;
            }
            if (MarketingModels.PROJECT_DETECTED.equals(event.eventType())) {
                if (event.sessionId() != null) volume.computeIfAbsent(project, p -> new LinkedHashSet<>()).add(event.sessionId());
                String range = blank(event.projectAmountRange());
                if (range != null) {
                    ranges.computeIfAbsent(project, p -> new LinkedHashMap<>()).merge(range, 1L, Long::sum);
                }
            }
            if (MarketingModels.PRODUCT_INTEREST.equals(event.eventType())) {
                interests.merge(project, 1L, Long::sum);
                if (event.productId() != null) {
                    productCounts.computeIfAbsent(project, p -> new LinkedHashMap<>())
                            .computeIfAbsent(event.productId(), id -> new long[1])[0]++;
                    productNames.putIfAbsent(event.productId(), name(event.productName(), event.productId()));
                }
            }
            if (MarketingModels.PRODUCT_REJECTED.equals(event.eventType())) rejections.merge(project, 1L, Long::sum);
            if (MarketingModels.UNMET_NEED.equals(event.eventType())) unmet.merge(project, 1L, Long::sum);
        }

        Map<String, Set<String>> previousVolume = new LinkedHashMap<>();
        for (MarketingModels.MarketingEvent event : previousEvents) {
            if (MarketingModels.PROJECT_DETECTED.equals(event.eventType())
                    && event.projectType() != null && event.sessionId() != null) {
                previousVolume.computeIfAbsent(event.projectType(), p -> new LinkedHashSet<>()).add(event.sessionId());
            }
        }

        long totalVolume = volume.values().stream().mapToLong(Set::size).sum();
        List<MarketingModels.ProjectMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : volume.entrySet()) {
            String project = entry.getKey();
            long count = entry.getValue().size();
            List<MarketingModels.ProductCount> topProducts = new ArrayList<>();
            Map<String, long[]> counts = productCounts.getOrDefault(project, Map.of());
            counts.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                    .limit(5)
                    .forEach(e -> topProducts.add(new MarketingModels.ProductCount(
                            e.getKey(), productNames.getOrDefault(e.getKey(), e.getKey()), e.getValue()[0])));
            Long previous = previousVolume.containsKey(project)
                    ? (long) previousVolume.get(project).size() : null;
            metrics.add(new MarketingModels.ProjectMetric(project, count,
                    totalVolume == 0 ? null : round((double) count / totalVolume, 4),
                    ranges.getOrDefault(project, Map.of()),
                    interests.getOrDefault(project, 0L),
                    rejections.getOrDefault(project, 0L),
                    unmet.getOrDefault(project, 0L),
                    topProducts, previous, evolution(count, previous)));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.ProjectMetric::volume).reversed()
                .thenComparing(MarketingModels.ProjectMetric::projectType));
        return metrics;
    }

    // ------------------------------------------------------------------ refus (§22)

    private static List<MarketingModels.RejectionMetric> rejectionMetrics(List<MarketingModels.MarketingEvent> events) {
        Map<String, long[]> byReason = new LinkedHashMap<>();
        long total = 0;
        for (MarketingModels.MarketingEvent event : events) {
            if (!MarketingModels.PRODUCT_REJECTED.equals(event.eventType())) {
                continue;
            }
            total++;
            byReason.computeIfAbsent(name(event.reasonCategory(), "OTHER"), r -> new long[1])[0]++;
        }
        List<MarketingModels.RejectionMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byReason.entrySet()) {
            metrics.add(new MarketingModels.RejectionMetric(entry.getKey(), null, null, entry.getValue()[0],
                    total == 0 ? null : round((double) entry.getValue()[0] / total, 4)));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.RejectionMetric::count).reversed());
        return metrics;
    }

    /** Refus détaillés par produit (drill-down). */
    public List<MarketingModels.RejectionMetric> rejectionsByProduct(LocalDate from, LocalDate to, String productId) {
        MarketingModels.MarketingFilter filter = new MarketingModels.MarketingFilter(productId, null, null, null,
                MarketingModels.PRODUCT_REJECTED);
        Map<String, long[]> byReason = new LinkedHashMap<>();
        String productName = null;
        long total = 0;
        for (MarketingModels.MarketingEvent event : events(from, to, filter)) {
            total++;
            byReason.computeIfAbsent(name(event.reasonCategory(), "OTHER"), r -> new long[1])[0]++;
            productName = name(event.productName(), productName);
        }
        List<MarketingModels.RejectionMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byReason.entrySet()) {
            metrics.add(new MarketingModels.RejectionMetric(entry.getKey(), productId, productName, entry.getValue()[0],
                    total == 0 ? null : round((double) entry.getValue()[0] / total, 4)));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.RejectionMetric::count).reversed());
        return metrics;
    }

    // ------------------------------------------------------------------ cross-sell (§23)

    private List<MarketingModels.CrossSellMetric> crossSell(List<MarketingModels.MarketingEvent> events) {
        Map<String, Set<String>> productsBySession = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (!MarketingModels.PRODUCT_INTEREST.equals(event.eventType())
                    || event.productId() == null || event.sessionId() == null) {
                continue;
            }
            productsBySession.computeIfAbsent(event.sessionId(), s -> new LinkedHashSet<>()).add(event.productId());
            names.putIfAbsent(event.productId(), name(event.productName(), event.productId()));
        }
        Map<String, Long> sessionsPerProduct = new LinkedHashMap<>();
        Map<String, long[]> pairs = new LinkedHashMap<>();
        for (Set<String> productIds : productsBySession.values()) {
            // Toutes les sessions d'intérêt comptent au dénominateur (même sans association).
            for (String id : productIds) {
                sessionsPerProduct.merge(id, 1L, Long::sum);
            }
            if (productIds.size() < 2) {
                continue;
            }
            List<String> sorted = new ArrayList<>(productIds);
            sorted.sort(String::compareTo);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = 0; j < sorted.size(); j++) {
                    if (i == j) continue;
                    pairs.computeIfAbsent(sorted.get(i) + "|" + sorted.get(j), k -> new long[1])[0]++;
                }
            }
        }
        List<MarketingModels.CrossSellMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : pairs.entrySet()) {
            String[] ids = entry.getKey().split("\\|", 2);
            long common = entry.getValue()[0];
            long sourceSessions = sessionsPerProduct.getOrDefault(ids[0], 0L);
            metrics.add(new MarketingModels.CrossSellMetric(ids[0], name(names.get(ids[0]), ids[0]),
                    ids[1], name(names.get(ids[1]), ids[1]), common, sourceSessions,
                    sourceSessions == 0 ? null : round((double) common / sourceSessions, 4)));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.CrossSellMetric::commonSessions).reversed()
                .thenComparing(MarketingModels.CrossSellMetric::sourceProductId));
        return metrics.size() > 20 ? metrics.subList(0, 20) : metrics;
    }

    // ------------------------------------------------------------------ besoins non couverts (§12)

    private static List<MarketingModels.UnmetNeedMetric> unmetNeeds(List<MarketingModels.MarketingEvent> events) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Double> confidenceSum = new HashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, String> projects = new LinkedHashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (!MarketingModels.UNMET_NEED.equals(event.eventType())) {
                continue;
            }
            String reason = blank(event.reason());
            String key = name(event.projectType(), "UNKNOWN") + "|" + name(event.reasonCategory(), "OTHER")
                    + "|" + name(reason, "");
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            if (event.confidence() != null) {
                confidenceSum.merge(key, event.confidence(), Double::sum);
            }
            reasons.putIfAbsent(key, reason);
            projects.putIfAbsent(key, blank(event.projectType()));
        }
        List<MarketingModels.UnmetNeedMetric> metrics = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            long count = entry.getValue()[0];
            Double average = confidenceSum.containsKey(entry.getKey())
                    ? round(confidenceSum.get(entry.getKey()) / count, 3) : null;
            metrics.add(new MarketingModels.UnmetNeedMetric(projects.get(entry.getKey()),
                    "".equals(parts[1]) ? null : parts[1], reasons.get(entry.getKey()), count, average));
        }
        metrics.sort(Comparator.comparingLong(MarketingModels.UnmetNeedMetric::count).reversed());
        return metrics;
    }

    // ------------------------------------------------------------------ informations manquantes (§13)

    private static List<MarketingModels.MissingInfoMetric> missingInformation(List<MarketingModels.MarketingEvent> events) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (!MarketingModels.MISSING_PRODUCT_INFORMATION.equals(event.eventType())) {
                continue;
            }
            String productId = name(event.productId(), "UNKNOWN");
            String key = productId + "|" + name(event.reasonCategory(), "OTHER");
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            names.putIfAbsent(productId, name(event.productName(), productId));
        }
        List<MarketingModels.MissingInfoMetric> metrics = new ArrayList<>();
        counts.forEach((key, counter) -> {
            String[] parts = key.split("\\|", 2);
            metrics.add(new MarketingModels.MissingInfoMetric(parts[0], names.get(parts[0]), parts[1], counter[0]));
        });
        metrics.sort(Comparator.comparingLong(MarketingModels.MissingInfoMetric::count).reversed());
        return metrics;
    }

    // ------------------------------------------------------------------ séries & tendances (§25)

    private static List<MarketingModels.DailyPoint> series(List<MarketingModels.MarketingEvent> events,
                                                           LocalDate from, LocalDate to) {
        Map<String, Set<String>> sessions = new HashMap<>();
        Map<String, Long> interests = new HashMap<>();
        Map<String, Long> high = new HashMap<>();
        Map<String, Long> subscription = new HashMap<>();
        Map<String, Long> appointment = new HashMap<>();
        for (MarketingModels.MarketingEvent event : events) {
            String day = dayOf(event);
            if (day == null) continue;
            if (event.sessionId() != null) sessions.computeIfAbsent(day, d -> new LinkedHashSet<>()).add(event.sessionId());
            switch (value(event.eventType())) {
                case MarketingModels.PRODUCT_INTEREST -> {
                    interests.merge(day, 1L, Long::sum);
                    if ("HIGH".equalsIgnoreCase(value(event.interestLevel()))) high.merge(day, 1L, Long::sum);
                }
                case MarketingModels.SUBSCRIPTION_INTEREST -> subscription.merge(day, 1L, Long::sum);
                case MarketingModels.APPOINTMENT_INTEREST -> appointment.merge(day, 1L, Long::sum);
                default -> { }
            }
        }
        List<MarketingModels.DailyPoint> points = new ArrayList<>();
        LocalDate cursor = from;
        long guard = 0;
        while (!cursor.isAfter(to) && guard++ < 400) {
            String day = cursor.toString();
            points.add(new MarketingModels.DailyPoint(day,
                    sessions.getOrDefault(day, Set.of()).size(),
                    interests.getOrDefault(day, 0L),
                    high.getOrDefault(day, 0L),
                    subscription.getOrDefault(day, 0L),
                    appointment.getOrDefault(day, 0L)));
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private List<MarketingModels.TrendMetric> trends(List<MarketingModels.MarketingEvent> events,
                                                     List<MarketingModels.MarketingEvent> previousEvents,
                                                     Map<String, ProductAcc> products) {
        List<MarketingModels.TrendMetric> trends = new ArrayList<>();
        long currentConversations = distinctSessions(events);
        long previousConversations = distinctSessions(previousEvents);
        trends.add(new MarketingModels.TrendMetric("OVERVIEW", "conversations", "Conversations",
                currentConversations, previousConversations, evolution(currentConversations, previousConversations)));

        Map<String, Long> previous = interestedSessionsByProduct(previousEvents);
        for (Map.Entry<String, Long> entry : previous.entrySet()) {
            if (!products.containsKey(entry.getKey())) {
                trends.add(new MarketingModels.TrendMetric("PRODUCT", entry.getKey(), entry.getKey(),
                        0, entry.getValue(), evolution(0, entry.getValue())));
            }
        }
        for (ProductAcc acc : products.values()) {
            long current = acc.interestedSessions.size();
            Long previousValue = previous.get(acc.id);
            if (current == 0 && previousValue == null) {
                continue;
            }
            trends.add(new MarketingModels.TrendMetric("PRODUCT", acc.id, acc.name,
                    current, previousValue == null ? 0 : previousValue, evolution(current, previousValue)));
        }
        trends.sort((a, b) -> {
            double ea = a.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(a.evolutionPercent());
            double eb = b.evolutionPercent() == null ? -Double.MAX_VALUE : Math.abs(b.evolutionPercent());
            return Double.compare(eb, ea);
        });
        return trends;
    }

    // ------------------------------------------------------------------ utilitaires

    private static void addSession(Set<String> sessions, MarketingModels.MarketingEvent event) {
        if (event.sessionId() != null) {
            sessions.add(event.sessionId());
        }
    }

    private static long distinctSessions(List<MarketingModels.MarketingEvent> events) {
        Set<String> sessions = new LinkedHashSet<>();
        events.forEach(event -> {
            if (event.sessionId() != null) sessions.add(event.sessionId());
        });
        return sessions.size();
    }

    private static String dayOf(MarketingModels.MarketingEvent event) {
        String reference = event.timestamp() != null && !event.timestamp().isBlank()
                ? event.timestamp() : event.createdAt();
        return reference == null || reference.length() < 10 ? null : reference.substring(0, 10);
    }

    /** Variation en % ; {@code null} si la base est nulle ou absente (division par zéro évitée). */
    private static Double evolution(long current, Long previous) {
        if (previous == null || previous == 0L) {
            return null;
        }
        return round(((double) current - previous) / previous * 100, 2);
    }

    private static Double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String name(String value, String fallback) {
        String cleaned = blank(value);
        return cleaned == null ? (fallback == null ? "" : fallback) : cleaned;
    }
}
