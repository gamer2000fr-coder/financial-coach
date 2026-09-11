package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.QualityModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * BATCH QUOTIDIEN QUALITÉ (§23) : lit les feedbacks et les contrôles du jour, écrit
 * <b>l'agrégat consolidé</b> {@code aggregates/coach_quality_daily_YYYY-MM-DD.json} (format demandé
 * par la spécification) ainsi que des fichiers par section (mêmes conventions que le module
 * Marketing), puis génère le rapport IA.
 * <p>
 * IDEMPOTENT : une relance écrase les mêmes fichiers — aucun doublon, aucun cumul.
 */
@Service
public class QualityBatchService {
    private static final Logger log = LoggerFactory.getLogger(QualityBatchService.class);

    private final QualityAnalyticsService analyticsService;
    private final QualityProperties properties;
    private final ObjectMapper objectMapper;
    private final QualityReportService reportService;

    public QualityBatchService(QualityAnalyticsService analyticsService,
                               QualityProperties properties,
                               ObjectMapper objectMapper,
                               QualityReportService reportService) {
        this.analyticsService = analyticsService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.reportService = reportService;
    }

    /** Résultat du batch pour une journée. */
    public record BatchResult(String date, List<String> aggregateFiles, boolean reportGenerated,
                              String reportFile, QualityModels.SatisfactionKpis satisfaction,
                              QualityModels.ConformityKpis conformity, boolean hasInvalidLines) {
    }

    /** Exécute le batch d'une journée (agrégats + rapport IA avec le fournisseur par défaut). */
    public BatchResult run(LocalDate date) {
        return run(date, null);
    }

    /** Exécute le batch d'une journée ; {@code provider} {@code null} = fournisseur par défaut. */
    public BatchResult run(LocalDate date, AIModels.AIProvider provider) {
        QualityModels.QualityAggregates aggregates =
                analyticsService.compute(date, date, QualityModels.QualityFilter.none());
        List<String> files = new ArrayList<>();
        files.add(write("coach_quality_daily", date, aggregates));
        files.add(write("coach_quality_satisfaction", date, aggregates.satisfaction()));
        files.add(write("coach_quality_conformity", date, aggregates.conformity()));
        files.add(write("coach_quality_ratings", date, aggregates.ratingDistribution()));
        files.add(write("coach_quality_feedback_categories", date, aggregates.feedbackCategories()));
        files.add(write("coach_quality_comment_themes", date, aggregates.commentThemes()));
        files.add(write("coach_quality_checks", date, aggregates.qualityChecks()));
        files.add(write("coach_quality_satisfaction_vs_compliance", date, aggregates.satisfactionVsCompliance()));
        files.add(write("coach_quality_series", date, aggregates.series()));

        reportService.generate(date, provider);
        return new BatchResult(date.toString(), List.copyOf(files), true,
                "coach_quality_report_" + date + ".json", aggregates.satisfaction(),
                aggregates.conformity(), aggregates.invalidLines() > 0);
    }

    /** Dates déjà agrégées par le batch. */
    public List<String> availableDates() {
        Path dir = properties.aggregatesDir();
        List<String> dates = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return dates;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "coach_quality_daily_*.json")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                dates.add(name.substring("coach_quality_daily_".length(), name.length() - ".json".length()));
            }
        } catch (IOException e) {
            log.warn("Liste des agrégats qualité impossible : {}", e.getMessage());
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
            log.warn("Écriture de l'agrégat qualité {} impossible : {}", filename, e.getMessage());
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
