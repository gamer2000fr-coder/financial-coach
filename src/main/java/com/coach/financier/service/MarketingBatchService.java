package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.MarketingModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * BATCH QUOTIDIEN (§18/§50) : pré-calcule les agrégats d'une journée dans
 * {@code <marketing.dir>/aggregates/} puis, en option, génère le rapport IA.
 * <p>
 * IDEMPOTENT : une relance écrase les mêmes fichiers (aucun doublon, aucun cumul).
 */
@Service
public class MarketingBatchService {
    private static final Logger log = LoggerFactory.getLogger(MarketingBatchService.class);

    private final MarketingAnalyticsService analyticsService;
    private final MarketingProperties properties;
    private final ObjectMapper objectMapper;
    private final MarketingReportService reportService;

    public MarketingBatchService(MarketingAnalyticsService analyticsService,
                                 MarketingProperties properties,
                                 ObjectMapper objectMapper,
                                 MarketingReportService reportService) {
        this.analyticsService = analyticsService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.reportService = reportService;
    }

    /** Résultat du batch pour une journée. */
    public record BatchResult(String date, List<String> aggregateFiles, boolean reportGenerated,
                              String reportFile, MarketingModels.MarketingOverview overview) {}

    /** Exécute le batch d'une journée (agrégats + rapport IA avec le fournisseur par défaut). */
    public BatchResult run(LocalDate date) {
        return run(date, null);
    }

    /** Exécute le batch d'une journée ; {@code provider} {@code null} = fournisseur par défaut. */
    public BatchResult run(LocalDate date, AIModels.AIProvider provider) {
        MarketingModels.MarketingAggregates aggregates =
                analyticsService.compute(date, date, MarketingModels.MarketingFilter.none());
        List<String> files = new ArrayList<>();
        files.add(write("marketing_overview", date, aggregates.overview()));
        files.add(write("marketing_product_metrics", date, aggregates.products()));
        files.add(write("marketing_project_metrics", date, aggregates.projects()));
        files.add(write("marketing_rejections", date, aggregates.rejections()));
        files.add(write("marketing_cross_sell", date, aggregates.crossSell()));
        files.add(write("marketing_unmet_needs", date, aggregates.unmetNeeds()));
        files.add(write("marketing_missing_info", date, aggregates.missingInformation()));
        files.add(write("marketing_series", date, aggregates.series()));

        reportService.generate(date, provider);
        return new BatchResult(date.toString(), List.copyOf(files), true,
                "marketing_report_" + date + ".json", aggregates.overview());
    }

    /** Agrégat quotidien déjà calculé (relecture des fichiers du batch), sinon vide. */
    public List<String> availableDates() {
        Path dir = properties.aggregatesDir();
        List<String> dates = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return dates;
        }
        try (var stream = Files.newDirectoryStream(dir, "marketing_overview_*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                dates.add(name.substring("marketing_overview_".length(), name.length() - ".json".length()));
            }
        } catch (IOException e) {
            log.warn("Liste des agrégats marketing impossible : {}", e.getMessage());
        }
        dates.sort(String::compareTo);
        return dates;
    }

    private String write(String prefix, LocalDate date, Object payload) {
        String filename = prefix + "_" + date + ".json";
        Path file = properties.aggregatesDir().resolve(filename);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
        } catch (IOException e) {
            log.warn("Écriture de l'agrégat {} impossible : {}", filename, e.getMessage());
        }
        return filename;
    }

    /** Sérialisation JSON d'un agrégat (export / debug). */
    public String toJson(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
