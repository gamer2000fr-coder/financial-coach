package com.coach.financier.service;

import com.coach.financier.config.AdvisorFeedbackProperties;
import com.coach.financier.model.AdvisorFeedbackModels;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Jeu de DÉMONSTRATION du module Feedback Conseiller : feedbacks synthétiques marqués
 * {@code source=DEMO} (jamais confondus avec de vrais retours de conseillers).
 * <p>
 * Identifiants et tirages DÉTERMINISTES (graine fixe) : rejouer la génération ne crée aucun doublon.
 * Les produits utilisés sont de VRAIS identifiants du catalogue (aucun produit inventé, §8).
 */
@Service
public class AdvisorFeedbackDemoDataService {
    private static final long SEED = 20260911L;
    private static final int SESSIONS_PER_DAY = 6;

    private final AdvisorFeedbackStore store;
    private final AdvisorFeedbackProperties properties;
    private final AdvisorFeedbackCandidatesService candidatesService;

    public AdvisorFeedbackDemoDataService(AdvisorFeedbackStore store,
                                          AdvisorFeedbackProperties properties,
                                          AdvisorFeedbackCandidatesService candidatesService) {
        this.store = store;
        this.properties = properties;
        this.candidatesService = candidatesService;
    }

    /** Résultat de la génération (compteurs réellement écrits). */
    public record DemoResult(String from, String to, int days, long written, String note) {
    }

    public DemoResult generate(int days, Integer evaluationsPerDay) {
        int effectiveDays = Math.min(Math.max(days, 1), 90);
        int perDay = Math.min(Math.max(evaluationsPerDay == null ? 4 : evaluationsPerDay, 1), SESSIONS_PER_DAY);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(effectiveDays - 1L);

        List<AdvisorFeedbackCandidatesService.CatalogueProduct> catalogue = candidatesService.catalogue();
        List<AdvisorFeedbackModels.AdvisorFeedback> feedback = new ArrayList<>();

        for (int dayIndex = 0; dayIndex < effectiveDays; dayIndex++) {
            LocalDate day = start.plusDays(dayIndex);
            Random random = new Random(SEED + dayIndex);
            for (int index = 0; index < perDay; index++) {
                String sessionId = "demo-af-" + day + "-" + index;
                String timestamp = day + "T" + String.format("%02d:%02d:00", 14 + (index % 4), (index * 11) % 60);
                String assessment = pickAssessment(random, dayIndex);
                List<AdvisorFeedbackModels.AdvisorIssue> issues = switch (assessment) {
                    case AdvisorFeedbackModels.INCORRECT -> List.of(
                            issue(AdvisorFeedbackModels.AREA_PROJECT_DETECTION, AdvisorFeedbackModels.REASON_WRONG,
                                    "Projet confondu avec une demande d'information."),
                            issue(AdvisorFeedbackModels.AREA_PRODUCT_RELEVANCE,
                                    AdvisorFeedbackModels.REASON_UNSUPPORTED_RECOMMENDATION,
                                    "Recommandation non étayée par les pièces disponibles."));
                    case AdvisorFeedbackModels.NEEDS_IMPROVEMENT -> List.of(
                            issue(index % 2 == 0 ? AdvisorFeedbackModels.AREA_INTEREST_LEVEL
                                            : AdvisorFeedbackModels.AREA_CLIENT_EMAIL,
                                    index % 2 == 0 ? AdvisorFeedbackModels.REASON_WRONG_INTEREST_LEVEL
                                            : AdvisorFeedbackModels.REASON_TOO_GENERIC,
                                    index % 2 == 0
                                            ? "Le client demandait uniquement des informations."
                                            : "Email trop générique, à personnaliser."));
                    default -> List.of();
                };

                List<AdvisorFeedbackModels.ProductFeedback> productFeedback =
                        productFeedback(random, catalogue, index, assessment);
                List<String> missing = !AdvisorFeedbackModels.RELEVANT.equals(assessment) && index % 3 == 0
                        && catalogue.size() > 2
                        ? List.of(catalogue.get((dayIndex + index + 1) % catalogue.size()).productId())
                        : List.of();

                String emailAssessment = switch (assessment) {
                    case AdvisorFeedbackModels.INCORRECT -> AdvisorFeedbackModels.EMAIL_MAJOR;
                    case AdvisorFeedbackModels.NEEDS_IMPROVEMENT -> index % 2 == 0
                            ? AdvisorFeedbackModels.EMAIL_MINOR : AdvisorFeedbackModels.EMAIL_MAJOR;
                    default -> random.nextInt(4) == 0 ? AdvisorFeedbackModels.EMAIL_MINOR
                            : AdvisorFeedbackModels.EMAIL_READY;
                };

                feedback.add(new AdvisorFeedbackModels.AdvisorFeedback(
                        "afb-" + UUID.nameUUIDFromBytes(
                                ("demo-advisor-feedback-" + sessionId).getBytes(StandardCharsets.UTF_8)),
                        timestamp,
                        sessionId,
                        "advisor_hash_demo_" + (index % 3),
                        assessment,
                        issues,
                        productFeedback,
                        missing,
                        AdvisorFeedbackModels.RELEVANT.equals(assessment)
                                ? AdvisorFeedbackModels.RELEVANT
                                : (index % 2 == 0 ? AdvisorFeedbackModels.RELEVANT
                                : AdvisorFeedbackModels.NEEDS_IMPROVEMENT),
                        emailAssessment,
                        AdvisorFeedbackModels.EMAIL_READY.equals(emailAssessment)
                                ? "NO_EDIT" : (AdvisorFeedbackModels.EMAIL_MINOR.equals(emailAssessment)
                                ? "MINOR_EDIT" : "MAJOR_EDIT"),
                        AdvisorFeedbackModels.RELEVANT.equals(assessment)
                                ? (random.nextInt(3) == 0 ? "Synthèse conforme au dossier." : "")
                                : "Bonne synthèse générale mais quelques éléments à reprendre.",
                        AdvisorFeedbackModels.SOURCE_DEMO,
                        AdvisorFeedbackModels.EVENT_CREATED,
                        1,
                        timestamp));
            }
        }

        List<AdvisorFeedbackModels.AdvisorFeedback> written = store.appendDemo(feedback);
        return new DemoResult(start.toString(), end.toString(), effectiveDays, written.size(),
                "Feedbacks de démonstration (source=DEMO) — ne pas interpréter comme de vrais retours de conseillers.");
    }

    private static String pickAssessment(Random random, int dayIndex) {
        int draw = random.nextInt(100);
        if (draw < 62) return AdvisorFeedbackModels.RELEVANT;
        if (draw < 90) return AdvisorFeedbackModels.NEEDS_IMPROVEMENT;
        return AdvisorFeedbackModels.INCORRECT;
    }

    private static AdvisorFeedbackModels.AdvisorIssue issue(String area, String reason, String comment) {
        return new AdvisorFeedbackModels.AdvisorIssue(area, reason, comment);
    }

    private static List<AdvisorFeedbackModels.ProductFeedback> productFeedback(
            Random random, List<AdvisorFeedbackCandidatesService.CatalogueProduct> catalogue, int index,
            String assessment) {
        if (catalogue.isEmpty()) {
            return List.of();
        }
        List<AdvisorFeedbackModels.ProductFeedback> result = new ArrayList<>();
        int count = Math.min(1 + random.nextInt(2), catalogue.size());
        for (int position = 0; position < count; position++) {
            AdvisorFeedbackCandidatesService.CatalogueProduct product =
                    catalogue.get((index + position) % catalogue.size());
            String aiLevel = random.nextInt(3) == 0 ? "MEDIUM" : "HIGH";
            String advisorLevel = aiLevel;
            String productAssessment = AdvisorFeedbackModels.PRODUCT_RELEVANT;
            String reason = null;
            if (!AdvisorFeedbackModels.RELEVANT.equals(assessment) && random.nextInt(3) == 0) {
                productAssessment = AdvisorFeedbackModels.PRODUCT_NOT_RELEVANT;
                reason = AdvisorFeedbackModels.PRODUCT_REASON_OVERESTIMATED;
                advisorLevel = "MEDIUM".equals(aiLevel) ? "LOW" : "MEDIUM";
            } else if (random.nextInt(4) == 0) {
                advisorLevel = "HIGH".equals(aiLevel) ? "MEDIUM" : "LOW";
            }
            result.add(new AdvisorFeedbackModels.ProductFeedback(product.productId(), product.productName(),
                    aiLevel, productAssessment, advisorLevel, reason,
                    AdvisorFeedbackModels.PRODUCT_NOT_RELEVANT.equals(productAssessment)
                            ? "Le client demandait uniquement des informations." : ""));
        }
        return List.copyOf(result);
    }
}
