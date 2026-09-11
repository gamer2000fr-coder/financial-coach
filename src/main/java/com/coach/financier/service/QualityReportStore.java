package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
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
import java.util.Optional;

/**
 * Stockage FICHIER des rapports IA de qualité :
 * {@code <quality.dir>/reports/coach_quality_report_YYYY-MM-DD.json} (JSON structuré, exploitable
 * par le frontend — §32).
 */
@Service
public class QualityReportStore {
    private static final Logger log = LoggerFactory.getLogger(QualityReportStore.class);
    private static final String PREFIX = "coach_quality_report_";
    private static final String SUFFIX = ".json";

    private final QualityProperties properties;
    private final ObjectMapper objectMapper;

    public QualityReportStore(QualityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Écrit (ou réécrit — idempotent) le rapport d'une date. */
    public QualityModels.QualityReport save(QualityModels.QualityReport report) {
        if (report == null) {
            return null;
        }
        String date = report.reportDate() == null || report.reportDate().isBlank()
                ? LocalDate.now().toString() : report.reportDate();
        Path file = fileFor(date);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        } catch (IOException e) {
            log.warn("Rapport qualité non écrit dans {} : {}", file, e.getMessage());
        }
        return report;
    }

    public Optional<QualityModels.QualityReport> find(String date) {
        if (date == null || date.isBlank()) {
            return Optional.empty();
        }
        Path file = fileFor(date);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), QualityModels.QualityReport.class));
        } catch (IOException e) {
            log.warn("Rapport qualité illisible dans {} : {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Rapport le plus récent disponible. */
    public Optional<QualityModels.QualityReport> latest() {
        List<String> dates = availableDates();
        if (dates.isEmpty()) {
            return Optional.empty();
        }
        return find(dates.get(dates.size() - 1));
    }

    public List<String> availableDates() {
        List<String> dates = new ArrayList<>();
        Path dir = properties.reportsDir();
        if (!Files.isDirectory(dir)) {
            return dates;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                dates.add(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
            }
        } catch (IOException e) {
            log.warn("Liste des rapports qualité impossible : {}", e.getMessage());
        }
        dates.sort(String::compareTo);
        return dates;
    }

    public Path reportsDir() {
        return properties.reportsDir();
    }

    private Path fileFor(String date) {
        return properties.reportsDir().resolve(PREFIX + date + SUFFIX);
    }
}
