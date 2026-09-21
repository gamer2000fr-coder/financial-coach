package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.SuiviModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Dossier de suivi ÉVALUABLE : persiste le dossier préparé par le Coach et fabrique le LIEN
 * D'ÉVALUATION inséré dans le mail conseiller (§41 à §47).
 * <p>
 * Principes :
 * <ul>
 *   <li>l'URL ne contient QUE le {@code sessionId} — jamais de nom, email, compte, montant ni
 *       commentaire (§45) ;</li>
 *   <li>le lien est fabriqué par le BACKEND et ajouté APRÈS la validation des URLs du dossier : il ne
 *       peut donc pas être neutralisé par le contrôle anti-invention ;</li>
 *   <li>le dossier est persisté en JSONL (best effort) pour que le clic du conseiller retrouve le
 *       dossier sans recherche manuelle (§42/§43) ;</li>
 *   <li>le backend reste seul juge de l'existence du dossier : une session inconnue donne
 *       « Ce dossier n'est plus disponible. » (§46/§50).</li>
 * </ul>
 */
@Service
public class AdvisorDossierService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorDossierService.class);

    /** Verbes d'action utilisés pour extraire le « suivi conseillé » du mail conseiller (heuristique locale). */
    private static final List<String> ACTION_KEYWORDS = List.of("rappeler", "revoir", "vérifier", "verifier",
            "réaliser", "realiser", "accompagner", "proposer", "envoyer", "prendre rendez-vous", "préparer",
            "preparer", "étudier", "etudier", "confirmer", "simulation", "devis", "éligibilité", "eligibilite");

    private final AdvisorDossierStore dossierStore;
    private final AdvisorFeedbackStore feedbackStore;
    private final AdvisorFeedbackProperties properties;
    private final String frontendUrl;

    public AdvisorDossierService(AdvisorDossierStore dossierStore,
                                 AdvisorFeedbackStore feedbackStore,
                                 AdvisorFeedbackProperties properties,
                                 @Value("${app.advisor-feedback.frontend-url:}") String frontendUrl) {
        this.dossierStore = dossierStore;
        this.feedbackStore = feedbackStore;
        this.properties = properties;
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.trim();
    }

    /** URL d'évaluation d'un dossier : uniquement le sessionId, jamais de donnée personnelle (§45). */
    public String feedbackUrl(String sessionId) {
        String encoded = URLEncoder.encode(sessionId == null ? "" : sessionId, StandardCharsets.UTF_8);
        String base = frontendUrl.isBlank() ? "" : stripTrailingSlash(frontendUrl);
        return base + "/#/advisor-feedback/session/" + encoded;
    }

    /**
     * Bloc inséré en fin de mail conseiller (§41), au format de lien du dossier de suivi
     * ({@code [URL|nom|url]}) : il devient un lien cliquable en HTML et un lien lisible en texte.
     */
    public String feedbackBlock(String sessionId) {
        return "--------------------------------\n"
                + "Votre retour nous aide à améliorer le Coach IA.\n"
                + "\n"
                + "Donnez votre avis sur la synthèse et les recommandations préparées par le Coach :\n"
                + "[URL|Évaluer le suivi du Coach|" + feedbackUrl(sessionId) + "]";
    }

    /** URL de consultation de l'historique d'une conversation : uniquement le sessionId, jamais de donnée personnelle. */
    public String conversationUrl(String sessionId) {
        String encoded = URLEncoder.encode(sessionId == null ? "" : sessionId, StandardCharsets.UTF_8);
        String base = frontendUrl.isBlank() ? "" : stripTrailingSlash(frontendUrl);
        return base + "/#/conversation/" + encoded;
    }

    /**
     * Bloc « relire la conversation » ajouté AU mail conseiller (jamais au brouillon client) : le conseiller
     * retrouve l'intégralité des échanges client ↔ Coach avant de reprendre contact. Le lien ne contient que
     * le sessionId (aucune donnée personnelle) et reste relatif si la base IHM n'est pas configurée.
     * <p>
     * ⚠️ Ce bloc n'est PLUS ajouté au mail : le conseiller ouvre désormais le dossier directement dans la page
     * « Centre d'appels » (voir {@link #directoryUrl}). La page de relecture reste disponible (elle est
     * exposée par l'API et par la pop-in du centre d'appels) : le bloc est conservé pour un usage ultérieur.
     */
    public String conversationBlock(String sessionId) {
        return "Échanges de la conversation avec le Coach :\n"
                + "[URL|Consulter l'historique de la conversation|" + conversationUrl(sessionId) + "]";
    }

    /** La base IHM est-elle configurée ? Sinon les liens ajoutés au mail restent relatifs. */
    public boolean hasFrontendUrl() {
        return !frontendUrl.isBlank();
    }

    /**
     * URL du dossier dans la page « Centre d'appels — conversations » : elle cible DIRECTEMENT le dossier
     * (`#/centre-appels/<sessionId>`) et l'IHM ouvre la pop-in de ce dossier. C'est le lien « Ouvrir le dossier
     * du client » du mail conseiller. Comme les autres liens du projet, l'URL ne contient QUE le sessionId.
     */
    public String directoryUrl(String sessionId) {
        String encoded = URLEncoder.encode(sessionId == null ? "" : sessionId, StandardCharsets.UTF_8);
        String base = frontendUrl.isBlank() ? "" : stripTrailingSlash(frontendUrl);
        return base + "/#/centre-appels/" + encoded;
    }

    /**
     * Éléments complémentaires du dossier, produits par la clôture : identité « métier » (client, titre,
     * catégorie), score de sens commercial et transcript de la conversation. Ils alimentent l'ANNUAIRE
     * DES CONVERSATIONS du centre d'appels et restent facultatifs (dossier minimal si absents).
     */
    public record DossierExtras(String customerId, String title, String category, String categoryLabel,
                                SuiviModels.CommercialScore score,
                                List<AdvisorFeedbackModels.DossierMessage> transcript) {
        public static DossierExtras empty() {
            return new DossierExtras(null, null, null, null, null, List.of());
        }

        public DossierExtras {
            transcript = transcript == null ? List.of() : List.copyOf(transcript);
        }
    }

    /** Persiste le dossier préparé (best effort : un échec ne bloque jamais la clôture). */
    public Optional<AdvisorFeedbackModels.AdvisorDossier> persist(String sessionId, SuiviModels.SuiviResult result) {
        return persist(sessionId, result, DossierExtras.empty());
    }

    /** Persiste le dossier préparé avec les éléments de l'annuaire (client, score, transcript). */
    public Optional<AdvisorFeedbackModels.AdvisorDossier> persist(String sessionId, SuiviModels.SuiviResult result,
                                                                  DossierExtras extras) {
        if (!properties.isEnabled() || sessionId == null || sessionId.isBlank() || result == null) {
            return Optional.empty();
        }
        DossierExtras details = extras == null ? DossierExtras.empty() : extras;
        String timestamp = Instant.now().toString();
        SuiviModels.ConversationSummary summary = result.conversationSummary();
        List<AdvisorFeedbackModels.DossierProduct> products = new ArrayList<>();
        for (SuiviModels.ProductOfInterest product : result.productsOfInterest()) {
            products.add(new AdvisorFeedbackModels.DossierProduct(product.productId(), product.name(),
                    product.category(), product.interestLevel(), product.interestReason(), product.productUrl()));
        }
        String advisorBody = result.advisorEmail() == null ? "" : nullToEmpty(result.advisorEmail().body());
        AdvisorFeedbackModels.AdvisorDossier dossier = new AdvisorFeedbackModels.AdvisorDossier(
                "dossier-" + UUID.nameUUIDFromBytes((sessionId + "|" + timestamp).getBytes(StandardCharsets.UTF_8)),
                timestamp,
                sessionId,
                summary == null ? null : summary.mainProject(),
                summary == null ? List.of() : summary.otherProjects(),
                summary == null ? List.of() : summary.importantCustomerPreferences(),
                products,
                extractNextActions(advisorBody),
                new AdvisorFeedbackModels.DossierEmail(
                        result.advisorEmail() == null ? null : result.advisorEmail().subject(), advisorBody),
                new AdvisorFeedbackModels.DossierEmail(
                        result.preparedCustomerEmail() == null ? null : result.preparedCustomerEmail().subject(),
                        result.preparedCustomerEmail() == null ? null : result.preparedCustomerEmail().body()),
                feedbackUrl(sessionId),
                timestamp,
                new AdvisorFeedbackModels.DossierClient(details.customerId(),
                        firstNonBlank(details.title(), summary == null ? null : summary.mainProject()),
                        details.category(), details.categoryLabel()),
                toDossierScore(details.score()),
                details.transcript());
        return dossierStore.save(dossier);
    }

    /** Traduit le score de clôture en score persisté (raisons + critères mesurés). */
    private static AdvisorFeedbackModels.DossierScore toDossierScore(SuiviModels.CommercialScore score) {
        if (score == null) {
            return null;
        }
        return new AdvisorFeedbackModels.DossierScore(score.score(), score.priority(), score.label(),
                score.reasons(), score.details(), score.proposedByAi());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }


    /**
     * Vue d'un dossier pour l'écran d'évaluation : dossier + feedback éventuel + statut (§48).
     * Vide si le dossier n'existe pas (le frontend affiche alors « Ce dossier n'est plus disponible. »).
     */
    public Optional<AdvisorFeedbackModels.DossierView> view(String sessionId) {
        Optional<AdvisorFeedbackModels.AdvisorDossier> dossier = dossierStore.findBySession(sessionId);
        if (dossier.isEmpty()) {
            return Optional.empty();
        }
        AdvisorFeedbackModels.AdvisorFeedback feedback = feedbackStore.latestBySession(sessionId).orElse(null);
        String status = feedback != null ? AdvisorFeedbackModels.STATUS_COMPLETED
                : (dossier.get().feedbackUrl() == null || dossier.get().feedbackUrl().isBlank()
                ? AdvisorFeedbackModels.STATUS_NOT_REQUESTED : AdvisorFeedbackModels.STATUS_PENDING);
        return Optional.of(new AdvisorFeedbackModels.DossierView(dossier.get(), feedback, status));
    }

    /** Extrait les actions de suivi du mail conseiller (heuristique locale, aucune donnée inventée). */
    static List<String> extractNextActions(String advisorEmailBody) {
        List<String> actions = new ArrayList<>();
        if (advisorEmailBody == null || advisorEmailBody.isBlank()) {
            return actions;
        }
        boolean inSection = false;
        for (String rawLine : advisorEmailBody.split("\\r?\\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("suivi conseill") || lower.contains("prochaine action")) {
                inSection = true;
                continue;
            }
            // Fin de la section : mention de la pièce jointe, séparateur ou nouvelle rubrique.
            if (inSection && (lower.contains("pièce jointe") || lower.contains("piece jointe")
                    || line.startsWith("---") || line.startsWith("Votre retour"))) {
                inSection = false;
                continue;
            }
            boolean candidate = inSection || lower.startsWith("-") || lower.startsWith("•");
            if (!candidate) {
                continue;
            }
            if (line.startsWith("#")) {
                continue; // titre : on continue la section
            }
            String cleaned = line.replaceFirst("^[-•*\\s]+", "").strip();
            String normalized = java.text.Normalizer
                    .normalize(cleaned.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            if (cleaned.length() > 8 && ACTION_KEYWORDS.stream().anyMatch(normalized::contains)) {
                actions.add(cleaned);
                if (actions.size() >= 5) {
                    break;
                }
            }
        }
        return List.copyOf(actions);
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Journalise l'absence d'URL frontend (le lien reste relatif : utilisable depuis le navigateur du conseiller). */
    public boolean absoluteUrlConfigured() {
        if (frontendUrl.isBlank()) {
            log.debug("app.advisor-feedback.frontend-url non configurée : le lien d'évaluation sera relatif.");
            return false;
        }
        return true;
    }
}
