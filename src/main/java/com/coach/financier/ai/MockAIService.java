package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.SuiviModels;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

    /**
     * Mode démo (aucun appel LLM) : synthèse DÉTERMINISTE construite uniquement à partir du
     * contexte fourni. Elle respecte les mêmes règles que l'agent réel : pas d'invention,
     * produits refusés exclus, brouillon client jamais présenté comme déjà envoyé.
     */
    @Override
    public SuiviModels.SuiviResult summarizeConversation(Map<String, Object> context, AIModels.AIProvider provider) {
        Map<String, Object> root = asMap(context);
        List<Map<String, Object>> history = asListOfMaps(root.get("conversationHistory"));
        List<Map<String, Object>> products = asListOfMaps(root.get("products"));
        Map<String, Object> customerContext = asMap(root.get("customerContext"));
        Map<String, Object> advisorContext = asMap(root.get("advisorContext"));
        Map<String, Object> usefulUrls = asMap(root.get("usefulUrls"));
        List<Map<String, Object>> projects = asListOfMaps(customerContext.get("currentProjects"));

        List<String> userTexts = new ArrayList<>();
        List<String> assistantTexts = new ArrayList<>();
        for (Map<String, Object> message : history) {
            String content = str(message.get("content"));
            if (content == null) continue;
            if ("user".equalsIgnoreCase(str(message.get("role")))) userTexts.add(content);
            else assistantTexts.add(content);
        }

        String mainProject = "Projet non précisé";
        List<String> otherProjects = new ArrayList<>();
        if (!projects.isEmpty()) {
            mainProject = describeProject(projects.get(0));
            for (int i = 1; i < projects.size(); i++) {
                otherProjects.add(describeProject(projects.get(i)));
            }
        }

        List<String> preferences = new ArrayList<>();
        String normalizedUser = normalize(String.join(" \n ", userTexts));
        if (normalizedUser.contains("epargne") || normalizedUser.contains("preserver")) {
            preferences.add("Souhaite préserver une partie de son épargne.");
        }
        if (normalizedUser.contains("apport") || normalizedUser.contains("comptant")) {
            preferences.add("Semble ouvert à un financement partiel (apport, paiement comptant).");
        }
        if (normalizedUser.contains("mensualite") || normalizedUser.contains("par mois")) {
            preferences.add("Attention portée au montant de la mensualité.");
        }

        List<SuiviModels.ProductOfInterest> interests = new ArrayList<>();
        for (Map<String, Object> product : products) {
            String id = str(product.get("id"));
            String name = str(product.get("name"));
            if (name == null || name.isBlank()) continue;
            String category = str(product.get("family"));
            String url = str(product.get("productUrl"));
            boolean rejected = userTexts.stream()
                    .anyMatch(text -> mentions(text, name, id) && isRejection(text));
            boolean highUser = userTexts.stream().anyMatch(text -> mentions(text, name, id));
            boolean mediumAssistant = assistantTexts.stream().anyMatch(text -> mentions(text, name, id));
            String level;
            String reason;
            if (rejected) {
                level = "REJECTED";
                reason = "Le client a explicitement écarté cette offre.";
            } else if (highUser) {
                level = "HIGH";
                reason = "Le client a demandé des précisions, comparé ou montré un intérêt explicite.";
            } else if (mediumAssistant) {
                level = "MEDIUM";
                reason = "Offre recommandée par le coach, cohérente avec le projet.";
            } else {
                level = "LOW";
                reason = "Offre chargée dans le contexte mais non discutée avec le client.";
            }
            interests.add(new SuiviModels.ProductOfInterest(id, name, category, level, reason, url));
        }

        String customerName = str(customerContext.get("customerName"));
        String customerReference = str(customerContext.get("customerReference"));
        String advisorName = str(advisorContext.get("advisorName"));
        String appointmentUrl = str(usefulUrls.get("advisorAppointment"));

        String advisorEmail = buildAdvisorEmail(mainProject, otherProjects, preferences, interests,
                customerName, customerReference, advisorName);
        String customerEmail = buildCustomerEmail(mainProject, interests, customerName, advisorName, appointmentUrl);

        return new SuiviModels.SuiviResult(
                new SuiviModels.ConversationSummary(mainProject, otherProjects, preferences),
                interests,
                new SuiviModels.EmailContent("Suivi client — " + mainProject, advisorEmail),
                new SuiviModels.EmailContent("Votre projet : " + mainProject, customerEmail),
                marketingDrafts(projects, interests));
    }

    /**
     * Signaux Marketing déterministes (mode démo, §6) : projet détecté, produits recommandés,
     * intérêts (HIGH/MEDIUM) et refus. Les identifiants techniques et la tranche de montant sont
     * ajoutés ensuite par le backend. Aucune donnée personnelle.
     */
    private static List<com.coach.financier.model.MarketingModels.MarketingEventDraft> marketingDrafts(
            List<Map<String, Object>> projects, List<SuiviModels.ProductOfInterest> interests) {
        List<com.coach.financier.model.MarketingModels.MarketingEventDraft> drafts = new ArrayList<>();
        for (Map<String, Object> project : projects) {
            String type = str(project.get("type"));
            if (type != null && !type.isBlank()) {
                drafts.add(new com.coach.financier.model.MarketingModels.MarketingEventDraft(
                        com.coach.financier.model.MarketingModels.PROJECT_DETECTED,
                        type, null, null, null, null, null, null, null, null, 0.9));
            }
        }
        for (SuiviModels.ProductOfInterest interest : interests) {
            String level = interest.interestLevel();
            com.coach.financier.model.MarketingModels.MarketingEventDraft recommended =
                    new com.coach.financier.model.MarketingModels.MarketingEventDraft(
                            com.coach.financier.model.MarketingModels.PRODUCT_RECOMMENDED,
                            null, null, interest.productId(), interest.name(), interest.category(),
                            null, null, "Offre présentée par le coach.", null, 0.8);
            drafts.add(recommended);
            if ("REJECTED".equals(level)) {
                drafts.add(new com.coach.financier.model.MarketingModels.MarketingEventDraft(
                        com.coach.financier.model.MarketingModels.PRODUCT_REJECTED,
                        null, null, interest.productId(), interest.name(), interest.category(),
                        null, "OTHER", interest.interestReason(), null, 0.8));
            } else if ("HIGH".equals(level) || "MEDIUM".equals(level)) {
                drafts.add(new com.coach.financier.model.MarketingModels.MarketingEventDraft(
                        com.coach.financier.model.MarketingModels.PRODUCT_INTEREST,
                        null, null, interest.productId(), interest.name(), interest.category(),
                        level, "DETAIL_REQUEST", interest.interestReason(), true, 0.85));
            }
        }
        return drafts;
    }

    private String buildAdvisorEmail(String mainProject, List<String> otherProjects, List<String> preferences,
                                     List<SuiviModels.ProductOfInterest> interests, String customerName,
                                     String customerReference, String advisorName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bonjour").append(advisorName == null ? "" : " " + advisorName).append(",\n\n");
        sb.append("Voici la synthèse du suivi client");
        if (customerName != null && !customerName.isBlank()) {
            sb.append(" ").append(customerName);
        }
        if (customerReference != null && !customerReference.isBlank()) {
            sb.append(" (réf. ").append(customerReference).append(")");
        }
        sb.append(".\n\n");
        sb.append("Projet du client\n").append(mainProject).append("\n\n");
        if (!otherProjects.isEmpty()) {
            sb.append("Autres sujets évoqués\n");
            for (String project : otherProjects) sb.append("- ").append(project).append('\n');
            sb.append('\n');
        }
        if (!preferences.isEmpty()) {
            sb.append("Préférences importantes\n");
            for (String preference : preferences) sb.append("- ").append(preference).append('\n');
            sb.append('\n');
        }
        sb.append("Produits / offres d'intérêt\n");
        boolean any = false;
        for (SuiviModels.ProductOfInterest interest : interests) {
            if ("LOW".equals(interest.interestLevel()) || "REJECTED".equals(interest.interestLevel())) continue;
            any = true;
            sb.append("- ").append(interest.name());
            if (interest.category() != null) sb.append(" (").append(interest.category()).append(')');
            sb.append(" — intérêt ").append(interest.interestLevel()).append(" : ").append(interest.interestReason());
            if (interest.productUrl() != null) {
                sb.append(" [URL|Voir l'offre|").append(interest.productUrl()).append(']');
            }
            sb.append('\n');
        }
        if (!any) sb.append("- Aucun produit n'a été clairement demandé ; voir les échanges.\n");
        sb.append('\n');
        List<String> rejected = interests.stream()
                .filter(i -> "REJECTED".equals(i.interestLevel())).map(SuiviModels.ProductOfInterest::name).toList();
        if (!rejected.isEmpty()) {
            sb.append("Points restant à confirmer\n- Le client a écarté : ")
                    .append(String.join(", ", rejected)).append(". Ne pas reproposer sans échange.\n\n");
        }
        sb.append("Suivi conseillé\n- Reprendre contact avec le client pour confirmer son besoin et vérifier l'éligibilité, ")
                .append("puis réaliser un devis ou une simulation sur les outils officiels.\n\n");
        sb.append("Vous trouverez en pièce jointe un brouillon d'email préparé à destination du client, basé sur les ")
                .append("besoins et centres d'intérêt identifiés pendant l'échange. Merci de le vérifier et de l'adapter ")
                .append("si nécessaire avant tout envoi. Le contenu n'a pas encore été envoyé au client.");
        return sb.toString();
    }

    private String buildCustomerEmail(String mainProject, List<SuiviModels.ProductOfInterest> interests,
                                      String customerName, String advisorName, String appointmentUrl) {
        StringBuilder sb = new StringBuilder();
        String firstName = null;
        if (customerName != null && !customerName.isBlank()) {
            firstName = customerName.trim().split("\\s+")[0];
        }
        sb.append("Bonjour").append(firstName == null ? "" : " " + firstName).append(",\n\n");
        sb.append("Suite à votre échange avec notre Coach Financier au sujet de ").append(mainProject)
                .append(", voici les solutions susceptibles de vous intéresser :\n\n");
        for (SuiviModels.ProductOfInterest interest : interests) {
            if (!"HIGH".equals(interest.interestLevel()) && !"MEDIUM".equals(interest.interestLevel())) continue;
            sb.append("- ").append(interest.name());
            if (interest.interestReason() != null) sb.append(" : ").append(interest.interestReason());
            if (interest.productUrl() != null) {
                sb.append(" [URL|En savoir plus|").append(interest.productUrl()).append(']');
            }
            sb.append('\n');
        }
        sb.append('\n');
        if (appointmentUrl != null && !appointmentUrl.isBlank()) {
            sb.append("Pour en discuter, vous pouvez [URL|prendre rendez-vous avec votre conseiller|")
                    .append(appointmentUrl).append("].\n\n");
        } else {
            sb.append("N'hésitez pas à contacter votre conseiller pour en discuter.\n\n");
        }
        sb.append("Bien cordialement,\n");
        sb.append(advisorName == null || advisorName.isBlank() ? "Votre conseiller" : advisorName);
        return sb.toString();
    }

    private static String describeProject(Map<String, Object> project) {
        String object = str(project.get("object"));
        String type = str(project.get("type"));
        Object amount = project.get("amount");
        StringBuilder sb = new StringBuilder();
        if (object != null && !object.isBlank()) {
            sb.append(object);
        } else if (type != null) {
            sb.append(type.replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
        }
        if (amount != null) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(amount).append(" €");
        }
        return sb.length() == 0 ? "Projet non précisé" : sb.toString();
    }

    /** Un texte mentionne-t-il le produit ? (nom complet ou mots-clés distinctifs) */
    private static boolean mentions(String text, String productName, String productId) {
        String haystack = normalize(text);
        String name = normalize(productName);
        if (name.length() >= 4 && haystack.contains(name)) {
            return true;
        }
        for (String token : signatureTokens(productName, productId)) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "sg", "bfm", "de", "du", "des", "la", "le", "les", "et", "en", "au", "aux", "un", "une",
            "pour", "avec", "sur", "assurance", "credit", "pret", "livret", "compte", "plan", "contrat",
            "offre", "formule", "tous", "toutes", "votre", "nos", "plus");

    private static List<String> signatureTokens(String productName, String productId) {
        List<String> tokens = new ArrayList<>();
        collectTokens(tokens, productName);
        collectTokens(tokens, productId == null ? null : productId.replace('_', ' '));
        return tokens;
    }

    private static void collectTokens(List<String> tokens, String value) {
        if (value == null) return;
        for (String token : normalize(value).split("[^a-z0-9]+")) {
            if (token.length() >= 4 && !STOPWORDS.contains(token) && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
    }

    private static boolean isRejection(String text) {
        String value = normalize(text);
        return containsAny(value, "pas interesse", "pas intéressé", "non merci", "je refuse", "refuse cette",
                "trop cher", "sans interet", "sans intérêt", "finalement pas");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?>) {
                    result.add(asMap(item));
                }
            }
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Mode démo : rapport Marketing DÉTERMINISTE construit à partir des agrégats fournis.
     * Aucun chiffre n'est inventé — seules les valeurs déjà calculées par le backend sont reprises,
     * et faits/hypothèses sont distingués comme le demande le prompt analyste.
     */
    @Override
    public com.coach.financier.model.MarketingModels.MarketingReport analyzeMarketing(
            com.coach.financier.model.MarketingModels.MarketingAggregates aggregates,
            AIModels.AIProvider provider) {
        List<com.coach.financier.model.MarketingModels.ReportItem> summary = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.MainTrend> mainTrends = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.RecommendationPerformance> recommendations = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.FrictionItem> friction = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.CrossSellInsight> crossSell = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.UnmetNeedInsight> unmetNeeds = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.MissingInfoInsight> missingInfo = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.MarketingAlert> alerts = new ArrayList<>();
        List<com.coach.financier.model.MarketingModels.MarketingOpportunity> opportunities = new ArrayList<>();

        String reportDate = LocalDate.now().toString();
        if (aggregates != null) {
            reportDate = aggregates.dateTo() == null ? reportDate : aggregates.dateTo();
            com.coach.financier.model.MarketingModels.MarketingOverview overview = aggregates.overview();
            if (overview != null) {
                summary.add(item("Volume analysé", overview.conversationCount() + " conversation(s) analysée(s), "
                        + overview.sessionsWithInterest() + " avec intérêt produit, "
                        + overview.highInterestCount() + " intérêt(s) HIGH.", "HIGH"));
                summary.add(item("Intentions fortes", overview.subscriptionIntentCount()
                        + " intention(s) de souscription et " + overview.appointmentRequestCount()
                        + " demande(s) de rendez-vous.", "HIGH"));
                if (overview.unmetNeedCount() > 0) {
                    summary.add(item("Besoins non couverts", overview.unmetNeedCount()
                            + " besoin(s) exprimé(s) sans offre clairement adaptée.", "HIGH"));
                    alerts.add(new com.coach.financier.model.MarketingModels.MarketingAlert("IMPORTANT",
                            "Besoins non couverts", overview.unmetNeedCount()
                            + " besoin(s) sans solution adaptée sur la période."));
                }
                if (overview.missingInformationCount() > 0) {
                    alerts.add(new com.coach.financier.model.MarketingModels.MarketingAlert("WATCH",
                            "Informations produit manquantes", overview.missingInformationCount()
                            + " question(s) sans réponse dans le catalogue."));
                }
                opportunities.add(new com.coach.financier.model.MarketingModels.MarketingOpportunity(
                        "Enrichir la connaissance produit",
                        "Les questions clients non couvertes par le catalogue fournissent des pistes d'enrichissement.",
                        "Étudier les informations produit manquantes les plus fréquentes avant toute décision."));
            }
            for (com.coach.financier.model.MarketingModels.TrendMetric trend : aggregates.trends()) {
                if (mainTrends.size() >= 5 || trend.evolutionPercent() == null) continue;
                boolean up = trend.evolutionPercent() >= 0;
                mainTrends.add(new com.coach.financier.model.MarketingModels.MainTrend(up ? "UP" : "DOWN",
                        trend.entityType(), trend.entityId(), trend.entityName(),
                        trend.current() + " vs " + trend.previous() + " (" + trend.evolutionPercent() + " %)"));
                if (Math.abs(trend.evolutionPercent()) >= 50) {
                    alerts.add(new com.coach.financier.model.MarketingModels.MarketingAlert("WATCH",
                            (up ? "Hausse" : "Baisse") + " marquée : " + trend.entityName(),
                            "Variation de " + trend.evolutionPercent() + " % — à confirmer sur plusieurs périodes."));
                }
            }
            for (com.coach.financier.model.MarketingModels.ProductMetric product : aggregates.products()) {
                if (recommendations.size() >= 5 || product.recommendedSessions() == 0) continue;
                String rate = product.interestRate() == null ? "non calculable"
                        : Math.round(product.interestRate() * 1000) / 10.0 + " %";
                recommendations.add(new com.coach.financier.model.MarketingModels.RecommendationPerformance(
                        product.productId(), product.productName(),
                        "Recommandé dans " + product.recommendedSessions() + " session(s), "
                        + product.interestedSessions() + " avec intérêt réel (taux " + rate + ")."));
            }
            for (com.coach.financier.model.MarketingModels.RejectionMetric rejection : aggregates.rejections()) {
                if (friction.size() >= 4) break;
                friction.add(new com.coach.financier.model.MarketingModels.FrictionItem(rejection.reasonCategory(),
                        rejection.count() + " refus" + (rejection.share() == null ? ""
                                : " (" + Math.round(rejection.share() * 1000) / 10.0 + " %)") + "."));
            }
            for (com.coach.financier.model.MarketingModels.CrossSellMetric pair : aggregates.crossSell()) {
                if (crossSell.size() >= 4) break;
                crossSell.add(new com.coach.financier.model.MarketingModels.CrossSellInsight(
                        pair.sourceProductName(), pair.targetProductName(),
                        pair.commonSessions() + " session(s) communes"
                                + (pair.rate() == null ? "" : " (" + Math.round(pair.rate() * 100) + " %)")
                                + " — association à étudier comme piste de parcours."));
            }
            for (com.coach.financier.model.MarketingModels.UnmetNeedMetric need : aggregates.unmetNeeds()) {
                if (unmetNeeds.size() >= 4) break;
                unmetNeeds.add(new com.coach.financier.model.MarketingModels.UnmetNeedInsight(need.projectType(),
                        need.count() + " occurrence(s) — " + String.valueOf(need.reason())));
            }
            for (com.coach.financier.model.MarketingModels.MissingInfoMetric info : aggregates.missingInformation()) {
                if (missingInfo.size() >= 4) break;
                missingInfo.add(new com.coach.financier.model.MarketingModels.MissingInfoInsight(
                        info.productId(), info.productName(),
                        info.count() + " question(s) sans réponse (" + info.reasonCategory() + ")."));
            }
        }

        return new com.coach.financier.model.MarketingModels.MarketingReport(
                reportDate, summary, mainTrends, recommendations, friction, crossSell, unmetNeeds, missingInfo,
                List.of(), alerts.size() > 8 ? alerts.subList(0, 8) : alerts, opportunities,
                "Rapport généré en mode démo à partir des statistiques agrégées.",
                java.time.Instant.now().toString(), "MOCK", Boolean.TRUE, null);
    }

    private static com.coach.financier.model.MarketingModels.ReportItem item(String title, String description,
                                                                             String importance) {
        return new com.coach.financier.model.MarketingModels.ReportItem(title, description, importance);
    }

    private String money(double value) { return String.format(java.util.Locale.FRANCE, "%.2f", value); }
}
