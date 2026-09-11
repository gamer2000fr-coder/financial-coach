package com.coach.financier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilitaires de STOCKAGE FICHIER (JSONL) partagés par les modules Marketing et Qualité
 * (aucune base de données dans le POC).
 * <p>
 * Un fichier par jour : {@code <dir>/<prefix>YYYY-MM-DD.jsonl}, une ligne JSON par enregistrement,
 * UTF-8, répertoires créés automatiquement, tolérance aux fichiers absents et aux lignes invalides
 * (ignorées et COMPTÉES, jamais silencieusement perdues).
 */
final class JsonlFiles {

    private static final Logger log = LoggerFactory.getLogger(JsonlFiles.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SUFFIX = ".jsonl";

    private JsonlFiles() {
    }

    /** Résultat de lecture : valeurs valides + lignes invalides rencontrées. */
    record ReadResult<T>(List<T> values, long invalidLines) {
        static <T> ReadResult<T> empty() {
            return new ReadResult<>(List.of(), 0);
        }
    }

    static Path fileFor(Path dir, String prefix, LocalDate day) {
        return dir.resolve(prefix + DAY.format(day) + SUFFIX);
    }

    static List<Path> files(Path dir, String prefix) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*" + SUFFIX)) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException e) {
            log.warn("Liste des fichiers {} impossible dans {} : {}", prefix, dir, e.getMessage());
        }
        files.sort(Path::compareTo);
        return files;
    }

    static LocalDate dayOf(Path file, String prefix) {
        String name = file.getFileName().toString();
        if (!name.startsWith(prefix) || !name.endsWith(SUFFIX)) {
            return null;
        }
        String raw = name.substring(prefix.length(), name.length() - SUFFIX.length());
        try {
            return LocalDate.parse(raw, DAY);
        } catch (Exception e) {
            return null; // nom inattendu : fichier ignoré
        }
    }

    /** Jours disponibles (fichiers existants), ordre croissant. */
    static List<LocalDate> days(Path dir, String prefix) {
        List<LocalDate> days = new ArrayList<>();
        for (Path file : files(dir, prefix)) {
            LocalDate day = dayOf(file, prefix);
            if (day != null) {
                days.add(day);
            }
        }
        days.sort(LocalDate::compareTo);
        return days;
    }

    /** Lit une période (bornes incluses, {@code null} = pas de borne). */
    static <T> ReadResult<T> read(Path dir, String prefix, LocalDate from, LocalDate to,
                                  Class<T> type, ObjectMapper objectMapper) {
        List<T> values = new ArrayList<>();
        long invalidLines = 0;
        for (Path file : files(dir, prefix)) {
            LocalDate day = dayOf(file, prefix);
            if (day == null || (from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        values.add(objectMapper.readValue(trimmed, type));
                    } catch (Exception e) {
                        invalidLines++;
                    }
                }
            } catch (IOException e) {
                log.warn("Lecture impossible dans {} : {}", file, e.getMessage());
            }
        }
        return new ReadResult<>(values, invalidLines);
    }

    /** Ajoute des lignes à un fichier (créé si nécessaire), sous verrou. */
    static void append(Path file, List<String> lines, Object lock) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            StringBuilder block = new StringBuilder();
            for (String line : lines) {
                block.append(line).append('\n');
            }
            synchronized (lock) {
                Files.writeString(file, block.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("Écriture impossible dans {} : {}", file, e.getMessage());
            throw new IllegalStateException("Écriture JSONL impossible : " + file, e);
        }
    }
}
