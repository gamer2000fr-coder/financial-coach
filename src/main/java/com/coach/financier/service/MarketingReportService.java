package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.MarketingModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * AGENT ANALYSTE MARKETING (§26/§27) : envoie à l'IA UNIQUEMENT les statistiques agrégées
 * (jamais les conversations brutes) et stocke le rapport JSON produit.
 * <p>
 * Si l'IA est indisponible, un rapport « vide mais explicite » est écrit avec l'erreur :
 * les chiffres restent consultables sur la page, aucune analyse n'est inventée.
 */
@Service
public class MarketingReportService {
    private static final Logger log = LoggerFactory.getLogger(MarketingReportService.class);

    private final MarketingAnalyticsService analyticsService;
    private final MarketingReportStore reportStore;
    private final AIServiceFactory aiServiceFactory;

    public MarketingReportService(MarketingAnalyticsService analyticsService,
                                  MarketingReportStore reportStore,
                                  AIServiceFactory aiServiceFactory) {
        this.analyticsService = analyticsService;
        this.reportStore = reportStore;
        this.aiServiceFactory = aiServiceFactory;
    }

    /** Génère (et stocke) le rapport IA d'une date à partir des agrégats de cette date. */
    public MarketingModels.MarketingReport generate(LocalDate date, AIModels.AIProvider provider) {
        MarketingModels.MarketingAggregates aggregates =
                analyticsService.compute(date, date, MarketingModels.MarketingFilter.none());
        AIModels.AIProvider effective = provider == null ? aiServiceFactory.defaultProvider() : provider;
        MarketingModels.MarketingReport report;
        try {
            report = aiServiceFactory.get(effective).analyzeMarketing(aggregates, effective);
        } catch (Exception e) {
            log.warn("Rapport Marketing IA indisponible pour {} : {}", date, e.getMessage());
            report = unavailableReport(date, e);
        }
        return reportStore.save(report);
    }

    public Optional<MarketingModels.MarketingReport> find(String date) {
        return reportStore.find(date);
    }

    /** Rapport du jour demandé, sinon le plus récent, sinon vide. */
    public Optional<MarketingModels.MarketingReport> findOrDefault(String date) {
        Optional<MarketingModels.MarketingReport> exact = reportStore.find(date);
        return exact.isPresent() ? exact : reportStore.latest();
    }

    public Optional<MarketingModels.MarketingReport> latest() {
        return reportStore.latest();
    }

    public List<String> availableDates() {
        return reportStore.availableDates();
    }

    private static MarketingModels.MarketingReport unavailableReport(LocalDate date, Exception error) {
        return new MarketingModels.MarketingReport(
                date.toString(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                "Analyse IA indisponible sur la période : les statistiques restent consultables.",
                Instant.now().toString(), null, Boolean.FALSE, error.getMessage());
    }

    /** Prompt système réellement utilisé (traçabilité / page Agents). */
    public static String promptUsed() {
        return com.coach.financier.ai.AgentFiles.marketingSystemPrompt();
    }

    /** Utilisé pour vérifier l'IA disponible sans générer de rapport. */
    AIService aiFor(AIModels.AIProvider provider) {
        return aiServiceFactory.get(provider);
    }
}
