package com.coach.financier.controller;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.AIModels;
import com.coach.financier.service.AdvisorFeedbackAnalyticsService;
import com.coach.financier.service.AdvisorFeedbackCandidatesService;
import com.coach.financier.service.AdvisorFeedbackDemoDataService;
import com.coach.financier.service.AdvisorDossierService;
import com.coach.financier.service.AdvisorFeedbackReportService;
import com.coach.financier.service.AdvisorFeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API du module <b>Feedback Conseiller</b> : saisie du feedback, dossiers candidats, indicateurs
 * agrégés, rapport IA et export CSV. Le frontend ne lit jamais les fichiers.
 * <p>
 * Ce module est INDÉPENDANT du module Qualité (satisfaction client / conformité) : le seul point de
 * jonction est le {@code sessionId}, ce qui prépare un croisement ultérieur (vue 360°).
 */
@RestController
@RequestMapping("/api/advisor-feedback")
public class AdvisorFeedbackController {

    private final AdvisorFeedbackService feedbackService;
    private final AdvisorFeedbackAnalyticsService analyticsService;
    private final AdvisorFeedbackReportService reportService;
    private final AdvisorFeedbackCandidatesService candidatesService;
    private final AdvisorFeedbackDemoDataService demoDataService;
    private final AdvisorDossierService dossierService;
    private final AdvisorFeedbackProperties properties;

    public AdvisorFeedbackController(AdvisorFeedbackService feedbackService,
                                     AdvisorFeedbackAnalyticsService analyticsService,
                                     AdvisorFeedbackReportService reportService,
                                     AdvisorFeedbackCandidatesService candidatesService,
                                     AdvisorFeedbackDemoDataService demoDataService,
                                     AdvisorDossierService dossierService,
                                     AdvisorFeedbackProperties properties) {
        this.feedbackService = feedbackService;
        this.analyticsService = analyticsService;
        this.reportService = reportService;
        this.candidatesService = candidatesService;
        this.demoDataService = demoDataService;
        this.dossierService = dossierService;
        this.properties = properties;
    }

    /** État du module (libellés, jours disponibles, seuil d'échantillon). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("demoMode", properties.isDemoMode());
        out.put("eventsDir", properties.eventsDir().toString());
        out.put("availableDays", analyticsService.availableDays());
        out.put("reportDates", reportService.availableDates());
        out.put("sufficientSampleSize", properties.sufficientSampleSize());
        out.put("assessments", AdvisorFeedbackModels.assessmentLabels());
        out.put("areas", AdvisorFeedbackModels.areaLabels());
        out.put("reasons", AdvisorFeedbackModels.reasonLabels());
        out.put("emailAssessments", AdvisorFeedbackModels.emailLabels());
        out.put("productReasons", AdvisorFeedbackModels.productReasonLabels());
        return out;
    }

    /** Saisie (ou révision) d'un feedback conseiller — jamais bloquant pour le dossier. */
    @PostMapping
    public AdvisorFeedbackService.SubmitResponse submit(
            @RequestBody(required = false) AdvisorFeedbackModels.AdvisorFeedbackRequest request) {
        return feedbackService.submit(request);
    }

    /** Feedback courant d'un dossier (permet de préremplir une révision). */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<AdvisorFeedbackModels.AdvisorFeedback> current(@PathVariable String sessionId) {
        return feedbackService.currentOf(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Dossier évaluable ciblé par le lien reçu par email (§43) : projet, résumé produit par le Coach,
     * produits d'intérêt et niveaux, suivi conseillé, email client préparé, feedback éventuel et
     * statut. Le BACKEND est seul juge de l'existence du dossier (§46) : 404 si le dossier n'existe
     * pas ou n'est plus disponible (l'IHM affiche alors « Ce dossier n'est plus disponible. »).
     */
    @GetMapping("/sessions/{sessionId}/dossier")
    public ResponseEntity<AdvisorFeedbackModels.DossierView> dossier(@PathVariable String sessionId) {
        return dossierService.view(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Dossiers proposés à l'évaluation (sessions récentes + produits détectés). */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam(defaultValue = "7") int days,
                                         @RequestParam(defaultValue = "false") boolean includeEvaluated) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", candidatesService.candidates(days, includeEvaluated));
        out.put("catalogue", candidatesService.catalogue());
        return out;
    }

    /** Agrégats complets de la période (KPI, zones, motifs, produits, email, tendances). */
    @GetMapping("/overview")
    public AdvisorFeedbackModels.AdvisorFeedbackAggregates overview(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String assessment,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String emailAssessment) {
        LocalDate[] range = resolve(period, from, to);
        return analyticsService.compute(range[0], range[1], new AdvisorFeedbackModels.AdvisorFeedbackFilter(
                assessment, area, productId, emailAssessment));
    }

    /** Zones à améliorer et motifs associés. */
    @GetMapping("/issues")
    public Map<String, Object> issues(@RequestParam(required = false) String period,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(range[0], range[1], AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("areas", aggregates.areas());
        out.put("reasons", aggregates.reasons());
        out.put("assessments", aggregates.assessments());
        return out;
    }

    /** Pertinence produit + corrections de niveau d'intérêt + produits ajoutés par les conseillers. */
    @GetMapping("/products")
    public Map<String, Object> products(@RequestParam(required = false) String period,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(range[0], range[1], AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("products", aggregates.products());
        out.put("interestCorrections", aggregates.interestCorrections());
        return out;
    }

    /** Qualité des emails préparés (READY_TO_USE / MINOR_EDITS / MAJOR_EDITS / UNUSABLE). */
    @GetMapping("/email-quality")
    public Map<String, Object> emailQuality(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(range[0], range[1], AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailQuality", aggregates.emailQuality());
        out.put("kpis", aggregates.kpis());
        return out;
    }

    /** Tendances (période courante vs période précédente de même longueur). */
    @GetMapping("/trends")
    public Map<String, Object> trends(@RequestParam(required = false) String period,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(range[0], range[1], AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trends", aggregates.trends());
        out.put("series", aggregates.series());
        return out;
    }

    /** Rapport IA (204 si aucun rapport). */
    @GetMapping("/report")
    public ResponseEntity<AdvisorFeedbackModels.AdvisorFeedbackReport> report(
            @RequestParam(required = false) String date) {
        String target = date == null || date.isBlank() ? LocalDate.now().toString() : date;
        return reportService.findOrDefault(target)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Bouton « Générer l'analyse IA » (§30) : rapport de la période sélectionnée. */
    @PostMapping("/report/generate")
    public AdvisorFeedbackModels.AdvisorFeedbackReport generate(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) AIModels.AIProvider provider) {
        LocalDate[] range = resolve(period, from, to);
        return reportService.generate(range[0], range[1], provider);
    }

    /** Batch quotidien (agrégats + rapport), idempotent. */
    @PostMapping("/batch")
    public AdvisorFeedbackReportService.BatchResult batch(@RequestParam(required = false) String date,
                                                         @RequestParam(required = false) AIModels.AIProvider provider) {
        LocalDate target = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return reportService.run(target, provider);
    }

    /** Jeu de démonstration (source=DEMO, rejouable sans doublon). */
    @PostMapping("/demo-data")
    public AdvisorFeedbackDemoDataService.DemoResult demoData(@RequestParam(defaultValue = "14") int days,
                                                             @RequestParam(defaultValue = "4") int evaluationsPerDay) {
        return demoDataService.generate(days, evaluationsPerDay);
    }

    /** Export CSV des statistiques agrégées (commentaires non exportés). */
    @GetMapping(value = "/export/summary.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String period,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        LocalDate[] range = resolve(period, from, to);
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(range[0], range[1], AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        StringBuilder csv = new StringBuilder("indicateur;valeur\n");
        csv.append("periode;").append(aggregates.dateFrom()).append(" -> ").append(aggregates.dateTo()).append('\n');
        csv.append("dossiers_evalues;").append(aggregates.kpis().sessionsEvaluated()).append('\n');
        csv.append("analyses_pertinentes;").append(rate(aggregates.kpis().relevantRate())).append('\n');
        csv.append("a_ameliorer;").append(rate(aggregates.kpis().needsImprovementRate())).append('\n');
        csv.append("incorrectes;").append(rate(aggregates.kpis().incorrectRate())).append('\n');
        csv.append("produits_evalues;").append(aggregates.kpis().productAssessments()).append('\n');
        csv.append("produits_pertinents;").append(rate(aggregates.kpis().productRelevanceRate())).append('\n');
        csv.append("emails_prets_ou_mineurs;").append(rate(aggregates.kpis().emailReadyOrMinorRate())).append('\n');
        csv.append("corrections_interet;").append(aggregates.kpis().interestCorrections()).append('\n');
        csv.append('\n').append("zone;libelle;volume\n");
        aggregates.areas().forEach(area -> csv.append(area.area()).append(';').append(area.label()).append(';')
                .append(area.count()).append('\n'));
        csv.append('\n').append("produit;evaluations;pertinent;non_pertinent;taux_pertinence\n");
        aggregates.products().forEach(product -> csv.append(product.productId()).append(';')
                .append(product.assessments()).append(';').append(product.relevant()).append(';')
                .append(product.notRelevant()).append(';').append(rate(product.relevanceRate())).append('\n'));
        csv.append('\n').append("email;volume;part\n");
        aggregates.emailQuality().forEach(email -> csv.append(email.code()).append(';')
                .append(email.count()).append(';').append(rate(email.share())).append('\n'));
        byte[] payload = ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"feedback_conseillers.csv\"")
                .header("Content-Type", "text/csv;charset=UTF-8")
                .body(payload);
    }

    private static String rate(Double value) {
        return value == null ? "" : String.valueOf(value);
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
}
