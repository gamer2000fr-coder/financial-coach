package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stockage FICHIER des contrôles qualité automatiques (§23) :
 * {@code <quality.dir>/checks/coach_quality_checks_YYYY-MM-DD.jsonl}.
 * <p>
 * Un événement par contrôle EXÉCUTÉ (qu'il ait détecté une anomalie ou non), afin de pouvoir
 * distinguer « contrôles effectués » et « anomalies détectées » (§25). Les identifiants sont
 * DÉTERMINISTES ({@code chk-<session>-<type>}) : relancer les contrôles de la même session ne crée
 * aucun doublon.
 */
@Service
public class QualityCheckStore {
    private static final Logger log = LoggerFactory.getLogger(QualityCheckStore.class);
    private static final String PREFIX = "coach_quality_checks_";

    private final QualityProperties properties;
    private final ObjectMapper objectMapper;
    private final Set<String> knownCheckIds = ConcurrentHashMap.newKeySet();
    private final Object writeLock = new Object();
    private volatile boolean idsLoaded = false;

    public QualityCheckStore(QualityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Enregistre des contrôles (dédupliqués par {@code checkId}) ; retourne ceux réellement écrits. */
    public List<QualityModels.QualityCheck> append(List<QualityModels.QualityCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return List.of();
        }
        ensureIdsLoaded();
        Map<LocalDate, List<String>> byDay = new LinkedHashMap<>();
        List<QualityModels.QualityCheck> written = new ArrayList<>();
        for (QualityModels.QualityCheck check : checks) {
            if (check == null || check.checkId() == null || check.checkId().isBlank()) {
                continue;
            }
            if (!knownCheckIds.add(check.checkId())) {
                continue; // déjà contrôlé (relance) : aucun doublon
            }
            try {
                byDay.computeIfAbsent(dayOf(check), d -> new ArrayList<>())
                        .add(objectMapper.writeValueAsString(check));
                written.add(check);
            } catch (Exception e) {
                knownCheckIds.remove(check.checkId());
                log.warn("Contrôle qualité non sérialisable ({}) : {}", check.checkId(), e.getMessage());
            }
        }
        for (Map.Entry<LocalDate, List<String>> entry : byDay.entrySet()) {
            try {
                JsonlFiles.append(JsonlFiles.fileFor(properties.checksDir(), PREFIX, entry.getKey()),
                        entry.getValue(), writeLock);
            } catch (Exception e) {
                log.warn("Contrôles qualité non stockés : {}", e.getMessage());
                written.clear();
            }
        }
        return List.copyOf(written);
    }

    /** Contrôles d'une période (bornes incluses) + lignes invalides comptées. */
    public JsonlFiles.ReadResult<QualityModels.QualityCheck> read(LocalDate from, LocalDate to) {
        JsonlFiles.ReadResult<QualityModels.QualityCheck> result = JsonlFiles.read(
                properties.checksDir(), PREFIX, from, to, QualityModels.QualityCheck.class, objectMapper);
        result.values().forEach(check -> {
            if (check != null && check.checkId() != null) {
                knownCheckIds.add(check.checkId());
            }
        });
        return result;
    }

    /** Jours pour lesquels un fichier de contrôles existe. */
    public List<LocalDate> availableDays() {
        return JsonlFiles.days(properties.checksDir(), PREFIX);
    }

    private static LocalDate dayOf(QualityModels.QualityCheck check) {
        String reference = check.timestamp();
        if (reference != null && reference.length() >= 10) {
            try {
                return LocalDate.parse(reference.substring(0, 10));
            } catch (Exception ignored) {
                // horodatage inattendu : jour courant
            }
        }
        return LocalDate.now();
    }

    private void ensureIdsLoaded() {
        if (idsLoaded) {
            return;
        }
        synchronized (writeLock) {
            if (idsLoaded) {
                return;
            }
            idsLoaded = true;
            read(null, null);
        }
    }
}
