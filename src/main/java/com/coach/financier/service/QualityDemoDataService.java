package com.coach.financier.service;

import com.coach.financier.config.QualityProperties;
import com.coach.financier.model.QualityModels;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Jeu de DÉMONSTRATION du module Qualité : feedbacks et contrôles synthétiques, clairement
 * marqués {@code source=DEMO} (aucune confusion possible avec de vrais retours clients).
 * <p>
 * Valeurs et identifiants DÉTERMINISTES (graine fixe, identifiants construits à partir du jour et de
 * l'index) : rejouer la génération ne crée jamais de doublon grâce à l'idempotence des stores.
 * <p>
 * Le jeu reproduit volontairement les cas intéressants du croisement satisfaction × conformité :
 * des clients insatisfaits alors que le Coach est CONFORME (chiffrage de crédit attendu mais non réalisé
 * alors que la grille de taux était disponible) et quelques anomalies réelles côté Coach.
 */
@Service
public class QualityDemoDataService {
    private static final long SEED = 20260911L;
    private static final int CONVERSATIONS_PER_DAY = 8;

    private final QualityFeedbackStore feedbackStore;
    private final QualityCheckStore checkStore;
    private final QualityProperties properties;
    private final CoachQualityCheckService checkService;

    public QualityDemoDataService(QualityFeedbackStore feedbackStore,
                                  QualityCheckStore checkStore,
                                  QualityProperties properties,
                                  CoachQualityCheckService checkService) {
        this.feedbackStore = feedbackStore;
        this.checkStore = checkStore;
        this.properties = properties;
        this.checkService = checkService;
    }

    /** Résultat de génération (compteurs réellement écrits). */
    public record DemoResult(String from, String to, int days, long feedbackWritten, long checksWritten, String note) {
    }

    /** Génère {@code days} jours de feedbacks et de contrôles de démonstration. */
    public DemoResult generate(int days, Integer ratingCountPerDay) {
        int effectiveDays = Math.min(Math.max(days, 1), 90);
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(effectiveDays - 1L);
        int reviews = Math.min(Math.max(ratingCountPerDay == null ? 5 : ratingCountPerDay, 0), 20);

        List<QualityModels.CoachFeedback> feedback = new ArrayList<>();
        List<QualityModels.QualityCheck> checks = new ArrayList<>();

        for (int dayIndex = 0; dayIndex < effectiveDays; dayIndex++) {
            LocalDate day = start.plusDays(dayIndex);
            java.util.Random random = new java.util.Random(SEED + dayIndex);
            for (int index = 0; index < CONVERSATIONS_PER_DAY; index++) {
                String sessionId = "demo-" + day + "-" + index;
                String timestamp = day + "T" + String.format("%02d:%02d:00", 9 + (index % 8), (index * 7) % 60);

                // 1) Contrôles automatiques de la session (une anomalie crédit réelle de temps en temps).
                boolean realCreditViolation = (index == 3 && dayIndex % 7 == 3);
                boolean productMismatch = (index == 5 && dayIndex % 5 == 2);
                boolean repetition = index % 4 == 1;
                for (String checkType : properties.enabledChecks()) {
                    boolean detected = switch (checkType) {
                        case QualityModels.CREDIT_SIMULATION_VIOLATION -> realCreditViolation;
                        case QualityModels.PRODUCT_MISMATCH -> productMismatch;
                        case QualityModels.EXCESSIVE_REPETITION -> repetition;
                        case QualityModels.UNANSWERED_REQUEST -> index == 7 && dayIndex % 6 == 1;
                        case QualityModels.MISSING_DATA_NOT_RETRIEVED -> index == 6 && dayIndex % 4 == 3;
                        default -> false;
                    };
                    checks.add(new QualityModels.QualityCheck(
                            "chk-" + sessionId + "-" + checkType,
                            timestamp,
                            sessionId,
                            checkType,
                            properties.severityFor(checkType),
                            detected,
                            QualityModels.SOURCE_DEMO,
                            detected ? detail(checkType) : "Aucune anomalie détectée (démonstration).",
                            detected ? 0.8 : null));
                }

                // 2) Feedback client sur une partie des sessions (pas toutes : taux de participation < 100 %).
                if (index >= reviews) {
                    continue;
                }
                int rating = pickRating(random);
                List<String> reasons = rating <= 2
                        ? pickReasons(random, index)
                        : (rating == 3 && random.nextInt(3) == 0 ? List.of(QualityModels.MISSING_INFORMATION) : List.of());
                String comment = rating <= 2
                        ? negativeComment(index, realCreditViolation)
                        : (rating >= 4 && random.nextInt(3) == 0
                                ? "Échanges utiles et clairs, merci." : "");
                feedback.add(new QualityModels.CoachFeedback(
                        "fb-" + UUID.nameUUIDFromBytes(("demo-feedback-" + sessionId).getBytes(StandardCharsets.UTF_8)),
                        timestamp,
                        sessionId,
                        "customer_hash_demo_" + (index % 5),
                        rating,
                        reasons,
                        List.of(),
                        null, null, null,
                        comment,
                        QualityModels.SOURCE_DEMO,
                        timestamp));
            }
        }

        List<QualityModels.CoachFeedback> writtenFeedback = feedbackStore.appendDemo(feedback);
        checkStore.append(checks);
        return new DemoResult(start.toString(), end.toString(), effectiveDays, writtenFeedback.size(),
                checks.size(), "Données de démonstration (source=DEMO) — ne pas interpréter comme de vrais avis.");
    }

    private static int pickRating(java.util.Random random) {
        int draw = random.nextInt(100);
        if (draw < 52) return 5;
        if (draw < 78) return 4;
        if (draw < 90) return 3;
        if (draw < 96) return 2;
        return 1;
    }

    private static List<String> pickReasons(java.util.Random random, int index) {
        List<String> pool = List.of(QualityModels.TOO_REPETITIVE, QualityModels.MISSING_INFORMATION,
                QualityModels.TOO_LONG, QualityModels.ACTION_NOT_POSSIBLE, QualityModels.HARD_TO_UNDERSTAND,
                QualityModels.PRODUCT_NOT_RELEVANT);
        List<String> reasons = new ArrayList<>();
        reasons.add(pool.get(index % pool.size()));
        if (random.nextInt(2) == 0) {
            String second = pool.get((index + 3) % pool.size());
            if (!reasons.contains(second)) {
                reasons.add(second);
            }
        }
        return reasons;
    }

    private static String negativeComment(int index, boolean realCreditViolation) {
        if (realCreditViolation) {
            return "Le Coach m'a annoncé une mensualité sans référence de taux, impossible à vérifier.";
        }
        return switch (index % 4) {
            case 0 -> "Le Coach répétait les mêmes chiffres à chaque message.";
            case 1 -> "Je voulais une estimation chiffrée, le Coach m'a seulement renvoyé vers un simulateur.";
            case 2 -> "Les explications étaient longues et difficiles à suivre.";
            default -> "Il manquait des informations sur l'offre proposée.";
        };
    }

    private static String detail(String checkType) {
        return switch (checkType) {
            case QualityModels.CREDIT_SIMULATION_VIOLATION ->
                    "Le Coach semble avoir produit un chiffrage de crédit sans s'appuyer sur la grille de taux "
                            + "fournie (anomalie de démonstration).";
            case QualityModels.PRODUCT_MISMATCH ->
                    "Une offre hors familles autorisées a été présentée (anomalie de démonstration).";
            case QualityModels.EXCESSIVE_REPETITION ->
                    "Plusieurs informations ont été répétées sans nécessité apparente (démonstration).";
            case QualityModels.UNANSWERED_REQUEST ->
                    "La conversation se termine sur un message client sans réponse (démonstration).";
            case QualityModels.MISSING_DATA_NOT_RETRIEVED ->
                    "Données demandées par l'IA et non fournies (démonstration).";
            default -> "Anomalie de démonstration.";
        };
    }

    /** Contrôles implémentés (rappel pour l'IHM / la documentation). */
    public List<String> implementedChecks() {
        return checkService.implementedChecks();
    }

    /** Horodatage courant (utile pour les tests de génération). */
    static String now() {
        return Instant.now().toString();
    }
}
