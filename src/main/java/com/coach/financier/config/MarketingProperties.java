package com.coach.financier.config;

import com.coach.financier.model.MarketingModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration du module Marketing Intelligence ({@code app.marketing.*}).
 * <p>
 * Les TRANCHES de montant et les POIDS du score d'intérêt sont configurables (§7/§17) : seul le
 * code les applique, jamais le LLM.
 */
@Component
public class MarketingProperties {

    private final boolean enabled;
    private final boolean demoMode;
    private final Path baseDir;
    private final String hashSalt;
    private final List<BigDecimal> amountBounds;
    private final String extractorVersion;
    private final String promptVersion;
    private final Map<String, Double> scoreWeights;

    public MarketingProperties(
            @Value("${app.marketing.enabled:true}") boolean enabled,
            @Value("${app.marketing.demo-mode:false}") boolean demoMode,
            @Value("${app.marketing.dir:./data/marketing}") String dir,
            @Value("${app.marketing.hash-salt:coach-financier-poc}") String hashSalt,
            @Value("${app.marketing.amount-bounds:2000,5000,10000,15000,30000}") String amountBounds,
            @Value("${app.marketing.extractor-version:marketing-events-v1}") String extractorVersion,
            @Value("${app.marketing.prompt-version:marketing-extractor-v1}") String promptVersion,
            @Value("${app.marketing.score.recommended:1}") double scoreRecommended,
            @Value("${app.marketing.score.interest-low:0}") double scoreInterestLow,
            @Value("${app.marketing.score.interest-medium:2}") double scoreInterestMedium,
            @Value("${app.marketing.score.interest-high:3}") double scoreInterestHigh,
            @Value("${app.marketing.score.comparison:2}") double scoreComparison,
            @Value("${app.marketing.score.subscription:4}") double scoreSubscription,
            @Value("${app.marketing.score.appointment:5}") double scoreAppointment,
            @Value("${app.marketing.score.rejected:-5}") double scoreRejected) {
        this.enabled = enabled;
        this.demoMode = demoMode;
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
        this.hashSalt = hashSalt;
        this.amountBounds = parseBoundaries(amountBounds);
        this.extractorVersion = extractorVersion;
        this.promptVersion = promptVersion;
        this.scoreWeights = new LinkedHashMap<>();
        this.scoreWeights.put(MarketingModels.PRODUCT_RECOMMENDED, scoreRecommended);
        this.scoreWeights.put(MarketingModels.PRODUCT_REJECTED, scoreRejected);
        this.scoreWeights.put(MarketingModels.PRODUCT_COMPARISON, scoreComparison);
        this.scoreWeights.put(MarketingModels.SUBSCRIPTION_INTEREST, scoreSubscription);
        this.scoreWeights.put(MarketingModels.APPOINTMENT_INTEREST, scoreAppointment);
        this.scoreWeights.put("INTEREST_LOW", scoreInterestLow);
        this.scoreWeights.put("INTEREST_MEDIUM", scoreInterestMedium);
        this.scoreWeights.put("INTEREST_HIGH", scoreInterestHigh);
    }

    private static List<BigDecimal> parseBoundaries(String raw) {
        List<BigDecimal> bounds = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (value.isEmpty()) continue;
                try {
                    bounds.add(new BigDecimal(value));
                } catch (NumberFormatException ignored) {
                    // Borne invalide ignorée : on n'invente aucune tranche.
                }
            }
        }
        bounds.sort(BigDecimal::compareTo);
        return List.copyOf(bounds);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isDemoMode() { return demoMode; }
    public Path baseDir() { return baseDir; }
    public Path eventsDir() { return baseDir.resolve("events"); }
    public Path aggregatesDir() { return baseDir.resolve("aggregates"); }
    public Path reportsDir() { return baseDir.resolve("reports"); }
    public String hashSalt() { return hashSalt; }
    public String extractorVersion() { return extractorVersion; }
    public String promptVersion() { return promptVersion; }

    /** Tranches de montant exposées par l'API (bornes + « PLUS »). */
    public List<BigDecimal> amountBounds() { return amountBounds; }

    /** Libellé de tranche pour un montant (ex. {@code 10000_15000}, {@code 30000_PLUS}). */
    public String amountRangeLabel(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal previous = BigDecimal.ZERO;
        for (BigDecimal bound : amountBounds) {
            if (amount.compareTo(bound) < 0) {
                return label(previous, bound);
            }
            previous = bound;
        }
        if (amountBounds.isEmpty()) {
            return null;
        }
        return previous.stripTrailingZeros().toPlainString() + "_PLUS";
    }

    private static String label(BigDecimal from, BigDecimal to) {
        return from.stripTrailingZeros().toPlainString() + "_" + to.stripTrailingZeros().toPlainString();
    }

    /** Poids du score d'intérêt (§17) pour un événement / niveau d'intérêt. */
    public double scoreFor(String eventType, String interestLevel) {
        if (MarketingModels.PRODUCT_INTEREST.equals(eventType)) {
            String level = interestLevel == null ? "" : interestLevel.trim().toUpperCase(java.util.Locale.ROOT);
            return scoreWeights.getOrDefault("INTEREST_" + level, 0.0);
        }
        return scoreWeights.getOrDefault(eventType, 0.0);
    }
}
