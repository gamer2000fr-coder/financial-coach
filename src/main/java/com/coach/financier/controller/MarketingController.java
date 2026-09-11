package com.coach.financier.controller;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.service.MarketingAnalyticsService;
import com.coach.financier.service.MarketingBatchService;
import com.coach.financier.service.MarketingDemoDataService;
import com.coach.financier.service.MarketingEventStore;
import com.coach.financier.service.MarketingReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * API MARKETING INTELLIGENCE (§29) : lit les événements et agrégats FICHIERS (aucune base de
 * données) et expose les données consolidées à la page {@code #/marketing}.
 * <p>
 * Les filtres (§30) sont appliqués côté backend avant agrégation ; le frontend ne lit jamais le disque.
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {
    private final MarketingAnalyticsService analyticsService;
    private final MarketingReportService reportService;
    private final MarketingBatchService batchService;
    private final MarketingEventStore eventStore;
    private final MarketingDemoDataService demoDataService;
    private final MarketingProperties properties;

    public MarketingController(MarketingAnalyticsService analyticsService,
                               MarketingReportService reportService,
                               MarketingBatchService batchService,
                               MarketingEventStore eventStore,
                               MarketingDemoDataService demoDataService,
                               MarketingProperties properties) {
        this.analyticsService = analyticsService;
        this.reportService = reportService;
        this.batchService = batchService;
        this.eventStore = eventStore;
        this.demoDataService = demoDataService;
        this.properties = properties;
    }

    /** État du module : jours disponibles, mode démo, tranches de montant, seuils. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("demoMode", properties.isDemoMode());
        out.put("eventsDir", eventStore.eventsDir().toString());
        out.put("availableDays", analyticsService.availableDays().stream().map(LocalDate::toString).toList());
        out.put("reportDates", reportService.availableDates());
        out.put("amountBounds", properties.amountBounds());
        // Libellés métier (source unique) : la page ne doit jamais afficher un code brut
        // comme « REAL_ESTATE · NO_SUITABLE_PRODUCT ».
        out.put("projectTypeLabels", MarketingModels.projectTypeLabels());
        out.put("productFamilyLabels", MarketingModels.productFamilyLabels());
        out.put("rejectionReasonLabels", MarketingModels.rejectionReasonLabels());
        out.put("interestReasonLabels", MarketingModels.interestReasonLabels());
        out.put("unmetReasonLabels", MarketingModels.unmetReasonLabels());
        out.put("missingInfoReasonLabels", MarketingModels.missingInfoReasonLabels());
        return out;
    }

    /** Vue complète (KPI + produits + projets + refus + cross-sell + besoins + tendances). */
    @GetMapping("/overview")
    public MarketingModels.MarketingAggregates overview(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String productFamily,
            @RequestParam(required = false) String projectType,
            @RequestParam(required = false) String interestLevel,
            @RequestParam(required = false) String eventType) {
        return aggregation(period, from, to, productId, productFamily, projectType, interestLevel, eventType);
    }

    @GetMapping("/products")
    public List<MarketingModels.ProductMetric> products(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productFamily,
            @RequestParam(required = false) String projectType) {
        return aggregation(period, from, to, null, productFamily, projectType, null, null).products();
    }

    /** Drill-down produit (§43) : métriques + refus détaillés + associations + projets associés. */
    @GetMapping("/products/{productId}")
    public Map<String, Object> product(@PathVariable String productId,
                                       @RequestParam(required = false) String period,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        MarketingModels.MarketingAggregates aggregates = analyticsService.compute(range[0], range[1],
                new MarketingModels.MarketingFilter(productId, null, null, null, null));
        Optional<MarketingModels.ProductMetric> metric = aggregates.products().stream()
                .filter(product -> productId.equalsIgnoreCase(product.productId()))
                .findFirst();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        out.put("metric", metric.orElse(null));
        out.put("rejections", analyticsService.rejectionsByProduct(range[0], range[1], productId));
        out.put("crossSell", aggregates.crossSell().stream()
                .filter(pair -> productId.equalsIgnoreCase(pair.sourceProductId()))
                .toList());
        out.put("dateFrom", aggregates.dateFrom());
        out.put("dateTo", aggregates.dateTo());
        return out;
    }

    @GetMapping("/projects")
    public List<MarketingModels.ProjectMetric> projects(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String projectType) {
        return aggregation(period, from, to, null, null, projectType, null, null).projects();
    }

    @GetMapping("/trends")
    public List<MarketingModels.TrendMetric> trends(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productId) {
        return aggregation(period, from, to, productId, null, null, null, null).trends();
    }

    @GetMapping("/rejections")
    public List<MarketingModels.RejectionMetric> rejections(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productId) {
        LocalDate[] range = resolve(period, from, to);
        return productId == null || productId.isBlank()
                ? aggregation(period, from, to, null, null, null, null, MarketingModels.PRODUCT_REJECTED).rejections()
                : analyticsService.rejectionsByProduct(range[0], range[1], productId);
    }

    @GetMapping("/cross-sell")
    public List<MarketingModels.CrossSellMetric> crossSell(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return aggregation(period, from, to, null, null, null, null, null).crossSell();
    }

    @GetMapping("/unmet-needs")
    public List<MarketingModels.UnmetNeedMetric> unmetNeeds(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return aggregation(period, from, to, null, null, null, null, null).unmetNeeds();
    }

    @GetMapping("/missing-information")
    public List<MarketingModels.MissingInfoMetric> missingInformation(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return aggregation(period, from, to, null, null, null, null, null).missingInformation();
    }

    /** Rapport IA du jour demandé, sinon le plus récent (§27). */
    @GetMapping("/reports/daily")
    public ResponseEntity<MarketingModels.MarketingReport> dailyReport(
            @RequestParam(required = false) String date) {
        return reportService.findOrDefault(date)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Régénère le rapport IA d'une date (batch + rapport) — idempotent. */
    @PostMapping("/reports/daily/regenerate")
    public MarketingBatchService.BatchResult regenerate(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) AIModels.AIProvider provider) {
        LocalDate target = parse(date, LocalDate.now());
        return batchService.run(target, provider);
    }

    /** Batch quotidien : agrégats + rapport, idempotent (§18). */
    @PostMapping("/batch")
    public MarketingBatchService.BatchResult batch(@RequestParam(required = false) String date) {
        return batchService.run(parse(date, LocalDate.now().minusDays(1)));
    }

    /** Export CSV des métriques produit (aucune donnée personnelle, §46). */
    @GetMapping(value = "/export/products.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportProducts(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productFamily,
            @RequestParam(required = false) String projectType) {
        List<MarketingModels.ProductMetric> products =
                aggregation(period, from, to, null, productFamily, projectType, null, null).products();
        StringBuilder csv = new StringBuilder();
        csv.append("produit;famille;recommandations;sessions_interessees;clients_uniques;high;medium;low;"
                + "refus;comparaisons;souscription;rdv;taux_interet;score;evolution_pct\n");
        for (MarketingModels.ProductMetric product : products) {
            csv.append(csv(product.productName())).append(';')
                    .append(csv(product.productFamily())).append(';')
                    .append(product.recommendedSessions()).append(';')
                    .append(product.interestedSessions()).append(';')
                    .append(product.uniqueInterestedCustomers()).append(';')
                    .append(product.highCount()).append(';')
                    .append(product.mediumCount()).append(';')
                    .append(product.lowCount()).append(';')
                    .append(product.rejectedCount()).append(';')
                    .append(product.comparisonCount()).append(';')
                    .append(product.subscriptionIntentCount()).append(';')
                    .append(product.appointmentRequestCount()).append(';')
                    .append(product.interestRate() == null ? "" : product.interestRate()).append(';')
                    .append(product.score()).append(';')
                    .append(product.evolutionPercent() == null ? "" : product.evolutionPercent())
                    .append('\n');
        }
        byte[] body = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8); // BOM pour Excel
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"marketing_products.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    /** Génère un jeu de données de DÉMONSTRATION clairement identifié ({@code demo=true}, §49). */
    @PostMapping("/demo-data")
    public Map<String, Object> demoData(@RequestParam(defaultValue = "7") int days,
                                        @RequestParam(defaultValue = "12") int sessionsPerDay) {
        return demoDataService.generate(Math.min(Math.max(days, 1), 60), Math.min(Math.max(sessionsPerDay, 1), 200));
    }

    // ------------------------------------------------------------------ utilitaires

    private MarketingModels.MarketingAggregates aggregation(String period, String from, String to,
                                                            String productId, String productFamily,
                                                            String projectType, String interestLevel,
                                                            String eventType) {
        LocalDate[] range = resolve(period, from, to);
        return analyticsService.compute(range[0], range[1], new MarketingModels.MarketingFilter(
                productId, productFamily, projectType, interestLevel, eventType));
    }

    private LocalDate[] resolve(String period, String from, String to) {
        LocalDate today = LocalDate.now();
        String value = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "today" -> new LocalDate[]{today, today};
            case "yesterday" -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case "30d" -> new LocalDate[]{today.minusDays(29), today};
            case "custom" -> new LocalDate[]{parse(from, today.minusDays(6)), parse(to, today)};
            default -> new LocalDate[]{today.minusDays(6), today};
        };
    }

    private static LocalDate parse(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
    }
}
