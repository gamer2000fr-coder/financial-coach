package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ENREGISTREMENT DU FEEDBACK CLIENT de fin de conversation (pop-in 1 à 5 étoiles + commentaire
 * facultatif + motifs conditionnels).
 * <p>
 * Garanties :
 * <ul>
 *   <li>le feedback est TOTALEMENT facultatif : « Passer » est une réponse valide ;</li>
 *   <li>un échec d'enregistrement N'EMPÊCHE JAMAIS la clôture de la conversation (§43) ;</li>
 *   <li>les doubles envois (double clic, retry réseau, refresh) sont dédupliqués : l'identifiant est
 *       DÉTERMINISTE (session + source) et une session ne peut porter qu'un seul avis (§44) ;</li>
 *   <li>aucune donnée personnelle n'est stockée : l'identifiant client est pseudonymisé et le
 *       commentaire est nettoyé (emails/téléphones masqués) avant tout usage (§45).</li>
 * </ul>
 */
@Service
public class QualityFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(QualityFeedbackService.class);

    private final QualityFeedbackStore feedbackStore;
    private final QualityProperties properties;
    private final AnonymousIdService anonymousIdService;
    private final com.coach.financier.repository.BankingDataRepository bankingDataRepository;

    public QualityFeedbackService(QualityFeedbackStore feedbackStore,
                                  QualityProperties properties,
                                  AnonymousIdService anonymousIdService,
                                  com.coach.financier.repository.BankingDataRepository bankingDataRepository) {
        this.feedbackStore = feedbackStore;
        this.properties = properties;
        this.anonymousIdService = anonymousIdService;
        this.bankingDataRepository = bankingDataRepository;
    }

    /** Réponse API : jamais d'erreur bloquante pour le client (§43). */
    public record FeedbackResponse(String status, String feedbackId, String sessionId, Integer rating,
                                   List<String> selectedReasons, String message) {
    }

    /**
     * Enregistre le feedback d'une conversation à partir d'une requête brute (note, motifs, commentaire).
     * <p>
     * L'identifiant client provient de la fiche bancaire puis est <b>pseudonymisé</b> avant stockage :
     * aucun identifiant en clair n'est écrit sur disque (§45).
     */
    public FeedbackResponse submit(String sessionId, QualityModels.FeedbackRequest request) {
        if (!properties.isEnabled()) {
            return new FeedbackResponse("DISABLED", null, sessionId, null, List.of(),
                    "Module qualité désactivé.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return new FeedbackResponse("INVALID", null, sessionId, null, List.of(),
                    "Session inconnue : aucun avis enregistré.");
        }
        if (request == null || request.rating() == null) {
            // « Passer » : aucun avis, aucune erreur, la conversation se clôture normalement.
            return new FeedbackResponse("SKIPPED", null, sessionId, null, List.of(),
                    "Aucun avis fourni (facultatif).");
        }
        int rating = request.rating();
        if (rating < 1 || rating > 5) {
            return new FeedbackResponse("INVALID", null, sessionId, rating, List.of(),
                    "La note doit être comprise entre 1 et 5.");
        }

        String sanitizedComment = QualityModels.sanitizeComment(request.comment(), properties.commentMaxLength());
        List<String> reasons = rating <= 3
                ? request.selectedReasons().stream().map(QualityModels::normalizeReason).distinct().toList()
                : List.of(); // note positive : aucun motif demandé (§7)

        String feedbackId = feedbackId(sessionId);
        String timestamp = Instant.now().toString();
        QualityModels.CoachFeedback feedback = new QualityModels.CoachFeedback(
                feedbackId,
                timestamp,
                sessionId,
                anonymousIdService.anonymize(customerReference()),
                rating,
                reasons,
                List.of(),
                null, null, null,
                sanitizedComment,
                QualityModels.SOURCE_POPUP,
                timestamp);

        QualityFeedbackStore.SaveResult result = feedbackStore.save(feedback);
        if (result.duplicate()) {
            return new FeedbackResponse("DUPLICATE", result.feedback().feedbackId(), sessionId, rating, reasons,
                    "Un avis a déjà été enregistré pour cette conversation.");
        }
        if (!result.saved()) {
            log.warn("Avis non enregistré pour la session {} : {}", sessionId, result.error());
            return new FeedbackResponse("STORAGE_ERROR", feedbackId, sessionId, rating, reasons,
                    "Avis non enregistré (la conversation reste clôturable).");
        }
        return new FeedbackResponse("SAVED", feedbackId, sessionId, rating, reasons, null);
    }

    /** Avis déjà enregistré pour une session (affichage / idempotence). */
    public java.util.Optional<QualityModels.CoachFeedback> find(String sessionId) {
        return feedbackStore.findBySession(sessionId);
    }

    /**
     * Identifiant DÉTERMINISTE : deux appels pour la même session produisent le même identifiant,
     * ce qui rend le doublon impossible même en cas de double clic ou de retry (§44).
     */
    public static String feedbackId(String sessionId) {
        return "fb-" + UUID.nameUUIDFromBytes(
                (sessionId + "|" + QualityModels.SOURCE_POPUP).getBytes(StandardCharsets.UTF_8));
    }

    /** Référence client de la fiche bancaire (jamais stockée en clair : pseudonymisée aussitôt). */
    private String customerReference() {
        try {
            return bankingDataRepository.loadSnapshot().rawData().path("customer").path("customerId").asText(null);
        } catch (Exception e) {
            log.warn("Référence client indisponible pour le feedback : {}", e.getMessage());
            return null;
        }
    }
}
