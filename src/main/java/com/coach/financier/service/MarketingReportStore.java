package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.MarketingModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Stockage FICHIER des rapport IA Marketing (§27) :
 * {@code <marketing.dir>/reports/marketing_report_YYYY-MM-DD.json}.
 */
@Service
public class MarketingReportStore {
    private static final Logger log = LoggerFactory.getLogger(MarketingReportStore.class);
    private static final String PREFIX = "marketing_report_";
    private static final String SUFFIX = ".json";

    private final MarketingProperties properties;
    private final ObjectMapper objectMapper;

    public MarketingReportStore(MarketingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Écrit (ou écrase) le rapport d'une date — opération idempotente. */
    public MarketingModels.MarketingReport save(MarketingModels.MarketingReport report) {
        if (report == null || report.reportDate() == null) {
            throw new IllegalArgumentException("Rapport marketing sans date : rien à écrire.");
        }
        Path file = fileOf(report.reportDate());
        try {
            Files.createDirectories(file.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Écriture du rapport marketing impossible : " + file, e);
        }
        return report;
    }

    public Optional<MarketingModels.MarketingReport> find(String date) {
        if (date == null || date.isBlank()) {
            return Optional.empty();
        }
        Path file = fileOf(date.trim());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    MarketingModels.MarketingReport.class));
        } catch (Exception e) {
            log.warn("Rapport marketing illisible {} : {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** Rapport le plus récent disponible, s'il existe. */
    public Optional<MarketingModels.MarketingReport> latest() {
        List<Path> files = files();
        if (files.isEmpty()) {
            return Optional.empty();
        }
        return find(stem(files.get(files.size() - 1)));
    }

    public List<String> availableDates() {
        List<String> dates = new ArrayList<>();
        files().forEach(file -> dates.add(stem(file)));
        return dates;
    }

    private List<Path> files() {
        Path dir = properties.reportsDir();
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            log.warn("Liste des rapports marketing impossible dans {} : {}", dir, e.getMessage());
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private Path fileOf(String date) {
        return properties.reportsDir().resolve(PREFIX + date + SUFFIX);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        return name.substring(PREFIX.length(), name.length() - SUFFIX.length());
    }

    /** Date « métier » par défaut : aujourd'hui si aucun rapport n'existe. */
    public LocalDate latestDateOrToday() {
        return latest().map(MarketingModels.MarketingReport::reportDate)
                .map(LocalDate::parse)
                .orElseGet(LocalDate::now);
    }
}
