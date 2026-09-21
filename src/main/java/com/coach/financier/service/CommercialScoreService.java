package com.coach.financier.service;

import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.SuiviModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SCORE DE SENS COMMERCIAL attribué à la clôture d'une conversation.
 * <p>
 * Objectif métier : donner au conseiller (et au centre d'appels) un ordre de PRIORISATION des relances —
 * « affaire mûre, urgente, client intéressé » passe avant « projet exploratoire ». Le score est accompagné
 * d'une explication courte (raisons) et de la trace des critères mesurés.
 * <p>
 * Répartition des points (total 100) :
 * <ul>
 *   <li><b>Maturité du projet</b> (30) : projet identifié, objet/montant connus, offre(s) réellement
 *       présentée(s) pendant l'échange ;</li>
 *   <li><b>Intérêt du client</b> (25) : meilleur niveau d'intérêt observé sur les offres évoquées ;</li>
 *   <li><b>Urgence</b> (20) : contrainte de temps exprimée par le client (lue par l'IA, sinon détectée par
 *       mots-clés) ;</li>
 *   <li><b>Capacité de financement</b> (15) : indicateurs RÉELLEMENT disponibles dans la fiche client ;</li>
 *   <li><b>Engagement</b> (10) : nombre de messages du client (implication dans l'échange).</li>
 * </ul>
 * Pénalités : chaque offre explicitement ÉCARTÉE par le client retire 8 points (plafonné à -16).
 * <p>
 * Le score PROPOSÉ par l'IA de synthèse (si elle en propose un) est conservé — c'est elle qui lit le mieux
 * la conversation — mais il est BORNÉ à 0..100 par le backend, et les critères mesurés sont TOUJOURS
 * recalculés pour rester traçables. Aucun seuil bancaire n'est introduit : l'appréciation « capacité » se
 * contente de décrire les indicateurs présents.
 */
@Service
public class CommercialScoreService {
    private static final Logger log = LoggerFactory.getLogger(CommercialScoreService.class);

    private static final int MAX_MATURITY = 30;
    private static final int MAX_INTEREST = 25;
    private static final int MAX_URGENCY = 20;
    private static final int MAX_CAPACITY = 15;
    private static final int MAX_ENGAGEMENT = 10;
    private static final int REJECTED_PENALTY = 8;
    private static final int MAX_PENALTY = 16;
    private static final int MAX_REASONS = 3;

    /** Marqueurs d'urgence cherchés dans les messages du CLIENT (texte normalisé, sans accents). */
    private static final List<String> URGENCY_KEYWORDS = List.of(
            "urgent", "urgence", "rapidement", "au plus vite", "des que possible", "le plus tot possible",
            "avant la fin", "avant le", "avant fevrier", "ce mois", "semaine prochaine", "brefs delais",
            "vite", "aujourd'hui", "des demain", "fin du mois");

    private final FinancialAnalysisService financialAnalysisService;

    public CommercialScoreService(FinancialAnalysisService financialAnalysisService) {
        this.financialAnalysisService = financialAnalysisService;
    }

    /**
     * Construit le score final à partir de la conversation, du dossier préparé par l'IA et des offres
     * réellement présentées au client.
     *
     * @param conversation      conversation clôturée (projets, messages, synthèse financière éventuelle)
     * @param result            sortie de l'agent de suivi (dont la proposition de score de l'IA)
     * @param presentedProducts offres réellement présentées ({@code {id, name, category...}})
     */
    public SuiviModels.CommercialScore scoreFor(ConversationModels.Conversation conversation,
                                                SuiviModels.SuiviResult result,
                                                List<Map<String, Object>> presentedProducts) {
        List<CurrentProject> projects = conversation == null ? List.of() : conversation.projects();
        List<ConversationModels.Message> messages = conversation == null ? List.of() : conversation.transcript();
        List<SuiviModels.ProductOfInterest> interests = result == null || result.productsOfInterest() == null
                ? List.of() : result.productsOfInterest();
        SuiviModels.AiScoreProposal proposal = result == null ? null : result.commercialScore();

        List<SuiviModels.ScoreCriterion> criteria = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // 1) Maturité du projet.
        CurrentProject project = firstIdentifiedProject(projects);
        int maturity = 0;
        List<String> maturityDetails = new ArrayList<>();
        if (project != null) {
            maturity += 12;
            maturityDetails.add(project.getType() == null ? "projet identifié" : project.getType().name());
        }
        boolean quantified = hasObjectOrAmount(project);
        if (quantified) {
            maturity += 8;
            maturityDetails.add(amountDetail(project));
        }
        if (presentedProducts != null && !presentedProducts.isEmpty()) {
            maturity += 10;
            maturityDetails.add(presentedProducts.size() + " offre(s) présentée(s)");
        }
        maturity = Math.min(MAX_MATURITY, maturity);
        criteria.add(new SuiviModels.ScoreCriterion("MATURITY", "Maturité du projet", maturity, MAX_MATURITY,
                String.join(", ", maturityDetails)));
        if (maturity >= 20) {
            reasons.add("Projet cadré" + (quantified ? " et chiffré (" + amountDetail(project) + ")" : "")
                    + " avec " + (presentedProducts == null ? 0 : presentedProducts.size())
                    + " offre(s) présentée(s) au client.");
        }

        // 2) Intérêt du client (meilleur niveau observé).
        SuiviModels.InterestLevel best = SuiviModels.InterestLevel.LOW;
        List<String> interestedNames = new ArrayList<>();
        for (SuiviModels.ProductOfInterest interest : interests) {
            SuiviModels.InterestLevel level = SuiviModels.InterestLevel.parse(interest.interestLevel());
            if (level == SuiviModels.InterestLevel.HIGH) {
                interestedNames.add(safe(interest.name()));
            }
            if (rank(level) > rank(best)) {
                best = level;
            }
        }
        int interestPoints = switch (best) {
            case HIGH -> MAX_INTEREST;
            case MEDIUM -> 15;
            case LOW -> 5;
            case REJECTED -> 0;
        };
        criteria.add(new SuiviModels.ScoreCriterion("INTEREST", "Intérêt du client", interestPoints, MAX_INTEREST,
                best == SuiviModels.InterestLevel.HIGH && !interestedNames.isEmpty()
                        ? "intérêt fort : " + String.join(", ", interestedNames)
                        : "niveau observé : " + best.name()));
        if (best == SuiviModels.InterestLevel.HIGH) {
            reasons.add("Client intéressé par l'offre recommandée"
                    + (interestedNames.isEmpty() ? "" : " (" + String.join(", ", interestedNames) + ")") + ".");
        }

        // 3) Urgence : lecture de l'IA si elle en donne une, sinon mots-clés du client.
        String urgencySignal = proposal == null ? "" : safe(proposal.urgency()).toUpperCase(Locale.ROOT);
        String matchedKeyword = "";
        if (urgencySignal.isBlank()) {
            matchedKeyword = findUrgencyKeyword(messages);
            urgencySignal = matchedKeyword.isBlank() ? "NONE" : "HIGH";
        }
        int urgencyPoints = switch (urgencySignal) {
            case "HIGH" -> MAX_URGENCY;
            case "MEDIUM" -> 12;
            case "LOW" -> 5;
            default -> 0;
        };
        criteria.add(new SuiviModels.ScoreCriterion("URGENCY", "Urgence exprimée", urgencyPoints, MAX_URGENCY,
                urgencyPoints == 0 ? "aucune contrainte de temps exprimée"
                        : (urgencySignal + (matchedKeyword.isBlank() ? "" : " (« " + matchedKeyword + " »)"))));
        if (urgencyPoints >= 12) {
            reasons.add("Contrainte de temps exprimée par le client : relance à traiter en priorité.");
        }

        // 4) Capacité de financement, uniquement à partir des indicateurs disponibles.
        FinancialSummary summary = conversation != null && conversation.financialSummary() != null
                ? conversation.financialSummary() : safeAnalyze();
        int capacityPoints = 7;
        String capacityDetail = "indicateurs financiers indisponibles";
        if (summary != null) {
            double ratio = summary.debtServiceToIncomeRatio();
            double savings = summary.averageMonthlySavings();
            String ratioText = "endettement " + percent(ratio) + ", épargne mensuelle " + euros(savings);
            if (ratio <= 0) {
                capacityPoints = 7;
                capacityDetail = ratioText;
            } else if (ratio < 0.33 && savings > 0) {
                capacityPoints = MAX_CAPACITY;
                capacityDetail = ratioText + " : marge confortable";
            } else if (ratio < 0.40) {
                capacityPoints = 9;
                capacityDetail = ratioText + " : marge correcte";
            } else {
                capacityPoints = 4;
                capacityDetail = ratioText + " : marge limitée";
            }
            if (summary.overdraftOccurrences() > 0) {
                capacityPoints = Math.max(0, capacityPoints - 3);
                capacityDetail = capacityDetail + ", " + summary.overdraftOccurrences() + " découvert(s)"
                        + " : vigilance";
            }
        }
        criteria.add(new SuiviModels.ScoreCriterion("CAPACITY", "Capacité de financement", capacityPoints,
                MAX_CAPACITY, capacityDetail));
        if (capacityPoints >= MAX_CAPACITY) {
            reasons.add("Capacité de remboursement confortable d'après les indicateurs disponibles.");
        } else if (capacityPoints <= 4 && summary != null) {
            reasons.add("Capacité de remboursement limitée : dossier à accompagner (apport, durée, garanties).");
        }

        // 5) Engagement : implication réelle du client dans l'échange.
        long clientMessages = messages.stream().filter(message -> "user".equalsIgnoreCase(safe(message.role())))
                .count();
        int engagementPoints = clientMessages >= 5 ? MAX_ENGAGEMENT
                : clientMessages >= 3 ? 7
                : clientMessages >= 2 ? 4 : 0;
        criteria.add(new SuiviModels.ScoreCriterion("ENGAGEMENT", "Engagement du client", engagementPoints,
                MAX_ENGAGEMENT, clientMessages + " message(s) client"));
        if (engagementPoints >= 7) {
            reasons.add("Échange approfondi (" + clientMessages + " messages client) : le besoin est précisé.");
        }

        // 6) Pénalités : offres explicitement écartées.
        long rejected = interests.stream()
                .filter(interest -> SuiviModels.InterestLevel.parse(interest.interestLevel())
                        == SuiviModels.InterestLevel.REJECTED)
                .count();
        int penalty = (int) Math.min(MAX_PENALTY, rejected * REJECTED_PENALTY);
        if (penalty > 0) {
            reasons.add("Offre(s) écartée(s) par le client (" + rejected + ") : relance à réorienter.");
        }

        int computed = Math.max(0, Math.min(100,
                maturity + interestPoints + urgencyPoints + capacityPoints + engagementPoints - penalty));

        boolean proposedByAi = proposal != null && proposal.score() != null;
        int finalScore = computed;
        if (proposedByAi) {
            finalScore = Math.max(0, Math.min(100, proposal.score()));
            List<String> aiReasons = limit(proposal.reasons(), MAX_REASONS);
            if (!aiReasons.isEmpty()) {
                reasons = new ArrayList<>(aiReasons);
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("Aucun signal fort dans cet échange : dossier à reprendre depuis le début.");
        }
        reasons = limit(reasons, MAX_REASONS);

        SuiviModels.CommercialScore score = new SuiviModels.CommercialScore(finalScore, priorityOf(finalScore),
                labelOf(finalScore), reasons, criteria, null, proposedByAi);
        log.debug("Score commercial {} ({}), proposé par l'IA : {}", score.score(), score.label(), proposedByAi);
        return score;
    }

    /** Priorité commerciale associée au score (badge + tri de l'annuaire). */
    public static String priorityOf(int score) {
        if (score >= 80) return SuiviModels.PRIORITY_VERY_HIGH;
        if (score >= 65) return SuiviModels.PRIORITY_HIGH;
        if (score >= 45) return SuiviModels.PRIORITY_MEDIUM;
        return SuiviModels.PRIORITY_LOW;
    }

    /** Libellé lisible de la priorité, affiché au conseiller. */
    public static String labelOf(int score) {
        return switch (priorityOf(score)) {
            case SuiviModels.PRIORITY_VERY_HIGH -> "Priorité très haute — affaire mûre";
            case SuiviModels.PRIORITY_HIGH -> "Priorité haute";
            case SuiviModels.PRIORITY_MEDIUM -> "Priorité moyenne";
            default -> "Priorité faible — à recontacter plus tard";
        };
    }

    /** Libellé d'une priorité à partir de son code (IHM, exports). */
    public static String labelOfPriority(String priority) {
        if (priority == null) return labelOf(0);
        return switch (priority) {
            case SuiviModels.PRIORITY_VERY_HIGH -> "Priorité très haute — affaire mûre";
            case SuiviModels.PRIORITY_HIGH -> "Priorité haute";
            case SuiviModels.PRIORITY_MEDIUM -> "Priorité moyenne";
            default -> "Priorité faible — à recontacter plus tard";
        };
    }

    /** Premier projet réellement identifié (type connu, différent de {@code UNKNOWN}). */
    private static CurrentProject firstIdentifiedProject(List<CurrentProject> projects) {
        for (CurrentProject project : projects) {
            if (project != null && project.getType() != null
                    && project.getType() != com.coach.financier.model.ProjectType.UNKNOWN) {
                return project;
            }
        }
        return null;
    }

    private static boolean hasObjectOrAmount(CurrentProject project) {
        if (project == null) {
            return false;
        }
        boolean hasObject = project.getObject() != null && !project.getObject().isBlank();
        boolean hasAmount = project.getAmount() != null
                && project.getAmount().signum() > 0;
        return hasObject || hasAmount;
    }

    private static String amountDetail(CurrentProject project) {
        if (project == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (project.getObject() != null && !project.getObject().isBlank()) {
            parts.add(abbreviate(project.getObject().trim()));
        }
        if (project.getAmount() != null && project.getAmount().signum() > 0) {
            parts.add(euros(project.getAmount().doubleValue()) + " " + safe(project.getCurrency()));
        }
        return String.join(", ", parts);
    }

    /** Mot-clé d'urgence trouvé dans les messages du client (texte normalisé). */
    private static String findUrgencyKeyword(List<ConversationModels.Message> messages) {
        for (ConversationModels.Message message : messages) {
            if (!"user".equalsIgnoreCase(safe(message.role()))) {
                continue;
            }
            String text = normalize(message.content());
            for (String keyword : URGENCY_KEYWORDS) {
                if (text.contains(normalize(keyword))) {
                    return keyword;
                }
            }
        }
        return "";
    }

    private FinancialSummary safeAnalyze() {
        try {
            return financialAnalysisService.analyze();
        } catch (Exception e) {
            log.debug("Synthèse financière indisponible pour le score commercial : {}", e.getMessage());
            return null;
        }
    }

    private static int rank(SuiviModels.InterestLevel level) {
        return switch (level) {
            case HIGH -> 3;
            case MEDIUM -> 2;
            case LOW -> 1;
            case REJECTED -> 0;
        };
    }

    private static List<String> limit(List<String> values, int max) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !result.contains(value.trim())) {
                result.add(value.trim());
            }
            if (result.size() >= max) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static String percent(double ratio) {
        return String.format(Locale.FRANCE, "%.0f %%", ratio * 100);
    }

    private static String euros(double amount) {
        return String.format(Locale.FRANCE, "%.0f €", amount);
    }

    private static String abbreviate(String value) {
        return value.length() <= 60 ? value : value.substring(0, 57) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    /** Critères mesurés d'un score, indexés par code (IHM, tests). */
    public static Map<String, Integer> pointsByCode(SuiviModels.CommercialScore score) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (score != null) {
            for (SuiviModels.ScoreCriterion criterion : score.criteria()) {
                result.put(criterion.code(), criterion.points());
            }
        }
        return result;
    }
}
