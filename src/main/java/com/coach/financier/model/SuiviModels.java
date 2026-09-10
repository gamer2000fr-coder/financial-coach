package com.coach.financier.model;

import java.util.List;

/**
 * Modèles de la CLÔTURE de conversation : préparation du dossier de suivi destiné
 * au conseiller bancaire, avec un brouillon d'email client en pièce jointe.
 * <p>
 * Principe métier : « IA prépare → conseiller contrôle → conseiller décide → conseiller envoie ».
 * Le système n'envoie JAMAIS automatiquement l'email commercial au client.
 */
public final class SuiviModels {
    private SuiviModels() {}

    /** Niveaux d'intérêt d'un produit (le refus explicite est conservé pour ne pas le promouvoir). */
    public enum InterestLevel {
        HIGH,
        MEDIUM,
        LOW,
        REJECTED;

        /** Normalise la valeur libre renvoyée par le LLM ({@code null}/inconnue → LOW). */
        public static InterestLevel parse(String value) {
            if (value == null) {
                return LOW;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return LOW;
            }
        }
    }

    /**
     * Produit/offre évoqué pendant l'échange et son niveau d'intérêt observé.
     * {@code interestLevel} est une chaîne (HIGH/MEDIUM/LOW/REJECTED) volontairement tolérante :
     * la valeur libre du LLM est normalisée côté backend en {@link InterestLevel}.
     */
    public record ProductOfInterest(
            String productId,
            String name,
            String category,
            String interestLevel,
            String interestReason,
            String productUrl
    ) {}

    /** Contenu d'un email : objet + corps (les liens sont au format [URL|nom|url]). */
    public record EmailContent(String subject, String body) {}

    /** Synthèse structurée d'une conversation (§15 : sortie IA stricte). */
    public record ConversationSummary(
            String mainProject,
            List<String> otherProjects,
            List<String> importantCustomerPreferences
    ) {}

    /** Sortie IA structurée demandée à l'agent de synthèse (agent/suivi.txt). */
    public record SuiviResult(
            ConversationSummary conversationSummary,
            List<ProductOfInterest> productsOfInterest,
            EmailContent advisorEmail,
            EmailContent preparedCustomerEmail
    ) {}

    /** Pièce jointe générée à partir du brouillon client. */
    public record Attachment(String filename, byte[] content, String contentType) {}

    /**
     * Corps de la requête de clôture. Tous les champs sont optionnels : la configuration
     * ({@code app.advisor.*}, {@code app.suivi.*}) sert de repli.
     *
     * @param send {@code false} = dry-run (on prépare sans envoyer) ; défaut : true
     */
    public record CloseConversationRequest(
            String advisorEmail,
            String advisorName,
            String attachmentFormat,
            Boolean send,
            AIModels.AIProvider provider
    ) {}

    /** Résultat de la clôture renvoyé à l'appelant (IHM ou intégration). */
    public record CloseConversationResponse(
            String sessionId,
            String status,
            String advisorName,
            String advisorAddress,
            String attachmentName,
            List<String> sentTo,
            ConversationSummary conversationSummary,
            List<ProductOfInterest> productsOfInterest,
            List<String> rejectedProducts,
            EmailContent advisorEmail,
            EmailContent preparedCustomerEmail,
            List<String> warnings
    ) {}
}
