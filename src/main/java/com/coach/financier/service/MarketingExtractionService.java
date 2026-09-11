package com.coach.financier.service;

import com.coach.financier.config.MarketingProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.SuiviModels;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transforme les signaux bruts de l'IA ({@link MarketingModels.MarketingEventDraft}) en
 * ÉVÉNEMENTS persistables : le code ajoute les identifiants techniques, la tranche de montant
 * (configurable, jamais calculée par l'IA), la pseudonymisation et les versions.
 * <p>
 * Confidentialité (§5) : aucune donnée personnelle n'est écrite — l'identifiant client est
 * remplacé par un hash, et les textes libres sont préalablement masqués (email/téléphone).
 */
@Service
public class MarketingExtractionService {
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d .\\-()]{7,}\\d)");
    private static final String MASK = "[masqué]";

    private final MarketingProperties properties;
    private final AnonymousIdService anonymousIdService;

    public MarketingExtractionService(MarketingProperties properties, AnonymousIdService anonymousIdService) {
        this.properties = properties;
        this.anonymousIdService = anonymousIdService;
    }

    public List<MarketingModels.MarketingEvent> toEvents(List<MarketingModels.MarketingEventDraft> drafts,
                                                        String sessionId,
                                                        ConversationModels.Conversation conversation,
                                                        String customerId,
                                                        AIModels.AIProvider provider) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        String anonymousCustomerId = anonymousIdService.anonymize(customerId);
        String timestamp = LocalDateTime.now().withNano(0).toString();
        String createdAt = Instant.now().toString();
        String model = provider == null ? null : provider.name();
        String defaultProjectType = projectTypeOf(conversation);
        String amountRange = amountRangeOf(conversation);

        List<MarketingModels.MarketingEvent> events = new ArrayList<>();
        for (MarketingModels.MarketingEventDraft draft : drafts) {
            if (draft == null || !MarketingModels.EVENT_TYPES.contains(draft.eventType())) {
                continue; // type inconnu : on n'invente rien
            }
            events.add(new MarketingModels.MarketingEvent(
                    UUID.randomUUID().toString(),
                    draft.eventType(),
                    timestamp,
                    sessionId,
                    anonymousCustomerId,
                    firstNonBlank(draft.projectType(), defaultProjectType),
                    amountRange,
                    blank(draft.productId()),
                    maskPii(draft.productName()),
                    blank(draft.productFamily()),
                    normalizeInterestLevel(draft.interestLevel()),
                    blank(draft.reasonCategory()),
                    maskPii(draft.reason()),
                    draft.advisorFollowUpRecommended(),
                    clampConfidence(draft.confidence()),
                    properties.extractorVersion(),
                    properties.promptVersion(),
                    model,
                    createdAt,
                    properties.isDemoMode() ? Boolean.TRUE : null));
        }
        return List.copyOf(events);
    }

    private static String projectTypeOf(ConversationModels.Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        CurrentProject project = conversation.currentProject();
        return project == null || project.getType() == null ? null : project.getType().name();
    }

    private String amountRangeOf(ConversationModels.Conversation conversation) {
        if (conversation == null || conversation.currentProject() == null) {
            return null;
        }
        return properties.amountRangeLabel(conversation.currentProject().getAmount());
    }

    private static String normalizeInterestLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String level = value.trim().toUpperCase(Locale.ROOT);
        return switch (level) {
            case "LOW", "MEDIUM", "HIGH" -> level;
            default -> null;
        };
    }

    private static Double clampConfidence(Double confidence) {
        if (confidence == null) {
            return null;
        }
        if (confidence < 0) return 0.0;
        if (confidence > 1) return 1.0;
        return confidence;
    }

    /** Masque emails et téléphones dans les textes libres (défense en profondeur, §5). */
    static String maskPii(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String masked = EMAIL.matcher(value).replaceAll(MASK);
        return PHONE.matcher(masked).replaceAll(MASK);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** Version du prompt/extracteur utilisée (traçabilité des événements). */
    public String extractorVersion() {
        return properties.extractorVersion();
    }

    public String promptVersion() {
        return properties.promptVersion();
    }

    /** Taille maximale d'un texte libre conservé (garde-fou volumétrie). */
    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }
}
