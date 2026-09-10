package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProjectType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MockAIService implements AIService {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d{1,3}(?:[ .]\\d{3})*(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?)\\s*(€|eur|euros|euro)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Mode démo : classification d'intention déterministe par mots-clés.
     * (Le vrai classifieur enrichi est utilisé par les fournisseurs GPT/DeepSeek.)
     */
    @Override
    public IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                               AIModels.AIProvider provider) {
        String m = userMessage == null ? "" : userMessage.toLowerCase();
        if (containsAny(m, "vacances", "météo", "football", "coupe du monde", "recette", "programmation")) {
            return IntentClassification.outOfScope("La demande ne concerne pas les finances personnelles.");
        }
        IntentClassification c = new IntentClassification();
        c.setInScope(true);
        c.setConfidence(ConfidenceLevel.HIGH);

        Map<String, String> current = parseCurrentProject(currentProjectDescription);
        boolean refers = containsAny(m, "pour mon cas", "mon projet", "ce projet", "3 fois", "trois fois",
                "moitié", "moitié comptant", "celui-ci", "cette voiture", "le crédit", "financer",
                "finalement", "en fait");
        c.setRefersToCurrentProject(refers);

        if (containsAny(m, "crédit", "credit", "prêt", "pret", "financement", "en 3 fois", "plusieurs fois", "échéance")) {
            c.setIntent(FinancialIntent.FINANCING_REQUEST);
        } else if (containsAny(m, "permettre", "raisonnable", "puis-je", "puis je", "capacité", "peut-on", "affordable")) {
            c.setIntent(FinancialIntent.AFFORDABILITY_CHECK);
        } else if (containsAny(m, "moins cher", "comparer", "comparaison")) {
            c.setIntent(FinancialIntent.COMPARE_OPTIONS);
        } else if (containsAny(m, "finalement", "en fait", "plutôt", "en définitive")) {
            c.setIntent(FinancialIntent.PROJECT_UPDATE);
        } else if (containsAny(m, "assurance", "mutuelle", "assureur", "garantie")) {
            c.setIntent(FinancialIntent.PRODUCT_INFORMATION);
        } else if (containsAny(m, "acheter", "achat", "veux", "besoin", "veut")) {
            c.setIntent(FinancialIntent.PURCHASE);
        } else if (containsAny(m, "budget", "dépense", "depense", "dépenses")) {
            c.setIntent(FinancialIntent.BUDGET_ANALYSIS);
        } else if (containsAny(m, "épargne", "epargne", "livret", "épargner")) {
            c.setIntent(FinancialIntent.SAVINGS_ANALYSIS);
        } else {
            c.setIntent(FinancialIntent.OTHER);
        }

        String typeToken = detectProjectType(m);
        if (typeToken == null && (refers || needsCurrent(c.getIntent()))) {
            typeToken = current.get("type");
        }
        c.setProjectType(typeToken == null ? ProjectType.UNKNOWN : ProjectType.valueOf(typeToken));

        if (c.getProjectType() == ProjectType.UNKNOWN && !refers) {
            c.setConfidence(ConfidenceLevel.LOW);
        }

        String object = current.get("object");
        BigDecimal amount = extractAmount(m);
        if (amount == null) {
            String rawAmount = current.get("amount");
            if (rawAmount != null && !rawAmount.isBlank()) {
                try { amount = new BigDecimal(rawAmount.replace(" ", "").replace(',', '.')); } catch (Exception ignored) { amount = null; }
            }
        }
        if (amount != null) c.setAmount(amount);
        if (object != null && (c.getProjectObject() == null || refers)) c.setProjectObject(object);

        boolean hasNewInfo = amount != null && !containsAny(m, "mon cas", "mon projet");
        c.setProjectChanged(c.getProjectType() != ProjectType.UNKNOWN && (hasNewInfo || !refers || c.getIntent() == FinancialIntent.PROJECT_UPDATE));
        c.setReason("Classification démo (mots-clés).");
        return c;
    }

    private static boolean needsCurrent(FinancialIntent intent) {
        return intent == FinancialIntent.FINANCING_REQUEST || intent == FinancialIntent.AFFORDABILITY_CHECK
                || intent == FinancialIntent.COMPARE_OPTIONS || intent == FinancialIntent.PROJECT_UPDATE
                || intent == FinancialIntent.FOLLOW_UP || intent == FinancialIntent.CREDIT_INFORMATION;
    }

    private static Map<String, String> parseCurrentProject(String description) {
        Map<String, String> map = new HashMap<>();
        if (description == null) return map;
        for (String line : description.split("\n")) {
            int idx = line.indexOf('=');
            if (idx > 0) {
                map.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }
        }
        return map;
    }

    private static String detectProjectType(String m) {
        // Placé avant VEHICLE : « assurance auto » doit rester INSURANCE (sinon « auto » ferait VEHICLE).
        if (containsAny(m, "assurance", "mutuelle", "assureur", "garantie")) return "INSURANCE";
        if (containsAny(m, "clio", "tesla", "voiture", "véhicule", "vehicule", "auto ", "auto'", "moto", "utilitaire")) return "VEHICLE";
        if (containsAny(m, "maison", "appartement", "résidence", "immo", "acheter un logement")) return "REAL_ESTATE_PURCHASE";
        if (containsAny(m, "cuisine", "salle de bain", "rénovation", "renovation", "travaux", "isolation", "refaire")) return "HOME_WORK";
        if (containsAny(m, "ordinateur", "macbook", "pc gamer", "télévision", "television", "smartphone", "portable")) return "ELECTRONICS";
        if (containsAny(m, "canapé", "canape", "meuble", "lit", "ameublement")) return "FURNITURE";
        if (containsAny(m, "vacances", "voyage", "billet d'avion")) return "TRAVEL";
        if (containsAny(m, "formation", "études", "etudes", "école", "ecole", "scolarité")) return "EDUCATION";
        if (containsAny(m, "mariage", "noces")) return "WEDDING";
        if (containsAny(m, "soin", "dentiste", "opération", "operation", "santé")) return "HEALTH_EXPENSE";
        if (containsAny(m, "regroupement", "restructurer", "crédits")) return "DEBT_RESTRUCTURING";
        return null;
    }

    private static BigDecimal extractAmount(String message) {
        if (message == null) return null;
        Matcher matcher = AMOUNT_PATTERN.matcher(message);
        while (matcher.find()) {
            String number = matcher.group(1).replace(" ", "");
            boolean hasCurrency = matcher.group(2) != null;
            boolean big = number.replace(".", "").replace(",", "").length() >= 4;
            if (hasCurrency || big) {
                try {
                    return new BigDecimal(number.replace(",", "."));
                } catch (Exception ignored) {
                    // continue
                }
            }
        }
        return null;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    @Override
    public AIModels.AIAnswer answer(String customerMessage, AIModels.Classification classification,
                                    FinancialSummary financialSummary, Object bankingData,
                                    AIModels.BankingContextMode contextMode,
                                    Map<String, Object> additionalData,
                                    List<ConversationModels.Message> history, AIModels.AIProvider provider) {
        String answer = "Mode démo actif. Sur la base de vos données bancaires, votre revenu mensuel moyen est de "
                + money(financialSummary.averageMonthlyIncome()) + " € et vos dépenses mensuelles moyennes sont de "
                + money(financialSummary.averageMonthlyExpenses()) + " €. Votre solde courant est d'environ "
                + money(financialSummary.currentAccountBalance()) + " € et votre épargne de "
                + money(financialSummary.savingsBalance()) + " €.\n\n"
                + "Pour une réponse IA détaillée, configurez OPENAI_API_KEY ou DEEPSEEK_API_KEY et choisissez le fournisseur correspondant.";
        return new AIModels.AIAnswer(AIModels.AIStatus.ANSWER, answer, null, Map.of(), "Analyse financière calculée côté backend.", null);
    }

    private String money(double value) { return String.format(java.util.Locale.FRANCE, "%.2f", value); }
}
