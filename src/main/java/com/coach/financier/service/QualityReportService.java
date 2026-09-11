package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.QualityModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * AGENT <b>Analyste Qualité &amp; Satisfaction</b> ({@code agent/qualite_coach_client.txt}) :
 * l'IA reçoit UNIQUEMENT les agrégats calculés par le backend (+ commentaires anonymisés, sans
 * donnée personnelle) et rédige le rapport qualité. Elle ne calcule aucun chiffre et ne modifie
 * jamais le Coach (prompt, règles, catalogues) : elle propose, un humain décide.
 * <p>
 * Si l'IA est indisponible, un rapport « indisponible » explicite est stocké : les indicateurs
 * restent consultables sur la page Qualité, aucune analyse n'est inventée.
 */
@Service
public class QualityReportService {
    private static final Logger log = LoggerFactory.getLogger(QualityReportService.class);

    private final QualityAnalyticsService analyticsService;
    private final QualityReportStore reportStore;
    private final AIServiceFactory aiServiceFactory;

    public QualityReportService(QualityAnalyticsService analyticsService,
                                QualityReportStore reportStore,
                                AIServiceFactory aiServiceFactory) {
        this.analyticsService = analyticsService;
        this.reportStore = reportStore;
        this.aiServiceFactory = aiServiceFactory;
    }

    /** Génère (et stocke) le rapport IA d'une date à partir des agrégats de cette date. */
    public QualityModels.QualityReport generate(LocalDate date, AIModels.AIProvider provider) {
        QualityModels.QualityAggregates aggregates =
                analyticsService.compute(date, date, QualityModels.QualityFilter.none());
        AIModels.AIProvider effective = provider == null ? aiServiceFactory.defaultProvider() : provider;
        QualityModels.QualityReport report;
        try {
            report = aiServiceFactory.get(effective).analyzeQuality(aggregates, effective);
        } catch (Exception e) {
            log.warn("Rapport qualité IA indisponible pour {} : {}", date, e.getMessage());
            report = unavailableReport(date, e);
        }
        return reportStore.save(report);
    }

    public Optional<QualityModels.QualityReport> find(String date) {
        return reportStore.find(date);
    }

    /** Rapport de la date demandée, sinon le plus récent, sinon vide. */
    public Optional<QualityModels.QualityReport> findOrDefault(String date) {
        Optional<QualityModels.QualityReport> exact = reportStore.find(date);
        return exact.isPresent() ? exact : reportStore.latest();
    }

    public Optional<QualityModels.QualityReport> latest() {
        return reportStore.latest();
    }

    public List<String> availableDates() {
        return reportStore.availableDates();
    }

    private static QualityModels.QualityReport unavailableReport(LocalDate date, Exception error) {
        return new QualityModels.QualityReport(
                date.toString(),
                new QualityModels.ReportPeriod(date.toString(), date.toString()),
                new QualityModels.ExecutiveSummary("INSUFFICIENT_DATA",
                        "Analyse IA indisponible : les indicateurs calculés restent consultables."),
                new QualityModels.SatisfactionAnalysis("", List.of(), List.of()),
                new QualityModels.QualityAndCompliance("", List.of(), List.of()),
                new QualityModels.SatisfactionVsCompliance("", List.of()),
                List.of(), List.of(), List.of(), List.of(),
                "Aucune analyse IA n'a pu être produite pour cette période.",
                Instant.now().toString(), null, Boolean.FALSE, error == null ? null : error.getMessage());
    }

    /** Prompt système réellement utilisé (traçabilité / page Agents). */
    public static String promptUsed() {
        return com.coach.financier.ai.AgentFiles.qualitySystemPrompt();
    }

    /** Utilisé pour vérifier l'IA disponible sans générer de rapport. */
    AIService aiFor(AIModels.AIProvider provider) {
        return aiServiceFactory.get(provider);
    }
}
