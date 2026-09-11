package com.coach.financier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Configuration du module <b>Feedback Conseiller</b> ({@code app.advisor-feedback.*}).
 * <p>
 * Aucune base de données : le stockage est fait de fichiers JSONL/JSON sous
 * {@code <dir>/events|aggregates|reports} (mêmes conventions que Marketing et Qualité).
 */
@Component
public class AdvisorFeedbackProperties {

    private final boolean enabled;
    private final boolean demoMode;
    private final Path baseDir;
    private final String hashSalt;
    private final int commentMaxLength;
    private final long sufficientSampleSize;
    private final String promptVersion;

    public AdvisorFeedbackProperties(
            @Value("${app.advisor-feedback.enabled:true}") boolean enabled,
            @Value("${app.advisor-feedback.demo-mode:false}") boolean demoMode,
            @Value("${app.advisor-feedback.dir:./data/advisor-feedback}") String dir,
            @Value("${app.advisor-feedback.hash-salt:coach-financier-poc}") String hashSalt,
            @Value("${app.advisor-feedback.comment-max-length:1000}") int commentMaxLength,
            @Value("${app.advisor-feedback.sufficient-sample-size:5}") long sufficientSampleSize,
            @Value("${app.advisor-feedback.prompt-version:advisor-feedback-v1}") String promptVersion) {
        this.enabled = enabled;
        this.demoMode = demoMode;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.hashSalt = hashSalt;
        this.commentMaxLength = commentMaxLength <= 0 ? 1000 : commentMaxLength;
        this.sufficientSampleSize = sufficientSampleSize <= 0 ? 5 : sufficientSampleSize;
        this.promptVersion = promptVersion;
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

    public Path eventsDir() {
        return baseDir.resolve("events");
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

    /** En dessous de ce volume de feedbacks, l'IA doit rester prudente (§27 du prompt). */
    public long sufficientSampleSize() {
        return sufficientSampleSize;
    }

    public String promptVersion() {
        return promptVersion;
    }
}
