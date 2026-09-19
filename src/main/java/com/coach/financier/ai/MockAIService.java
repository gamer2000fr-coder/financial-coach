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
     * Mode démo : le prompt système n'a AUCUN effet (la réponse est construite à partir de la synthèse
     * calculée par le backend). La zone éditable d'une campagne n'est donc pas rejouable en mode démo,
     * d'où {@link #NO_REAL_PROVIDER_MESSAGE} pour les agents de l'atelier.
     */
    @Override
    public AIModels.AIAnswer answerWithSystemPrompt(String systemPrompt, String customerMessage,
                                                    AIModels.Classification classification,
                                                    FinancialSummary financialSummary, Object bankingData,
                                                    AIModels.BankingContextMode contextMode,
                                                    Map<String, Object> additionalData,
                                                    List<ConversationModels.Message> history,
                                                    AIModels.AIProvider provider) {
        return answer(customerMessage, classification, financialSummary, bankingData, contextMode, additionalData,
                history, provider);
    }

    /** Message unique expliquant pourquoi l'atelier exige un fournisseur IA réel. */
    public static final String NO_REAL_PROVIDER_MESSAGE =
            "L'atelier d'optimisation des prompts nécessite un fournisseur IA réel (GPT, DEEPSEEK ou "
            + "LOCAL — modèle servi localement) : "
            + "le mode démo ne produit pas de réponse dépendant du prompt.";

    /**
     * L'atelier compare les RÉPONSES RÉELLES du Coach à une question figée : cela n'a de sens qu'avec un
     * modèle réel, seul capable d'exploiter la zone éditable du prompt.
     */
    @Override
    public com.coach.financier.model.PromptOptimizationModels.ControllerFeedback reviewCoachAnswer(
            Map<String, Object> context, AIModels.AIProvider provider) {
        throw new IllegalStateException(NO_REAL_PROVIDER_MESSAGE);
    }

    @Override
    public com.coach.financier.model.PromptOptimizationModels.EditorResult editPromptSection(
            Map<String, Object> context, AIModels.AIProvider provider) {
        throw new IllegalStateException(NO_REAL_PROVIDER_MESSAGE);
    }

    /**
     * CLIENT simulé de l'atelier (« Agent C ») : en mode démo, ce rôle n'a pas de sens (il faudrait un
     * modèle réel pour tenir une conversation crédible) — refus explicite, comme les deux autres agents.
     */
    @Override
    public com.coach.financier.model.PromptOptimizationModels.ClientTurn clientTurn(
            Map<String, Object> context, AIModels.AIProvider provider) {
        throw new IllegalStateException(NO_REAL_PROVIDER_MESSAGE);
    }

    /**
     * CONCEPTION DU PROJET par l'Agent C (« Générer projet ») : en mode démo, inventer un client et son projet
     * demanderait un modèle réel — refus explicite, comme les autres rôles de l'atelier. Un projet déterministe
     * n'aurait aucune valeur ici : c'est justement la variété des scénarios qui teste le prompt.
     */
    @Override
    public com.coach.financier.model.PromptOptimizationModels.ClientBrief clientBrief(
            Map<String, Object> context, AIModels.AIProvider provider) {
        throw new IllegalStateException(NO_REAL_PROVIDER_MESSAGE);
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
                .append("puis vérifier le chiffrage à partir de la grille de taux et préparer l'offre ferme ")
                .append("(la souscription fait foi).\n\n");
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

    /**
     * Mode démo — AGENT ANALYSTE QUALITÉ : rapport DÉTERMINISTE construit uniquement à partir des
     * agrégats calculés par le backend (satisfaction d'un côté, conformité de l'autre).
     * <p>
     * Le mock respecte les règles du prompt : aucun chiffre inventé, satisfaction et conformité
     * TENUES SÉPARÉES, une mauvaise note n'est jamais transformée en anomalie du Coach, et la
     * frustration liée à une règle correctement appliquée (chiffrage de crédit non réalisé malgré la
     * grille de taux disponible) est explicitée.
     */
    @Override
    public com.coach.financier.model.QualityModels.QualityReport analyzeQuality(
            com.coach.financier.model.QualityModels.QualityAggregates aggregates,
            AIModels.AIProvider provider) {
        java.util.List<String> positivePoints = new ArrayList<>();
        java.util.List<String> irritants = new ArrayList<>();
        java.util.List<String> issues = new ArrayList<>();
        java.util.List<String> criticalIssues = new ArrayList<>();
        java.util.List<String> notableCases = new ArrayList<>();
        java.util.List<com.coach.financier.model.QualityModels.RuleFriction> friction = new ArrayList<>();
        java.util.List<com.coach.financier.model.QualityModels.QualityTrend> trends = new ArrayList<>();
        java.util.List<com.coach.financier.model.QualityModels.PriorityImprovement> improvements = new ArrayList<>();
        java.util.List<com.coach.financier.model.QualityModels.QualityAlert> alerts = new ArrayList<>();

        String reportDate = LocalDate.now().toString();
        String status = com.coach.financier.model.QualityModels.STATUS_INSUFFICIENT;
        String satisfactionSummary = "Aucun avis exploitable sur la période : les données fournies ne "
                + "permettent pas de conclure sur la satisfaction client.";
        String complianceSummary = "Aucun contrôle exploitable sur la période : les données fournies ne "
                + "permettent pas d'évaluer la conformité du Coach.";
        String crossSummary = "Le croisement satisfaction × conformité n'est pas exploitable sur la période.";

        if (aggregates != null) {
            reportDate = aggregates.dateTo() == null ? reportDate : aggregates.dateTo();
            com.coach.financier.model.QualityModels.SatisfactionKpis satisfaction = aggregates.satisfaction();
            com.coach.financier.model.QualityModels.ConformityKpis compliance = aggregates.conformity();
            com.coach.financier.model.QualityModels.SatisfactionComplianceMatrix matrix =
                    aggregates.satisfactionVsCompliance();

            if (satisfaction != null && satisfaction.feedbackCount() > 0) {
                satisfactionSummary = satisfaction.feedbackCount() + " avis reçu(s) sur "
                        + satisfaction.conversationsClosed() + " conversation(s) terminée(s)"
                        + (satisfaction.averageRating() == null ? "" : ", note moyenne "
                        + satisfaction.averageRating() + " / 5")
                        + (satisfaction.positiveRate() == null ? "" : ", avis positifs "
                        + percent(satisfaction.positiveRate()) + ", avis négatifs "
                        + percent(satisfaction.negativeRate()) + ".")
                        + (satisfaction.sufficientSample() ? ""
                        : " Le volume d'avis reste limité : ces observations sont préliminaires.");
                if (satisfaction.averageRating() != null && satisfaction.averageRating() >= 4) {
                    positivePoints.add("La satisfaction moyenne est élevée ("
                            + satisfaction.averageRating() + " / 5).");
                }
                if (satisfaction.positiveRate() != null && satisfaction.positiveRate() >= 0.7) {
                    positivePoints.add(percent(satisfaction.positiveRate())
                            + " des avis sont positifs (4 ou 5 étoiles).");
                }
                if (!satisfaction.sufficientSample()) {
                    status = com.coach.financier.model.QualityModels.STATUS_INSUFFICIENT;
                } else if (satisfaction.negativeRate() != null && satisfaction.negativeRate() >= 0.2) {
                    status = com.coach.financier.model.QualityModels.STATUS_ATTENTION;
                } else {
                    status = com.coach.financier.model.QualityModels.STATUS_WATCH;
                }
            }

            for (com.coach.financier.model.QualityModels.ReasonMetric reason : aggregates.feedbackCategories()) {
                if (irritants.size() >= 5) break;
                irritants.add(reason.label() + " (" + reason.count() + " avis"
                        + (reason.evolutionPercent() == null ? "" : ", évolution "
                        + reason.evolutionPercent() + " %") + ")");
            }
            for (com.coach.financier.model.QualityModels.CommentTheme theme : aggregates.commentThemes()) {
                if (irritants.size() >= 5) break;
                irritants.add(theme.label() + " — " + theme.count() + " commentaire(s)");
            }

            if (compliance != null && compliance.checksRun() > 0) {
                long creditViolations = detected(aggregates, com.coach.financier.model.QualityModels
                        .CREDIT_SIMULATION_VIOLATION);
                complianceSummary = compliance.checksRun() + " contrôle(s) exécuté(s), "
                        + compliance.anomalies() + " anomalie(s) dont " + compliance.highAnomalies()
                        + " de sévérité HIGH.";
                if (creditViolations == 0) {
                    complianceSummary += " Aucun chiffrage de crédit hors grille de taux n'a été détecté.";
                }
                for (com.coach.financier.model.QualityModels.CheckMetric check : aggregates.qualityChecks()) {
                    if (check.detected() == 0) continue;
                    String line = check.label() + " : " + check.detected() + " détection(s) sur "
                            + check.checksRun() + " contrôle(s) exécuté(s) (sévérité " + check.severity() + ")";
                    if (com.coach.financier.model.QualityModels.SEVERITY_HIGH.equals(check.severity())) {
                        criticalIssues.add(line);
                    } else {
                        issues.add(line);
                    }
                }
                if (!criticalIssues.isEmpty()) {
                    status = com.coach.financier.model.QualityModels.STATUS_ATTENTION;
                }
            }

            if (matrix != null) {
                crossSummary = "Croisement sur les avis reçus : " + matrix.satisfiedCompliant()
                        + " client(s) satisfait(s) avec Coach conforme, " + matrix.satisfiedAnomaly()
                        + " satisfait(s) avec anomalie, " + matrix.unsatisfiedCompliant()
                        + " insatisfait(s) avec Coach CONFORME, " + matrix.unsatisfiedAnomaly()
                        + " insatisfait(s) avec anomalie réelle.";
                if (matrix.unsatisfiedCompliant() > 0) {
                    notableCases.add("Insatisfaction client alors que le Coach a correctement appliqué les "
                            + "règles : l'amélioration doit porter sur la pédagogie et l'explication de la "
                            + "règle, pas sur la règle elle-même.");
                }
                if (matrix.unsatisfiedAnomaly() > 0) {
                    notableCases.add("Cas prioritaire : insatisfaction ET anomalie confirmée côté Coach ("
                            + matrix.unsatisfiedAnomaly() + ").");
                }
                if (matrix.satisfiedAnomaly() > 0) {
                    notableCases.add("Anomalie(s) non ressentie(s) par le client ("
                            + matrix.satisfiedAnomaly() + ") : à corriger même si la satisfaction est bonne.");
                }
            }

            // Chiffrage de crédit attendu mais NON réalisé alors que la grille de taux était disponible : la
            // règle est correctement appliquée (aucune violation détectée) mais elle frustre le client.
            long simulationRefusals = reasonCount(aggregates,
                    com.coach.financier.model.QualityModels.ACTION_NOT_POSSIBLE);
            long creditViolations = detected(aggregates, com.coach.financier.model.QualityModels
                    .CREDIT_SIMULATION_VIOLATION);
            if (simulationRefusals > 0 && creditViolations == 0) {
                friction.add(new com.coach.financier.model.QualityModels.RuleFriction(
                        "Chiffrage de crédit attendu mais non réalisé (grille de taux non utilisée)",
                        simulationRefusals + " avis mentionnent une action impossible (renvoi vers le "
                                + "simulateur officiel) alors qu'aucun chiffrage hors grille n'a été détecté : "
                                + "la règle est respectée, l'attente du client ne l'est pas.",
                        Boolean.TRUE,
                        "Rappeler au Coach qu'il peut chiffrer à partir de la grille de taux en précisant que la "
                                + "simulation est indicative et que la souscription fait foi, et fournir le lien du "
                                + "simulateur officiel pour l'offre ferme."));
            }

            for (com.coach.financier.model.MarketingModels.TrendMetric trend : aggregates.trends()) {
                if (trends.size() >= 5 || trend.evolutionPercent() == null) continue;
                String type = trend.evolutionPercent() > 0 ? "IMPROVING" : "DEGRADING";
                if ("anomalies".equals(trend.entityId())) {
                    type = trend.evolutionPercent() > 0 ? "DEGRADING" : "IMPROVING";
                }
                if ("feedback".equals(trend.entityId()) || "anomalies".equals(trend.entityId())) {
                    type = "STABLE";
                }
                trends.add(new com.coach.financier.model.QualityModels.QualityTrend(type, trend.entityName(),
                        trend.current() + " vs " + trend.previous() + " (" + trend.evolutionPercent()
                                + " %) sur la période précédente."));
            }

            if (!criticalIssues.isEmpty()) {
                improvements.add(new com.coach.financier.model.QualityModels.PriorityImprovement(
                        "HIGH", "Corriger les anomalies critiques détectées",
                        String.join(" | ", criticalIssues),
                        "Analyser chaque anomalie HIGH (vérifier les messages concernés) puis corriger la "
                                + "règle ou la formulation fautive.",
                        "Réduction des anomalies critiques et sécurisation de la conformité."));
                alerts.add(new com.coach.financier.model.QualityModels.QualityAlert("IMPORTANT",
                        "Anomalies critiques détectées",
                        criticalIssues.size() + " type(s) d'anomalie de sévérité HIGH sur la période."));
            }
            if (!irritants.isEmpty()) {
                improvements.add(new com.coach.financier.model.QualityModels.PriorityImprovement(
                        "MEDIUM", "Traiter le principal irritant client",
                        irritants.get(0),
                        "Tester une réponse plus concise et moins répétitive sur les questions de suivi, "
                                + "sans modifier les garde-fous métier.",
                        "Meilleure lisibilité perçue et satisfaction en hausse."));
            }
            if (matrix != null && matrix.unsatisfiedCompliant() > 0) {
                improvements.add(new com.coach.financier.model.QualityModels.PriorityImprovement(
                        "MEDIUM", "Mieux expliquer les règles qui frustrent",
                        matrix.unsatisfiedCompliant() + " client(s) insatisfait(s) alors que le Coach était "
                                + "conforme.",
                        "Rappeler que le chiffrage s'appuie sur la grille de taux (simulation indicative, la "
                                + "souscription fait foi) et fournir le lien du simulateur officiel.",
                        "Frustration réduite sans affaiblir la conformité."));
            }
            if (satisfaction != null && !satisfaction.sufficientSample()) {
                alerts.add(new com.coach.financier.model.QualityModels.QualityAlert("WATCH",
                        "Échantillon d'avis limité",
                        "Le volume d'avis est faible : les observations doivent être confirmées sur "
                                + "plusieurs périodes."));
            }
            if (satisfaction != null && satisfaction.negativeRate() != null && satisfaction.negativeRate() >= 0.2) {
                alerts.add(new com.coach.financier.model.QualityModels.QualityAlert("WATCH",
                        "Part d'avis négatifs élevée",
                        percent(satisfaction.negativeRate()) + " des avis sont négatifs (1 ou 2 étoiles)."));
            }
        }

        return new com.coach.financier.model.QualityModels.QualityReport(
                reportDate,
                new com.coach.financier.model.QualityModels.ReportPeriod(
                        aggregates == null ? reportDate : aggregates.dateFrom(),
                        aggregates == null ? reportDate : aggregates.dateTo()),
                new com.coach.financier.model.QualityModels.ExecutiveSummary(status, satisfactionSummary),
                new com.coach.financier.model.QualityModels.SatisfactionAnalysis(satisfactionSummary,
                        positivePoints, irritants),
                new com.coach.financier.model.QualityModels.QualityAndCompliance(complianceSummary,
                        issues, criticalIssues),
                new com.coach.financier.model.QualityModels.SatisfactionVsCompliance(crossSummary, notableCases),
                friction, trends, improvements, alerts,
                "Rapport généré en mode démo à partir des statistiques agrégées : satisfaction client et "
                        + "conformité du Coach restent deux dimensions distinctes, aucune anomalie n'est "
                        + "déduite d'une mauvaise note.",
                java.time.Instant.now().toString(), "MOCK", Boolean.TRUE, null);
    }

    private static long detected(com.coach.financier.model.QualityModels.QualityAggregates aggregates,
                                 String checkType) {
        if (aggregates == null) {
            return 0;
        }
        return aggregates.qualityChecks().stream()
                .filter(check -> checkType.equals(check.checkType()))
                .mapToLong(com.coach.financier.model.QualityModels.CheckMetric::detected)
                .sum();
    }

    private static long reasonCount(com.coach.financier.model.QualityModels.QualityAggregates aggregates,
                                    String reason) {
        if (aggregates == null) {
            return 0;
        }
        return aggregates.feedbackCategories().stream()
                .filter(metric -> reason.equals(metric.reason()))
                .mapToLong(com.coach.financier.model.QualityModels.ReasonMetric::count)
                .sum();
    }

    private static String percent(Double rate) {
        return rate == null ? "n/a" : Math.round(rate * 1000) / 10.0 + " %";
    }

    /**
     * Mode démo — ANALYSTE FEEDBACK CONSEILLER : rapport DÉTERMINISTE construit uniquement à partir
     * des KPI calculés par le backend. Aucun chiffre inventé, aucune instruction de commentaire
     * suivie, aucune recommandation de modification automatique du Coach.
     */
    @Override
    public com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport analyzeAdvisorFeedback(
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates,
            AIModels.AIProvider provider) {
        java.util.List<com.coach.financier.model.AdvisorFeedbackModels.StrengthItem> strengths = new ArrayList<>();
        java.util.List<com.coach.financier.model.AdvisorFeedbackModels.IssueItem> issues = new ArrayList<>();
        java.util.List<com.coach.financier.model.AdvisorFeedbackModels.ProductAnalysisItem> productAnalysis =
                new ArrayList<>();
        java.util.List<String> overestimation = new ArrayList<>();
        java.util.List<com.coach.financier.model.AdvisorFeedbackModels.AdvisorTrend> trends = new ArrayList<>();
        java.util.List<com.coach.financier.model.AdvisorFeedbackModels.AdvisorPriorityImprovement> improvements =
                new ArrayList<>();
        java.util.List<String> watchPoints = new ArrayList<>();

        String reportDate = LocalDate.now().toString();
        String status = com.coach.financier.model.AdvisorFeedbackModels.STATUS_INSUFFICIENT;
        String summaryText = "Aucun feedback conseiller exploitable sur la période : les données fournies ne "
                + "permettent pas de conclure sur la pertinence du travail du Coach.";
        String interestSummary = "Aucune correction de niveau d'intérêt fournie.";
        String nextActionSummary = "Aucune évaluation du suivi conseillé fournie.";
        String emailSummary = "Aucune évaluation d'email fournie.";

        if (aggregates != null) {
            reportDate = aggregates.dateTo() == null ? reportDate : aggregates.dateTo();
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorKpis kpis = aggregates.kpis();
            if (kpis != null && kpis.feedbackCount() > 0) {
                summaryText = kpis.sessionsEvaluated() + " dossier(s) évalué(s) : "
                        + percent(kpis.relevantRate()) + " jugé(s) pertinents, "
                        + percent(kpis.needsImprovementRate()) + " à améliorer, "
                        + percent(kpis.incorrectRate()) + " incorrects."
                        + (kpis.sufficientSample() ? ""
                        : " Le volume de retours reste limité : ces observations sont préliminaires.");
                status = kpis.relevantRate() != null && kpis.relevantRate() >= 0.7
                        ? com.coach.financier.model.AdvisorFeedbackModels.STATUS_GOOD
                        : (kpis.incorrectRate() != null && kpis.incorrectRate() >= 0.15
                        ? com.coach.financier.model.AdvisorFeedbackModels.STATUS_ATTENTION
                        : com.coach.financier.model.AdvisorFeedbackModels.STATUS_WATCH);
                if (kpis.relevantRate() != null && kpis.relevantRate() >= 0.7) {
                    strengths.add(new com.coach.financier.model.AdvisorFeedbackModels.StrengthItem(
                            "Analyses globalement validées",
                            percent(kpis.relevantRate()) + " des dossiers évalués sont jugés pertinents par les "
                                    + "conseillers."));
                }
                if (kpis.emailReadyOrMinorRate() != null && kpis.emailReadyOrMinorRate() >= 0.6) {
                    strengths.add(new com.coach.financier.model.AdvisorFeedbackModels.StrengthItem(
                            "Emails préparés exploitables",
                            percent(kpis.emailReadyOrMinorRate()) + " des emails évalués sont prêts à l'emploi ou "
                                    + "ne demandent que des modifications mineures."));
                }
                if (kpis.productRelevanceRate() != null && kpis.productRelevanceRate() >= 0.8) {
                    strengths.add(new com.coach.financier.model.AdvisorFeedbackModels.StrengthItem(
                            "Produits jugés pertinents",
                            percent(kpis.productRelevanceRate()) + " des produits évalués sont validés par les "
                                    + "conseillers."));
                }
            }
            for (com.coach.financier.model.AdvisorFeedbackModels.AreaMetric area : aggregates.areas()) {
                if (issues.size() >= 5) break;
                issues.add(new com.coach.financier.model.AdvisorFeedbackModels.IssueItem(area.area(),
                        area.label() + " : " + area.count() + " correction(s) signalée(s)"
                                + (area.evolutionPercent() == null ? "" : " (évolution "
                                + area.evolutionPercent() + " %)"), "MEDIUM"));
            }
            for (com.coach.financier.model.AdvisorFeedbackModels.ReasonMetric reason : aggregates.reasons()) {
                if (issues.size() >= 6) break;
                issues.add(new com.coach.financier.model.AdvisorFeedbackModels.IssueItem(
                        com.coach.financier.model.AdvisorFeedbackModels.AREA_OTHER,
                        "Motif « " + reason.label() + " » relevé " + reason.count() + " fois", "LOW"));
            }
            for (com.coach.financier.model.AdvisorFeedbackModels.ProductFeedbackMetric product : aggregates.products()) {
                if (productAnalysis.size() >= 8) break;
                String signal = product.relevanceRate() == null
                        ? com.coach.financier.model.AdvisorFeedbackModels.STATUS_INSUFFICIENT
                        : (product.relevanceRate() >= 0.8 ? "POSITIVE"
                        : (product.relevanceRate() >= 0.5 ? "MIXED" : "NEGATIVE"));
                productAnalysis.add(new com.coach.financier.model.AdvisorFeedbackModels.ProductAnalysisItem(
                        product.productId(), product.productName(),
                        product.assessments() + " évaluation(s), " + product.relevant() + " pertinent(s), "
                                + product.notRelevant() + " non pertinent(s), " + product.interestCorrections()
                                + " correction(s) de niveau"
                                + (product.addedByAdvisor() > 0 ? ", ajouté " + product.addedByAdvisor()
                                + " fois par un conseiller" : ""), signal));
            }
            for (com.coach.financier.model.AdvisorFeedbackModels.InterestCorrectionMetric correction
                    : aggregates.interestCorrections()) {
                if (overestimation.size() >= 5) break;
                String direction = "HIGH".equals(correction.fromLevel()) || "MEDIUM".equals(correction.fromLevel())
                        && "LOW".equals(correction.toLevel()) ? "surestimation" : "correction";
                overestimation.add(correction.productName() + " : " + correction.fromLevel() + " → "
                        + correction.toLevel() + " (" + correction.count() + " fois, " + direction + ")");
            }
            if (!aggregates.interestCorrections().isEmpty()) {
                interestSummary = aggregates.kpis().interestCorrections()
                        + " correction(s) de niveau d'intérêt : la valeur IA et la valeur conseiller sont "
                        + "conservées séparément.";
            }
            if (!aggregates.emailQuality().isEmpty()) {
                emailSummary = "Distribution des emails évalués : "
                        + aggregates.emailQuality().stream()
                        .map(item -> item.label() + " = " + item.count())
                        .collect(java.util.stream.Collectors.joining(", ")) + ".";
            }
            if (aggregates.kpis() != null && aggregates.kpis().productAssessments() > 0) {
                nextActionSummary = "Le suivi conseillé est évalué par les conseillers ; "
                        + aggregates.kpis().productAssessments() + " produit(s) évalué(s) sur la période.";
            }
            for (com.coach.financier.model.MarketingModels.TrendMetric trend : aggregates.trends()) {
                if (trends.size() >= 5 || trend.evolutionPercent() == null) continue;
                trends.add(new com.coach.financier.model.AdvisorFeedbackModels.AdvisorTrend(
                        trend.evolutionPercent() > 0 ? "DEGRADING" : "IMPROVING",
                        trend.entityName(), trend.current() + " vs " + trend.previous() + " ("
                                + trend.evolutionPercent() + " %) sur la période précédente."));
            }
            if (!aggregates.areas().isEmpty()) {
                var top = aggregates.areas().get(0);
                improvements.add(new com.coach.financier.model.AdvisorFeedbackModels.AdvisorPriorityImprovement(
                        "MEDIUM", "Traiter la correction la plus fréquente",
                        top.label() + " (" + top.count() + " signalement(s))",
                        "Analyser les dossiers concernés et examiner les critères utilisés par le Coach, "
                                + "sans modifier automatiquement de seuil ni de règle.",
                        "Baisse des corrections manuelles et gain de temps pour les conseillers."));
            }
            if (!overestimation.isEmpty()) {
                improvements.add(new com.coach.financier.model.AdvisorFeedbackModels.AdvisorPriorityImprovement(
                        "HIGH", "Réduire la surestimation des niveaux d'intérêt",
                        String.join(" | ", overestimation),
                        "Étudier les critères distinguant un intérêt explicite d'une simple demande "
                                + "d'information (comparaison des dossiers corrigés).",
                        "Meilleure priorisation commerciale et confiance accrue dans le dossier."));
                watchPoints.add("Plusieurs niveaux d'intérêt corrigés par les conseillers : converger avec "
                        + "les signaux produit.");
            }
            if (aggregates.kpis() != null && !aggregates.kpis().sufficientSample()) {
                watchPoints.add("Volume de retours conseillers limité : confirmer sur plusieurs périodes.");
            }
            if (aggregates.demo()) {
                watchPoints.add("Des feedbacks de démonstration (source=DEMO) sont présents sur la période.");
            }
        }

        return new com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport(
                reportDate,
                new com.coach.financier.model.AdvisorFeedbackModels.AdvisorReportPeriod(
                        aggregates == null ? reportDate : aggregates.dateFrom(),
                        aggregates == null ? reportDate : aggregates.dateTo()),
                new com.coach.financier.model.AdvisorFeedbackModels.AdvisorExecutiveSummary(status, summaryText),
                strengths, issues, productAnalysis,
                new com.coach.financier.model.AdvisorFeedbackModels.InterestLevelAnalysis(interestSummary,
                        overestimation, List.of()),
                new com.coach.financier.model.AdvisorFeedbackModels.SectionAnalysis(nextActionSummary, List.of()),
                new com.coach.financier.model.AdvisorFeedbackModels.SectionAnalysis(emailSummary, List.of()),
                trends, improvements, watchPoints,
                "Rapport généré en mode démo à partir des KPI calculés : il propose des pistes, il ne modifie "
                        + "jamais automatiquement le Coach (prompt, règles, seuils, catalogue, code).",
                java.time.Instant.now().toString(), "MOCK", Boolean.TRUE, null);
    }

    private String money(double value) { return String.format(java.util.Locale.FRANCE, "%.2f", value); }
}
