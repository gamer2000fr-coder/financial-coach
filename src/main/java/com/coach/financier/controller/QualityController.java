package com.coach.financier.controller;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.QualityModels;
import com.coach.financier.service.QualityAnalyticsService;
import com.coach.financier.service.QualityBatchService;
import com.coach.financier.service.QualityDemoDataService;
import com.coach.financier.service.QualityFeedbackService;
import com.coach.financier.service.QualityReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API du module <b>Qualité &amp; Satisfaction du Coach IA</b>.
 * <p>
 * Le frontend ne lit JAMAIS les fichiers : tout passe par ces endpoints (stockage fichier côté
 * serveur). Les indicateurs de SATISFACTION et de CONFORMITÉ restent séparés dans les réponses.
 */
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityAnalyticsService analyticsService;
    private final QualityReportService reportService;
    private final QualityBatchService batchService;
    private final QualityDemoDataService demoDataService;
    private final QualityProperties properties;

    public QualityController(QualityAnalyticsService analyticsService,
                             QualityReportService reportService,
                             QualityBatchService batchService,
                             QualityDemoDataService demoDataService,
                             QualityProperties properties) {
        this.analyticsService = analyticsService;
        this.reportService = reportService;
        this.batchService = batchService;
        this.demoDataService = demoDataService;
        this.properties = properties;
    }

    /** État du module (config + volumes disponibles). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("demoMode", properties.isDemoMode());
        out.put("feedbackDir", properties.feedbackDir().toString());
        out.put("availableDays", analyticsService.availableDays());
        out.put("reportDates", reportService.availableDates());
        out.put("implementedChecks", properties.enabledChecks());
        out.put("notImplementedChecks", properties.notImplementedChecks());
        out.put("commentMaxLength", properties.commentMaxLength());
        out.put("feedbackReasons", QualityModels.reasonLabels());
        return out;
    }

    /** Agrégats complets : satisfaction + conformité + croisement + séries + tendances. */
    @GetMapping("/overview")
    public QualityModels.QualityAggregates overview(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String checkType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String reason) {
        LocalDate[] range = resolve(period, from, to);
        return analyticsService.compute(range[0], range[1],
                new QualityModels.QualityFilter(checkType, severity, rating, reason));
    }

    /** Distribution des notes (1 à 5) de la période. */
    @GetMapping("/ratings")
    public Map<String, Object> ratings(@RequestParam(required = false) String period,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        QualityModels.QualityAggregates aggregates = analyticsService.compute(range[0], range[1],
                QualityModels.QualityFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dateFrom", aggregates.dateFrom());
        out.put("dateTo", aggregates.dateTo());
        out.put("satisfaction", aggregates.satisfaction());
        out.put("ratingDistribution", aggregates.ratingDistribution());
        out.put("series", aggregates.series());
        return out;
    }

    /** Contrôles de conformité réellement exécutés (jamais de faux « 0 » pour un contrôle absent). */
    @GetMapping("/issues")
    public Map<String, Object> issues(@RequestParam(required = false) String period,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to,
                                      @RequestParam(required = false) String checkType,
                                      @RequestParam(required = false) String severity) {
        LocalDate[] range = resolve(period, from, to);
        QualityModels.QualityAggregates aggregates = analyticsService.compute(range[0], range[1],
                new QualityModels.QualityFilter(checkType, severity, null, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dateFrom", aggregates.dateFrom());
        out.put("dateTo", aggregates.dateTo());
        out.put("conformity", aggregates.conformity());
        out.put("qualityChecks", aggregates.qualityChecks());
        out.put("implementedChecks", aggregates.implementedChecks());
        out.put("notImplementedChecks", aggregates.notImplementedChecks());
        out.put("details", analyticsService.checks(range[0], range[1]).stream()
                .filter(QualityModels.QualityCheck::detected).toList());
        return out;
    }

    /** Motifs d'insatisfaction choisis par les clients. */
    @GetMapping("/feedback-categories")
    public Map<String, Object> feedbackCategories(@RequestParam(required = false) String period,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        QualityModels.QualityAggregates aggregates = analyticsService.compute(range[0], range[1],
                QualityModels.QualityFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dateFrom", aggregates.dateFrom());
        out.put("dateTo", aggregates.dateTo());
        out.put("feedbackCategories", aggregates.feedbackCategories());
        out.put("commentThemes", aggregates.commentThemes());
        out.put("labels", QualityModels.reasonLabels());
        return out;
    }

    /** Tendances (période courante vs période précédente de même longueur). */
    @GetMapping("/trends")
    public Map<String, Object> trends(@RequestParam(required = false) String period,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        QualityModels.QualityAggregates aggregates = analyticsService.compute(range[0], range[1],
                QualityModels.QualityFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dateFrom", aggregates.dateFrom());
        out.put("dateTo", aggregates.dateTo());
        out.put("trends", aggregates.trends());
        out.put("series", aggregates.series());
        return out;
    }

    /** Rapport IA de la date demandée (204 si aucun rapport n'existe encore). */
    @GetMapping("/report")
    public ResponseEntity<QualityModels.QualityReport> report(@RequestParam(required = false) String date,
                                                              @RequestParam(required = false) String period,
                                                              @RequestParam(required = false) String from,
                                                              @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        String target = date == null || date.isBlank() ? range[1].toString() : date;
        return reportService.findOrDefault(target)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Régénère le rapport IA d'une journée (fournisseur par défaut si non précisé). */
    @PostMapping("/report/regenerate")
    public QualityModels.QualityReport regenerate(@RequestParam(required = false) String date,
                                                  @RequestParam(required = false) AIModels.AIProvider provider) {
        LocalDate target = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return reportService.generate(target, provider);
    }

    /** Batch quotidien : agrégats consolidés + rapport IA (idempotent). */
    @PostMapping("/batch")
    public QualityBatchService.BatchResult batch(@RequestParam(required = false) String date,
                                                 @RequestParam(required = false) AIModels.AIProvider provider) {
        LocalDate target = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return batchService.run(target, provider);
    }

    /** Jeu de démonstration (sources marquées DEMO, rejouable sans doublon). */
    @PostMapping("/demo-data")
    public QualityDemoDataService.DemoResult demoData(@RequestParam(defaultValue = "14") int days,
                                                      @RequestParam(defaultValue = "5") int reviewsPerDay) {
        return demoDataService.generate(days, reviewsPerDay);
    }

    /** Export CSV des statistiques AGRÉGÉES (jamais des commentaires bruts — §46). */
    @GetMapping(value = "/export/satisfaction.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        QualityModels.QualityAggregates aggregates = analyticsService.compute(range[0], range[1],
                QualityModels.QualityFilter.none());
        StringBuilder csv = new StringBuilder();
        csv.append("indicateur;valeur\n");
        csv.append("periode;").append(aggregates.dateFrom()).append(" -> ").append(aggregates.dateTo()).append('\n');
        csv.append("conversations_terminees;").append(aggregates.satisfaction().conversationsClosed()).append('\n');
        csv.append("avis_recus;").append(aggregates.satisfaction().feedbackCount()).append('\n');
        csv.append("taux_participation;").append(value(aggregates.satisfaction().participationRate())).append('\n');
        csv.append("note_moyenne;").append(value(aggregates.satisfaction().averageRating())).append('\n');
        csv.append("avis_positifs;").append(aggregates.satisfaction().positiveCount()).append('\n');
        csv.append("avis_negatifs;").append(aggregates.satisfaction().negativeCount()).append('\n');
        csv.append("controles_executes;").append(aggregates.conformity().checksRun()).append('\n');
        csv.append("anomalies;").append(aggregates.conformity().anomalies()).append('\n');
        csv.append("anomalies_high;").append(aggregates.conformity().highAnomalies()).append('\n');
        csv.append('\n').append("note;nombre;part\n");
        for (QualityModels.RatingBucket bucket : aggregates.ratingDistribution()) {
            csv.append(bucket.rating()).append(';').append(bucket.count()).append(';')
                    .append(bucket.share()).append('\n');
        }
        csv.append('\n').append("motif;libelle;nombre;part\n");
        for (QualityModels.ReasonMetric reason : aggregates.feedbackCategories()) {
            csv.append(reason.reason()).append(';').append(reason.label()).append(';')
                    .append(reason.count()).append(';').append(reason.share()).append('\n');
        }
        csv.append('\n').append("controle;libelle;severite;executes;anomalies\n");
        for (QualityModels.CheckMetric check : aggregates.qualityChecks()) {
            csv.append(check.checkType()).append(';').append(check.label()).append(';')
                    .append(check.severity()).append(';').append(check.checksRun()).append(';')
                    .append(check.detected()).append('\n');
        }
        byte[] payload = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"qualite_satisfaction.csv\"")
                .header("Content-Type", "text/csv;charset=UTF-8")
                .body(payload);
    }

    /** Résolution de période : {@code today|yesterday|7d|30d|custom} (défaut 7 jours). */
    private static LocalDate[] resolve(String period, String from, String to) {
        LocalDate today = LocalDate.now();
        String value = period == null ? "" : period.trim().toLowerCase();
        if ("custom".equals(value) && from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return new LocalDate[]{LocalDate.parse(from), LocalDate.parse(to)};
        }
        return switch (value) {
            case "today" -> new LocalDate[]{today, today};
            case "yesterday" -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case "30d" -> new LocalDate[]{today.minusDays(29), today};
            case "7d" -> new LocalDate[]{today.minusDays(6), today};
            default -> from != null && !from.isBlank() && to != null && !to.isBlank()
                    ? new LocalDate[]{LocalDate.parse(from), LocalDate.parse(to)}
                    : new LocalDate[]{today.minusDays(6), today};
        };
    }

    private static String value(Double number) {
        return number == null ? "" : String.valueOf(number);
    }
}
