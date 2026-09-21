package com.coach.financier.model;

import java.util.ArrayList;
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

    /**
     * Sortie IA structurée demandée à l'agent de synthèse (agent/suivi.txt) : dossier conseiller,
     * brouillon client ET signaux Marketing (même appel IA, §3 du module Marketing Intelligence).
     * <p>
     * {@code commercialScore} est la PROPOSITION de score de l'IA (clé {@code commercialScore} du JSON) :
     * elle est validée puis complétée par le backend (voir {@link CommercialScore}) ; {@code null} signifie
     * que l'IA n'en a pas proposé — le score est alors entièrement calculé par le backend.
     */
    public record SuiviResult(
            ConversationSummary conversationSummary,
            List<ProductOfInterest> productsOfInterest,
            EmailContent advisorEmail,
            EmailContent preparedCustomerEmail,
            List<MarketingModels.MarketingEventDraft> marketingEvents,
            AiScoreProposal commercialScore
    ) {
        public SuiviResult {
            marketingEvents = marketingEvents == null ? List.of() : List.copyOf(marketingEvents);
        }

        /** Compatibilité : résultat sans proposition de score (l'IA n'en fournit pas). */
        public SuiviResult(ConversationSummary conversationSummary,
                           List<ProductOfInterest> productsOfInterest,
                           EmailContent advisorEmail,
                           EmailContent preparedCustomerEmail,
                           List<MarketingModels.MarketingEventDraft> marketingEvents) {
            this(conversationSummary, productsOfInterest, advisorEmail, preparedCustomerEmail, marketingEvents, null);
        }
    }

    // ------------------------------------------------------------------ score de sens commercial

    /** Niveaux de priorité commerciale (badge et tri de l'annuaire du centre d'appels). */
    public static final String PRIORITY_VERY_HIGH = "VERY_HIGH";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_MEDIUM = "MEDIUM";
    public static final String PRIORITY_LOW = "LOW";

    /**
     * Score PROPOSÉ par l'IA de synthèse : elle seule sait lire la conversation (urgence réellement
     * exprimée, maturité du projet, objections...). Le backend borne le score à 0..100, ignore les
     * raisons vides et recalcule TOUJOURS les critères mesurables (traçabilité).
     */
    public record AiScoreProposal(Integer score, String urgency, List<String> reasons) {
        public AiScoreProposal {
            List<String> cleaned = new ArrayList<>();
            if (reasons != null) {
                for (String reason : reasons) {
                    if (reason != null && !reason.isBlank()) {
                        cleaned.add(reason.trim());
                    }
                }
            }
            reasons = List.copyOf(cleaned);
            urgency = urgency == null ? "" : urgency.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Critère CHIFFRÉ du score : libellé, points obtenus, points maximum, précision factuelle. */
    public record ScoreCriterion(String code, String label, int points, int maxPoints, String detail) {
        public ScoreCriterion {
            code = code == null ? "" : code;
            label = label == null ? "" : label;
            detail = detail == null ? "" : detail;
        }

        /** Ligne lisible : « Maturité du projet : 27/30 — vehicle, 15 000 EUR ». */
        public String display() {
            String pointsText = points + "/" + maxPoints;
            return detail.isBlank() ? label + " : " + pointsText : label + " : " + pointsText + " — " + detail;
        }
    }

    /**
     * SCORE DE SENS COMMERCIAL attribué à la clôture : il donne au conseiller un ordre de priorité
     * objectivable (« affaire mûre et urgente » avant « projet exploratoire ») et une explication courte.
     * <p>
     * Il n'est JAMAIS montré au client. Aucun seuil bancaire n'est utilisé : les critères s'appuient
     * uniquement sur les signaux réellement présents dans la conversation et la fiche client.
     */
    public record CommercialScore(
            int score,
            String priority,
            String label,
            List<String> reasons,
            List<ScoreCriterion> criteria,
            List<String> details,
            boolean proposedByAi
    ) {
        public CommercialScore {
            score = Math.max(0, Math.min(100, score));
            priority = priority == null || priority.isBlank() ? PRIORITY_LOW : priority;
            label = label == null ? "" : label;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
            if (details == null || details.isEmpty()) {
                List<String> rendered = new ArrayList<>();
                for (ScoreCriterion criterion : criteria) {
                    rendered.add(criterion.display());
                }
                details = List.copyOf(rendered);
            } else {
                details = List.copyOf(details);
            }
        }

        /** « 72/100 — Priorité haute », prêt à afficher (mail conseiller, IHM). */
        public String display() {
            return score + "/100 — " + label;
        }
    }


    /** Pièce jointe générée à partir du brouillon client. */
    public record Attachment(String filename, byte[] content, String contentType) {}

    /**
     * Adresses de préremplissage de la pièce jointe .eml : le brouillon est adressé AU CLIENT
     * ({@code customer}) et expédié PAR LE CONSEILLER ({@code advisor}). Un type dédié évite
     * d'inverser les deux adresses. Valeurs {@code null}/vides = champ correspondant laissé vide.
     */
    public record EmailAddresses(String customer, String advisor) {}

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
