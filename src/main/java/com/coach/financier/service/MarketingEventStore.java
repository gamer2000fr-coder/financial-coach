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
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage FICHIER des événements Marketing (aucune base de données, §4/§14/§15).
 * <p>
 * Un fichier JSONL par jour : {@code <marketing.dir>/events/marketing_events_YYYY-MM-DD.jsonl}
 * — un événement par ligne, UTF-8, répertoires créés automatiquement.
 * <p>
 * Fiabilité : écriture sous verrou (append d'une ligne), création des répertoires, DÉDUPLICATION
 * par {@code eventId} (les identifiants déjà présents sont chargés au premier usage puis suivis en
 * mémoire), tolérance aux lignes invalides (ignorées et comptées) et aux fichiers absents.
 */
@Service
public class MarketingEventStore {
    private static final Logger log = LoggerFactory.getLogger(MarketingEventStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String PREFIX = "marketing_events_";
    private static final String SUFFIX = ".jsonl";

    private final MarketingProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> knownEventIds = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean idsLoaded = false;

    public MarketingEventStore(MarketingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Ajoute des événements (dédupliqués par {@code eventId}) et retourne ceux réellement écrits. */
    public List<MarketingModels.MarketingEvent> append(List<MarketingModels.MarketingEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        ensureIdsLoaded();
        Map<LocalDate, List<MarketingModels.MarketingEvent>> byDay = new LinkedHashMap<>();
        List<MarketingModels.MarketingEvent> written = new ArrayList<>();
        for (MarketingModels.MarketingEvent event : events) {
            if (event == null || event.eventId() == null || event.eventId().isBlank()) {
                continue;
            }
            if (!knownEventIds.add(event.eventId())) {
                continue; // déjà écrit (relance) : pas de doublon (§14)
            }
            byDay.computeIfAbsent(dayOf(event), d -> new ArrayList<>()).add(event);
            written.add(event);
        }
        for (Map.Entry<LocalDate, List<MarketingModels.MarketingEvent>> entry : byDay.entrySet()) {
            Path file = eventFile(entry.getKey());
            try {
                Files.createDirectories(file.getParent());
                StringBuilder block = new StringBuilder();
                for (MarketingModels.MarketingEvent event : entry.getValue()) {
                    block.append(objectMapper.writeValueAsString(event)).append('\n');
                }
                synchronized (writeLock) {
                    Files.writeString(file, block.toString(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                log.warn("Écriture des événements marketing impossible dans {} : {}", file, e.getMessage());
                entry.getValue().forEach(event -> knownEventIds.remove(event.eventId()));
                written.removeAll(entry.getValue());
            }
        }
        return List.copyOf(written);
    }

    /** Événements d'une période (bornes incluses) + compteur de lignes invalides ignorées. */
    public ReadResult read(LocalDate from, LocalDate to) {
        List<MarketingModels.MarketingEvent> events = new ArrayList<>();
        long invalidLines = 0;
        for (Path file : eventFiles()) {
            LocalDate day = dayOfFile(file);
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
                        events.add(objectMapper.readValue(trimmed, MarketingModels.MarketingEvent.class));
                    } catch (Exception e) {
                        invalidLines++; // ligne corrompue : on l'ignore sans casser l'analyse (§51)
                    }
                }
            } catch (IOException e) {
                log.warn("Lecture des événements marketing impossible dans {} : {}", file, e.getMessage());
            }
        }
        return new ReadResult(events, invalidLines);
    }

    /** Résultat de lecture : événements valides + lignes invalides rencontrées. */
    public record ReadResult(List<MarketingModels.MarketingEvent> events, long invalidLines) {}

    /** Jours pour lesquels un fichier d'événements existe (ordre croissant). */
    public List<LocalDate> availableDays() {
        List<LocalDate> days = new ArrayList<>();
        for (Path file : eventFiles()) {
            LocalDate day = dayOfFile(file);
            if (day != null) {
                days.add(day);
            }
        }
        days.sort(LocalDate::compareTo);
        return days;
    }

    public Path eventsDir() {
        return properties.eventsDir();
    }

    private void ensureIdsLoaded() {
        if (idsLoaded) {
            return;
        }
        synchronized (writeLock) {
            if (idsLoaded) {
                return;
            }
            ReadResult all = read(null, null);
            all.events().forEach(event -> {
                if (event.eventId() != null) {
                    knownEventIds.add(event.eventId());
                }
            });
            idsLoaded = true;
        }
    }

    private List<Path> eventFiles() {
        Path dir = properties.eventsDir();
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            log.warn("Liste des fichiers d'événements marketing impossible dans {} : {}", dir, e.getMessage());
        }
        files.sort(Path::compareTo);
        return files;
    }

    private Path eventFile(LocalDate day) {
        return properties.eventsDir().resolve(PREFIX + day.format(DAY) + SUFFIX);
    }

    private static LocalDate dayOf(MarketingModels.MarketingEvent event) {
        String reference = event.timestamp() != null && !event.timestamp().isBlank()
                ? event.timestamp() : event.createdAt();
        if (reference != null && reference.length() >= 10) {
            try {
                return LocalDate.parse(reference.substring(0, 10));
            } catch (Exception ignored) {
                // repli sur aujourd'hui
            }
        }
        return LocalDate.now();
    }

    private static LocalDate dayOfFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
            return null;
        }
        String day = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        try {
            return LocalDate.parse(day, DAY);
        } catch (Exception e) {
            return null;
        }
    }
}
