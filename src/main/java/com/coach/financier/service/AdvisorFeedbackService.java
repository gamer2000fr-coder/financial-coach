package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saisie du FEEDBACK CONSEILLER : validation, normalisation des codes, anonymisation du conseiller
 * et enregistrement idempotent.
 * <p>
 * Garanties (§3, §16, §36, §38) :
 * <ul>
 *   <li>l'évaluation globale suffit : ni commentaire ni détail ne sont obligatoires ;</li>
 *   <li>une erreur d'enregistrement ne casse JAMAIS le dossier conseiller (statut explicite renvoyé) ;</li>
 *   <li>double clic / retry → un seul événement, une révision crée une version suivante (historique
 *       conservé, aucun écrasement) ;</li>
 *   <li>le commentaire est nettoyé et reste une donnée NON FIABLE : jamais interprété comme instruction ;</li>
 *   <li>le conseiller n'est identifié que par un hash, et n'apparaît jamais dans les agrégats.</li>
 * </ul>
 */
@Service
public class AdvisorFeedbackService {
    private static final Logger log = LoggerFactory.getLogger(AdvisorFeedbackService.class);

    private final AdvisorFeedbackStore store;
    private final AdvisorFeedbackProperties properties;
    private final AnonymousIdService anonymousIdService;

    public AdvisorFeedbackService(AdvisorFeedbackStore store,
                                 AdvisorFeedbackProperties properties,
                                 AnonymousIdService anonymousIdService) {
        this.store = store;
        this.properties = properties;
        this.anonymousIdService = anonymousIdService;
    }

    /** Réponse API : jamais bloquante pour le dossier conseiller. */
    public record SubmitResponse(String status, String feedbackId, String sessionId, String event, Integer version,
                                 String overallAssessment, String message) {
    }

    /** Enregistre (ou révise) le feedback d'un dossier. */
    public SubmitResponse submit(AdvisorFeedbackModels.AdvisorFeedbackRequest request) {
        if (!properties.isEnabled()) {
            return new SubmitResponse("DISABLED", null, sessionOf(request), null, null, null,
                    "Module Feedback Conseiller désactivé.");
        }
        String sessionId = sessionOf(request);
        if (sessionId == null) {
            return new SubmitResponse("INVALID", null, null, null, null, null,
                    "sessionId manquant : feedback non enregistré.");
        }
        String assessment = AdvisorFeedbackModels.normalizeAssessment(
                request == null ? null : request.overallAssessment());
        if (assessment == null) {
            return new SubmitResponse("INVALID", null, sessionId, null, null, null,
                    "Évaluation globale manquante ou invalide (RELEVANT, NEEDS_IMPROVEMENT, INCORRECT).");
        }

        // Pseudonymisation : aucun nom de conseiller n'est stocké ni exposé dans les agrégats.
        String rawHash = anonymousIdService.anonymize(
                request.advisorId() == null || request.advisorId().isBlank() ? sessionId : request.advisorId());
        String advisorIdHash = rawHash == null ? null : rawHash.replace("customer_hash_", "advisor_hash_");
        String timestamp = Instant.now().toString();
        // Aucun détail n'est exigé pour un feedback positif ; les domaines sont normalisés et dédupliqués.
        List<AdvisorFeedbackModels.AdvisorIssue> issues = AdvisorFeedbackModels.NEEDS_IMPROVEMENT.equals(assessment)
                || AdvisorFeedbackModels.INCORRECT.equals(assessment)
                ? normaliseIssues(request.issues(), properties.commentMaxLength()) : List.of();
        List<AdvisorFeedbackModels.ProductFeedback> products =
                normaliseProducts(request.productFeedback(), properties.commentMaxLength());
        List<String> missing = request.missingProductIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        String nextAction = AdvisorFeedbackModels.normalizeAssessment(request.nextActionAssessment());
        String email = AdvisorFeedbackModels.normalizeEmailAssessment(request.clientEmailAssessment());
        String editLevel = AdvisorFeedbackModels.normalizeEditLevel(request.editLevel());

        AdvisorFeedbackModels.AdvisorFeedback current = store.latestBySession(sessionId).orElse(null);
        String signature = signature(assessment, issues, products, missing, nextAction, email, editLevel,
                com.coach.financier.model.QualityModels.sanitizeComment(request.comment(),
                        properties.commentMaxLength()));
        if (current != null && signature.equals(signatureOf(current))) {
            // Même session, même conseiller, contenu identique : double clic / retry → aucun nouvel événement.
            return new SubmitResponse("DUPLICATE", current.feedbackId(), sessionId, current.event(),
                    current.version(), current.overallAssessment(),
                    "Ce feedback a déjà été enregistré (aucun doublon créé).");
        }
        int version = current == null ? 1 : current.version() + 1;

        AdvisorFeedbackModels.AdvisorFeedback feedback = new AdvisorFeedbackModels.AdvisorFeedback(
                feedbackId(sessionId, advisorIdHash, version),
                timestamp,
                sessionId,
                advisorIdHash,
                assessment,
                issues,
                products,
                missing,
                nextAction,
                email,
                editLevel,
                com.coach.financier.model.QualityModels.sanitizeComment(request.comment(),
                        properties.commentMaxLength()),
                AdvisorFeedbackModels.SOURCE_ADVISOR,
                version == 1 ? AdvisorFeedbackModels.EVENT_CREATED : AdvisorFeedbackModels.EVENT_UPDATED,
                version,
                timestamp);

        AdvisorFeedbackStore.SaveResult result = store.save(request, feedback);
        if (result.duplicate()) {
            return new SubmitResponse("DUPLICATE", feedback.feedbackId(), sessionId, feedback.event(),
                    version, assessment, "Ce feedback a déjà été enregistré (aucun doublon créé).");
        }
        if (!result.saved()) {
            log.warn("Feedback conseiller non enregistré ({}) : {}", sessionId, result.error());
            return new SubmitResponse("STORAGE_ERROR", feedback.feedbackId(), sessionId, feedback.event(),
                    version, assessment, "Feedback non enregistré : le dossier conseiller n'est pas impacté.");
        }
        return new SubmitResponse("SAVED", feedback.feedbackId(), sessionId, feedback.event(), version,
                assessment, null);
    }

    /** Feedback courant d'un dossier (utilisé par l'IHM avant révision). */
    public java.util.Optional<AdvisorFeedbackModels.AdvisorFeedback> currentOf(String sessionId) {
        return store.latestBySession(sessionId);
    }

    /** Identifiant déterministe : même session + même conseiller + même version → même identifiant. */
    public static String feedbackId(String sessionId, String advisorIdHash, int version) {
        return "afb-" + UUID.nameUUIDFromBytes(
                (sessionId + "|" + advisorIdHash + "|" + version).getBytes(StandardCharsets.UTF_8));
    }

    /** Empreinte du CONTENU d'un feedback : sert à distinguer un double clic d'une vraie révision. */
    private static String signatureOf(AdvisorFeedbackModels.AdvisorFeedback feedback) {
        return signature(feedback.overallAssessment(), feedback.issues(), feedback.productFeedback(),
                feedback.missingProductIds(), feedback.nextActionAssessment(), feedback.clientEmailAssessment(),
                feedback.editLevel(), feedback.comment());
    }

    private static String signature(String assessment, List<AdvisorFeedbackModels.AdvisorIssue> issues,
                                    List<AdvisorFeedbackModels.ProductFeedback> products, List<String> missing,
                                    String nextAction, String email, String editLevel, String comment) {
        return String.join("|",
                String.valueOf(assessment),
                issues.stream().map(issue -> issue.area() + ":" + issue.reason() + ":" + issue.comment())
                        .sorted().toList().toString(),
                products.stream().map(product -> product.productId() + ":" + product.advisorAssessment() + ":"
                                + product.aiInterestLevel() + ":" + product.advisorInterestLevel() + ":"
                                + product.reason())
                        .sorted().toList().toString(),
                missing.stream().sorted().toList().toString(),
                String.valueOf(nextAction), String.valueOf(email), String.valueOf(editLevel),
                String.valueOf(comment));
    }

    private static String sessionOf(AdvisorFeedbackModels.AdvisorFeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return null;
        }
        return request.sessionId().trim();
    }

    private static List<AdvisorFeedbackModels.AdvisorIssue> normaliseIssues(
            List<AdvisorFeedbackModels.AdvisorIssue> issues, int commentMaxLength) {
        List<AdvisorFeedbackModels.AdvisorIssue> result = new ArrayList<>();
        for (AdvisorFeedbackModels.AdvisorIssue issue : issues == null ? List.<AdvisorFeedbackModels.AdvisorIssue>of()
                : issues) {
            if (issue == null || issue.area() == null || issue.area().isBlank()) {
                continue;
            }
            result.add(new AdvisorFeedbackModels.AdvisorIssue(
                    AdvisorFeedbackModels.normalizeArea(issue.area()),
                    issue.reason() == null || issue.reason().isBlank()
                            ? null : AdvisorFeedbackModels.normalizeReason(issue.reason()),
                    com.coach.financier.model.QualityModels.sanitizeComment(issue.comment(), commentMaxLength)));
        }
        return List.copyOf(result);
    }

    private static List<AdvisorFeedbackModels.ProductFeedback> normaliseProducts(
            List<AdvisorFeedbackModels.ProductFeedback> products, int commentMaxLength) {
        List<AdvisorFeedbackModels.ProductFeedback> result = new ArrayList<>();
        for (AdvisorFeedbackModels.ProductFeedback product : products == null
                ? List.<AdvisorFeedbackModels.ProductFeedback>of() : products) {
            if (product == null || product.productId() == null || product.productId().isBlank()) {
                continue;
            }
            String aiLevel = AdvisorFeedbackModels.normalizeInterestLevel(product.aiInterestLevel());
            String advisorLevel = AdvisorFeedbackModels.normalizeInterestLevel(product.advisorInterestLevel());
            String assessment = AdvisorFeedbackModels.normalizeProductAssessment(product.advisorAssessment());
            if (assessment == null && aiLevel != null && advisorLevel != null && !aiLevel.equals(advisorLevel)) {
                // Correction de niveau sans jugement explicite : on conserve la correction (§9).
                assessment = AdvisorFeedbackModels.PRODUCT_RELEVANT;
            }
            if (assessment == null && (advisorLevel == null || advisorLevel.equals(aiLevel))) {
                continue; // rien à enregistrer pour ce produit
            }
            result.add(new AdvisorFeedbackModels.ProductFeedback(
                    product.productId().trim(),
                    product.productName(),
                    aiLevel,
                    assessment,
                    advisorLevel,
                    product.reason() == null || product.reason().isBlank()
                            ? null : AdvisorFeedbackModels.normalizeProductReason(product.reason()),
                    com.coach.financier.model.QualityModels.sanitizeComment(product.comment(), commentMaxLength)));
        }
        return List.copyOf(result);
    }
}
