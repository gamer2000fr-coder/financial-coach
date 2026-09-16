package com.coach.financier.config;

import com.coach.financier.model.PromptOptimizationModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Configuration de l'ATELIER d'amélioration itérative des prompts
 * ({@code app.prompt-optimization.*}).
 * <p>
 * Aucune base de données : le stockage est fait de fichiers JSON/JSONL sous
 * {@code <dir>/campaigns/<campaignId>/} (mêmes conventions que Marketing, Qualité et
 * Feedback Conseiller).
 */
@Component
public class PromptOptimizationProperties {

    private final boolean enabled;
    private final boolean demoMode;
    private final Path baseDir;
    private final int maxIterations;
    private final int maxEditableSectionLength;
    private final String hashSalt;

    public PromptOptimizationProperties(
            @Value("${app.prompt-optimization.enabled:true}") boolean enabled,
            @Value("${app.prompt-optimization.demo-mode:false}") boolean demoMode,
            @Value("${app.prompt-optimization.dir:./data/prompt-optimization}") String dir,
            @Value("${app.prompt-optimization.max-iterations:50}") int maxIterations,
            @Value("${app.prompt-optimization.max-editable-section-length:20000}") int maxEditableSectionLength,
            @Value("${app.prompt-optimization.hash-salt:coach-financier-poc}") String hashSalt) {
        this.enabled = enabled;
        this.demoMode = demoMode;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.maxIterations = maxIterations <= 0 || maxIterations > PromptOptimizationModels.MAX_ITERATIONS
                ? PromptOptimizationModels.MAX_ITERATIONS : maxIterations;
        this.maxEditableSectionLength = maxEditableSectionLength <= 0 ? 20000 : maxEditableSectionLength;
        this.hashSalt = hashSalt == null || hashSalt.isBlank() ? "coach-financier-poc" : hashSalt;
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

    /** Répertoire des campagnes : {@code <dir>/campaigns/<campaignId>/}. */
    public Path campaignsDir() {
        return baseDir.resolve("campaigns");
    }

    /** Plafond CUMULÉ d'itérations par campagne (jamais au-delà de {@code MAX_ITERATIONS}). */
    public int maxIterations() {
        return maxIterations;
    }

    /** Longueur maximale acceptée pour une zone éditable proposée par l'Agent A. */
    public int maxEditableSectionLength() {
        return maxEditableSectionLength;
    }

    /** Sel utilisé pour les empreintes du module (jamais de donnée personnelle en clair). */
    public String hashSalt() {
        return hashSalt;
    }
}
