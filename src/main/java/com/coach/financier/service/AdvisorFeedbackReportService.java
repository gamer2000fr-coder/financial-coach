package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.AIModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rapport IA du module Feedback Conseiller : stockage fichier
 * ({@code reports/advisor_feedback_report_<date>.json}), génération manuelle (§30) et agrégats
 * intermédiaires ({@code aggregates/advisor_feedback_daily_<date>.json}).
 * <p>
 * L'agent analyste ({@code agent/feedback_conseiller.txt}) ne reçoit QUE des statistiques calculées
 * et des commentaires anonymisés ; en cas d'échec, un rapport « indisponible » explicite est stocké
 * (aucune analyse n'est inventée).
 */
@Service
public class AdvisorFeedbackReportService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorFeedbackReportService.class);
    private static final String REPORT_PREFIX = "advisor_feedback_report_";
    private static final String AGGREGATE_PREFIX = "advisor_feedback_daily_";
    private static final String SUFFIX = ".json";

    private final AdvisorFeedbackAnalyticsService analyticsService;
    private final AdvisorFeedbackProperties properties;
    private final ObjectMapper objectMapper;
    private final AIServiceFactory aiServiceFactory;

    public AdvisorFeedbackReportService(AdvisorFeedbackAnalyticsService analyticsService,
                                       AdvisorFeedbackProperties properties,
                                       ObjectMapper objectMapper,
                                       AIServiceFactory aiServiceFactory) {
        this.analyticsService = analyticsService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.aiServiceFactory = aiServiceFactory;
    }

    /** Résultat d'un batch (agrégats + rapport). */
    public record BatchResult(String date, List<String> aggregateFiles, boolean reportGenerated,
                              String reportFile, AdvisorFeedbackModels.AdvisorKpis kpis) {
    }

    /** Batch quotidien : agrégats du jour puis rapport IA (idempotent). */
    public BatchResult run(LocalDate date, AIModels.AIProvider provider) {
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(date, date, AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        List<String> files = new ArrayList<>();
        files.add(write(AGGREGATE_PREFIX, date, "daily", aggregates));
        files.add(write(AGGREGATE_PREFIX, date, "kpis", aggregates.kpis()));
        files.add(write(AGGREGATE_PREFIX, date, "assessments", aggregates.assessments()));
        files.add(write(AGGREGATE_PREFIX, date, "areas", aggregates.areas()));
        files.add(write(AGGREGATE_PREFIX, date, "reasons", aggregates.reasons()));
        files.add(write(AGGREGATE_PREFIX, date, "products", aggregates.products()));
        files.add(write(AGGREGATE_PREFIX, date, "interest_corrections", aggregates.interestCorrections()));
        files.add(write(AGGREGATE_PREFIX, date, "email_quality", aggregates.emailQuality()));
        files.add(write(AGGREGATE_PREFIX, date, "series", aggregates.series()));
        AdvisorFeedbackModels.AdvisorFeedbackReport report = generate(date, date, provider);
        return new BatchResult(date.toString(), List.copyOf(files), report != null,
                REPORT_PREFIX + date + SUFFIX, aggregates.kpis());
    }

    /** Génère (et stocke) le rapport IA d'une PÉRIODE (bouton « Générer l'analyse IA », §30). */
    public AdvisorFeedbackModels.AdvisorFeedbackReport generate(LocalDate from, LocalDate to,
                                                               AIModels.AIProvider provider) {
        LocalDate start = from == null ? LocalDate.now() : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates =
                analyticsService.compute(start, end, AdvisorFeedbackModels.AdvisorFeedbackFilter.none());
        AIModels.AIProvider effective = provider == null ? aiServiceFactory.defaultProvider() : provider;
        AdvisorFeedbackModels.AdvisorFeedbackReport report;
        try {
            report = aiServiceFactory.get(effective).analyzeAdvisorFeedback(aggregates, effective);
        } catch (Exception e) {
            log.warn("Rapport Feedback Conseiller indisponible pour {} : {}", end, e.getMessage());
            report = unavailableReport(end, e);
        }
        return save(report);
    }

    public Optional<AdvisorFeedbackModels.AdvisorFeedbackReport> find(String date) {
        if (date == null || date.isBlank()) {
            return Optional.empty();
        }
        Path file = properties.reportsDir().resolve(REPORT_PREFIX + date + SUFFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(),
                    AdvisorFeedbackModels.AdvisorFeedbackReport.class));
        } catch (IOException e) {
            log.warn("Rapport Feedback Conseiller illisible dans {} : {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Rapport de la date demandée, sinon le plus récent. */
    public Optional<AdvisorFeedbackModels.AdvisorFeedbackReport> findOrDefault(String date) {
        Optional<AdvisorFeedbackModels.AdvisorFeedbackReport> exact = find(date);
        return exact.isPresent() ? exact : latest();
    }

    public Optional<AdvisorFeedbackModels.AdvisorFeedbackReport> latest() {
        List<String> dates = availableDates();
        return dates.isEmpty() ? Optional.empty() : find(dates.get(dates.size() - 1));
    }

    public List<String> availableDates() {
        List<String> dates = new ArrayList<>();
        Path dir = properties.reportsDir();
        if (!Files.isDirectory(dir)) {
            return dates;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, REPORT_PREFIX + "*" + SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                dates.add(name.substring(REPORT_PREFIX.length(), name.length() - SUFFIX.length()));
            }
        } catch (IOException e) {
            log.warn("Liste des rapports Feedback Conseiller impossible : {}", e.getMessage());
        }
        dates.sort(String::compareTo);
        return dates;
    }

    /** Prompt système réellement utilisé (traçabilité / page Agents). */
    public static String promptUsed() {
        return com.coach.financier.ai.AgentFiles.advisorFeedbackSystemPrompt();
    }

    /** Utilisé pour vérifier l'IA disponible sans générer de rapport. */
    AIService aiFor(AIModels.AIProvider provider) {
        return aiServiceFactory.get(provider);
    }

    private AdvisorFeedbackModels.AdvisorFeedbackReport save(
            AdvisorFeedbackModels.AdvisorFeedbackReport report) {
        if (report == null) {
            return null;
        }
        String date = report.reportDate() == null || report.reportDate().isBlank()
                ? LocalDate.now().toString() : report.reportDate();
        Path file = properties.reportsDir().resolve(REPORT_PREFIX + date + SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        } catch (IOException e) {
            log.warn("Rapport Feedback Conseiller non écrit dans {} : {}", file, e.getMessage());
        }
        return report;
    }

    private String write(String prefix, LocalDate date, String section, Object payload) {
        String filename = prefix + section + "_" + date + SUFFIX;
        Path file = properties.aggregatesDir().resolve(filename);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
        } catch (IOException e) {
            log.warn("Écriture de l'agrégat {} impossible : {}", filename, e.getMessage());
        }
        return filename;
    }

    private static AdvisorFeedbackModels.AdvisorFeedbackReport unavailableReport(LocalDate date, Exception error) {
        return new AdvisorFeedbackModels.AdvisorFeedbackReport(
                date.toString(),
                new AdvisorFeedbackModels.AdvisorReportPeriod(date.toString(), date.toString()),
                new AdvisorFeedbackModels.AdvisorExecutiveSummary(
                        AdvisorFeedbackModels.STATUS_INSUFFICIENT,
                        "Analyse IA indisponible : les indicateurs calculés restent consultables."),
                List.of(), List.of(), List.of(),
                new AdvisorFeedbackModels.InterestLevelAnalysis("", List.of(), List.of()),
                new AdvisorFeedbackModels.SectionAnalysis("", List.of()),
                new AdvisorFeedbackModels.SectionAnalysis("", List.of()),
                List.of(), List.of(), List.of(),
                "Aucune analyse IA n'a pu être produite pour cette période.",
                Instant.now().toString(), null, Boolean.FALSE,
                error == null ? null : error.getMessage());
    }
}
