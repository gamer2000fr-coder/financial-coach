package com.coach.financier.config;

import com.coach.financier.model.QualityModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration du module <b>Qualité &amp; Satisfaction du Coach IA</b> ({@code app.quality.*}).
 * <p>
 * Les SÉVÉRITÉS des contrôles et la LISTE des contrôles réellement exécutés sont configurables
 * (§21) : le code les applique, le LLM ne décide rien. Un contrôle non listé n'est PAS exécuté et
 * n'apparaît donc jamais comme un faux « 0 » dans les KPI (§25).
 */
@Component
public class QualityProperties {

    /** Contrôles réellement implémentés dans cette version du POC. */
    public static final List<String> DEFAULT_CHECKS = List.of(
            QualityModels.CREDIT_SIMULATION_VIOLATION,
            QualityModels.PRODUCT_MISMATCH,
            QualityModels.INVENTED_URL,
            QualityModels.UNANSWERED_REQUEST,
            QualityModels.MISSING_DATA_NOT_RETRIEVED,
            QualityModels.EXCESSIVE_REPETITION);

    private final boolean enabled;
    private final boolean demoMode;
    private final Path baseDir;
    private final String hashSalt;
    private final int commentMaxLength;
    private final int maxCommentsToAnalyze;
    private final long sufficientSampleSize;
    private final String promptVersion;
    private final List<String> enabledChecks;
    private final Map<String, String> severities;

    public QualityProperties(
            @Value("${app.quality.enabled:true}") boolean enabled,
            @Value("${app.quality.demo-mode:false}") boolean demoMode,
            @Value("${app.quality.dir:./data/quality}") String dir,
            @Value("${app.quality.hash-salt:coach-financier-poc}") String hashSalt,
            @Value("${app.quality.comment-max-length:1000}") int commentMaxLength,
            @Value("${app.quality.max-comments-to-analyze:30}") int maxCommentsToAnalyze,
            @Value("${app.quality.sufficient-sample-size:10}") long sufficientSampleSize,
            @Value("${app.quality.prompt-version:quality-report-v1}") String promptVersion,
            @Value("${app.quality.checks:}") String checks,
            @Value("${app.quality.severity.credit-simulation:}") String severityCreditSimulation,
            @Value("${app.quality.severity.product-mismatch:}") String severityProductMismatch,
            @Value("${app.quality.severity.invented-url:}") String severityInventedUrl,
            @Value("${app.quality.severity.unanswered-request:}") String severityUnansweredRequest,
            @Value("${app.quality.severity.missing-data:}") String severityMissingData,
            @Value("${app.quality.severity.excessive-repetition:}") String severityRepetition) {
        this.enabled = enabled;
        this.demoMode = demoMode;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.hashSalt = hashSalt;
        this.commentMaxLength = commentMaxLength <= 0 ? 1000 : commentMaxLength;
        this.maxCommentsToAnalyze = maxCommentsToAnalyze <= 0 ? 30 : maxCommentsToAnalyze;
        this.sufficientSampleSize = sufficientSampleSize <= 0 ? 10 : sufficientSampleSize;
        this.promptVersion = promptVersion;
        this.enabledChecks = parseChecks(checks);

        this.severities = new LinkedHashMap<>();
        put(severities, QualityModels.CREDIT_SIMULATION_VIOLATION,
                severityCreditSimulation, QualityModels.SEVERITY_HIGH);
        put(severities, QualityModels.PRODUCT_MISMATCH, severityProductMismatch, QualityModels.SEVERITY_HIGH);
        put(severities, QualityModels.INVENTED_URL, severityInventedUrl, QualityModels.SEVERITY_HIGH);
        put(severities, QualityModels.UNANSWERED_REQUEST, severityUnansweredRequest, QualityModels.SEVERITY_MEDIUM);
        put(severities, QualityModels.MISSING_DATA_NOT_RETRIEVED, severityMissingData, QualityModels.SEVERITY_MEDIUM);
        put(severities, QualityModels.EXCESSIVE_REPETITION, severityRepetition, QualityModels.SEVERITY_LOW);
    }

    private static void put(Map<String, String> target, String checkType, String raw, String fallback) {
        target.put(checkType, raw == null || raw.isBlank()
                ? fallback : QualityModels.normalizeSeverity(raw));
    }

    private static List<String> parseChecks(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_CHECKS;
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && QualityModels.isKnownCheck(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result.isEmpty() ? DEFAULT_CHECKS : List.copyOf(result);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path feedbackDir() {
        return baseDir.resolve("feedback");
    }

    public Path checksDir() {
        return baseDir.resolve("checks");
    }

    public Path aggregatesDir() {
        return baseDir.resolve("aggregates");
    }

    public Path reportsDir() {
        return baseDir.resolve("reports");
    }

    public String hashSalt() {
        return hashSalt;
    }

    public int commentMaxLength() {
        return commentMaxLength;
    }

    public int maxCommentsToAnalyze() {
        return maxCommentsToAnalyze;
    }

    /** En dessous de ce volume d'avis, l'IA doit rester prudente (§24 du prompt). */
    public long sufficientSampleSize() {
        return sufficientSampleSize;
    }

    public String promptVersion() {
        return promptVersion;
    }

    /** Contrôles réellement exécutés (§25 : jamais de faux « 0 » pour un contrôle inexistant). */
    public List<String> enabledChecks() {
        return enabledChecks;
    }

    /** Contrôles décrits par la spécification mais NON implémentés dans cette version. */
    public List<String> notImplementedChecks() {
        return QualityModels.CHECK_TYPES.stream().filter(type -> !enabledChecks.contains(type)).toList();
    }

    public boolean isCheckEnabled(String checkType) {
        return checkType != null && enabledChecks.contains(checkType.trim().toUpperCase(Locale.ROOT));
    }

    /** Sévérité configurée d'un contrôle (§21 : pas de sévérité dispersée dans le code). */
    public String severityFor(String checkType) {
        if (checkType == null) {
            return QualityModels.SEVERITY_MEDIUM;
        }
        return severities.getOrDefault(checkType.trim().toUpperCase(Locale.ROOT), QualityModels.SEVERITY_MEDIUM);
    }
}
