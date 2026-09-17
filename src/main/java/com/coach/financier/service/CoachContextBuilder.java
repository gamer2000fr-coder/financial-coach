package com.coach.financier.service;

import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.AgentDefinition;
import com.coach.financier.model.BankProduct;
import com.coach.financier.model.BankingModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.ProjectType;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Construit le {@link CoachContext} transmis au Coach pour un tour de conversation.
 * <p>
 * Ce service a été EXTRAIT de {@code ChatController} (déplacement mécanique, aucune modification de
 * logique) pour être partagé avec l'ATELIER d'optimisation des prompts : une campagne doit rejouer la
 * même question dans EXACTEMENT le même contexte (catalogue filtré, produits compatibles, données
 * d'agent, synthèse financière) pendant que seule la zone éditable du prompt change.
 * <p>
 * Le service est DÉTERMINISTE et sans effet de bord : il ne modifie jamais la conversation (les
 * mutations de session, comme le projet courant, restent dans {@code ChatController}).
 */
@Service
public class CoachContextBuilder {

    /** Question de clarification posée quand le projet est inconnu/ambigu (aucun appel au Coach). */
    public static final String CLARIFICATION_MESSAGE = "Pour vous proposer un financement adapté, "
            + "pourriez-vous préciser à quoi servira cet argent (par exemple l'achat d'un véhicule, "
            + "des travaux, etc.) ?";

    /** Intentions qui déclenchent la bascule vers l'agent spécialisé du thème. */
    private static final Set<FinancialIntent> AGENT_ROUTABLE_INTENTS = EnumSet.of(
            FinancialIntent.FINANCING_REQUEST, FinancialIntent.PRODUCT_INFORMATION,
            FinancialIntent.CREDIT_INFORMATION);

    private final FinancialAnalysisService financialAnalysisService;
    private final DataRequestService dataRequestService;
    private final FinancialSynthesisStore financialSynthesisStore;
    private final ProductCatalogueService productCatalogueService;
    private final ProjectProductMappingService mappingService;
    private final BankingDataRepository bankingDataRepository;
    private final ObjectMapper objectMapper;

    public CoachContextBuilder(FinancialAnalysisService financialAnalysisService,
                               DataRequestService dataRequestService,
                               FinancialSynthesisStore financialSynthesisStore,
                               ProductCatalogueService productCatalogueService,
                               ProjectProductMappingService mappingService,
                               BankingDataRepository bankingDataRepository,
                               ObjectMapper objectMapper) {
        this.financialAnalysisService = financialAnalysisService;
        this.dataRequestService = dataRequestService;
        this.financialSynthesisStore = financialSynthesisStore;
        this.productCatalogueService = productCatalogueService;
        this.mappingService = mappingService;
        this.bankingDataRepository = bankingDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Construit le contexte complet d'un tour de conversation.
     *
     * @param question       message du client (question figée dans une campagne)
     * @param classification classification d'intention DÉJÀ calculée (gelable par l'atelier)
     * @param project        projet courant de la session ({@code null} si aucun)
     * @param history        fenêtre d'historique transmise au Coach
     */
    public CoachContext build(String question, IntentClassification classification, CurrentProject project,
                             List<ConversationModels.Message> history) {
        return build(question, classification, project, history, null);
    }

    /**
     * Variante avec thème d'agent IMPOSÉ : utilisée par l'ATELIER d'optimisation des prompts, où
     * l'administrateur choisit l'agent à optimiser (c'est SA zone éditable qui est testée). Le thème qui
     * aurait été retenu en production reste visible dans le bloc de debug ({@code derivedTheme}).
     */
    public CoachContext build(String question, IntentClassification classification, CurrentProject project,
                             List<ConversationModels.Message> history, String forcedAgentTheme) {
        FinancialSummary summary = financialAnalysisService.analyze();

        // Engagements existants (crédits en cours) — distincts des produits proposés.
        List<Map<String, Object>> existingCredits = buildExistingCredits();

        // Filtrage métier : produits compatibles chargés UNIQUEMENT si nécessaire.
        boolean requiresProducts = requiresProducts(classification);
        boolean projectUsable = project != null && project.getType() != ProjectType.UNKNOWN
                && project.getType() != null;
        boolean unknownOrLow = classification.needsClarification()
                || (requiresProducts && !projectUsable);

        List<Map<String, Object>> compatibleProducts = new ArrayList<>();
        if (requiresProducts && projectUsable) {
            List<BankProduct> compatible = productCatalogueService.findCompatible(
                    project.getType(), project.getAmount());
            for (BankProduct product : compatible) {
                compatibleProducts.add(product.toCompactMap());
            }
        }

        // Agent actif : générique par défaut ; l'agent spécialisé du thème prend la main dès que la
        // demande est clairement rattachée à un produit (crédit conso/immo, épargne, assurance...).
        String derivedTheme = selectAgentTheme(question, classification, project);
        String agentTheme = forcedAgentTheme == null || forcedAgentTheme.isBlank() ? derivedTheme : forcedAgentTheme;
        AgentDefinition activeAgent = AgentFiles.agentFor(agentTheme);
        String agentLibelle = AgentFiles.libelleFor(agentTheme);

        // Contexte compact transmis au coach.
        // Catalogue RESTREINT en contexte financement : seuls les fichiers de données et les
        // produits compatibles restent VISIBLES -> l'IA ne découvre pas spontanément un produit hors périmètre
        // (ex. credit_immo.json pour un véhicule).
        boolean restrictCatalog = requiresProducts && projectUsable;
        Set<ProductFamily> debugFamilies = Set.of();
        int catalogBefore = dataRequestService.catalogEntries().size();
        // FICHIERS FOURNISSABLES : TOUT ce que le catalogue déclare. Dès que le Coach DEMANDE un fichier du
        // catalogue, il lui est fourni (règle simple : « s'il le demande, on l'autorise ») — la whitelist du
        // catalogue reste la seule barrière (aucun fichier hors catalogue, aucun fichier inventé) et il n'y a
        // donc plus de demande légitime refusée. La restriction ci-dessus ne limite plus que ce qui est MONTRÉ.
        Set<String> allowedCatalogPaths = new LinkedHashSet<>();
        for (Map<String, String> entry : dataRequestService.catalogEntries()) {
            allowedCatalogPaths.add(entry.get("path"));
        }
        List<Map<String, String>> visibleEntries;
        if (restrictCatalog) {
            debugFamilies = mappingService.getAllowedFamilies(project.getType());
            visibleEntries = new ArrayList<>();
            for (Map<String, String> entry : dataRequestService.catalogEntries()) {
                if (isCatalogueEntryAllowed(entry.get("path"), debugFamilies)) {
                    visibleEntries.add(entry);
                }
            }
        } else {
            visibleEntries = dataRequestService.catalogEntries();
        }
        Object catalog = visibleEntries;

        List<Map<String, Object>> providedData = new ArrayList<>();
        financialSynthesisStore.load().ifPresent(node -> {
            Map<String, Object> synthesisEntry = new LinkedHashMap<>();
            synthesisEntry.put("description", "Synthèse financière");
            synthesisEntry.put("data", node);
            providedData.add(synthesisEntry);
        });

        // Données propres à l'agent actif (fiche produit + arbre de décision) injectées d'office.
        if (activeAgent != null && activeAgent.getData() != null) {
            for (String path : activeAgent.getData()) {
                Map<String, Object> entry = dataRequestService.readEntry(path);
                if (entry != null) {
                    providedData.add(entry);
                }
            }
        }

        Map<String, Object> additionalData = new LinkedHashMap<>();
        additionalData.put("providedData", providedData);
        if (project != null) {
            additionalData.put("currentProject", currentProjectMap(project));
        }
        additionalData.put("existingCredits", existingCredits);
        additionalData.put("compatibleProducts", compatibleProducts);
        additionalData.put("agent", agentTheme);
        additionalData.put("agentLibelle", agentLibelle);

        AIModels.Classification legacy = legacyOf(classification);
        int agentDataCount = activeAgent == null || activeAgent.getData() == null
                ? 0 : activeAgent.getData().size();
        String debug = buildDebugLog(classification, project, restrictCatalog, debugFamilies,
                catalogBefore, visibleEntries.size(), compatibleProducts.size(), existingCredits.size(),
                agentTheme, agentLibelle, agentDataCount, derivedTheme);

        return new CoachContext(classification, legacy, summary, project, existingCredits, compatibleProducts,
                catalog, allowedCatalogPaths, providedData, additionalData, agentTheme, agentLibelle, agentDataCount,
                requiresProducts, projectUsable, requiresProducts && unknownOrLow, restrictCatalog, debugFamilies,
                catalogBefore, visibleEntries.size(), history, debug);
    }

    /** Faut-il charger les produits compatibles ? Logique déterministe, non déléguée au LLM. */
    private boolean requiresProducts(IntentClassification classification) {
        FinancialIntent intent = classification.getIntent();
        return intent == FinancialIntent.FINANCING_REQUEST || intent == FinancialIntent.PRODUCT_INFORMATION;
    }

    /**
     * Thème de l'agent actif. Deux cas :
     * - le message CHANGE explicitement de thème produit (assurance, épargne, crédit immo/conso...) :
     *   on bascule vers l'agent du NOUVEAU thème, même si le classifieur a gardé l'ancien type de projet
     *   (ex. on parlait crédit véhicule, le client demande maintenant une assurance) ;
     * - sinon : agent générique par défaut, ou agent spécialisé du projet pour les intentions « produit ».
     */
    private String selectAgentTheme(String message, IntentClassification c, CurrentProject project) {
        if (AGENT_ROUTABLE_INTENTS.contains(c.getIntent())) {
            String explicit = explicitAgentTheme(message, c.getProjectObject(), project);
            if (explicit != null) {
                return explicit;
            }
        }
        ProjectType type = c.getProjectType();
        if ((type == null || type == ProjectType.UNKNOWN) && project != null
                && project.getType() != null && project.getType() != ProjectType.UNKNOWN) {
            type = project.getType();
        }
        if (type == null) {
            return AgentFiles.GENERIC_THEME;
        }
        return switch (type) {
            case REAL_ESTATE_PURCHASE -> "credit_immo";
            case SAVINGS, INVESTMENT -> "epargne";
            case INSURANCE -> insuranceSubTheme(message, c.getProjectObject(), project);
            case VEHICLE, HOME_WORK, ELECTRONICS, FURNITURE, TRAVEL, EDUCATION, WEDDING,
                 HEALTH_EXPENSE, CASH_NEED, DEBT_RESTRUCTURING -> "credit_conso";
            default -> AgentFiles.GENERIC_THEME;
        };
    }

    /**
     * Thème produit explicitement demandé dans le message courant (bascule de thème en cours de
     * conversation). Retourne {@code null} si le message n'évoque pas clairement un autre thème.
     */
    private static String explicitAgentTheme(String message, String projectObject, CurrentProject project) {
        String hay = ((projectObject == null ? "" : projectObject + " ")
                + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        // Assurance-vie = placement (épargne), pas une assurance dommage. Les mots-clés courts sont comparés
        // en MOT ENTIER : « placer » ne doit pas matcher « remplacer », ni « pea » matcher « peau ».
        if (hay.contains("assurance-vie") || containsWord(hay, "livret", "pea", "placer", "placement")
                || hay.contains("épargne") || hay.contains("epargne")) {
            return "epargne";
        }
        if (containsAnyStr(hay, "assurance", "mutuelle", "assureur")) {
            return insuranceSubTheme(message, projectObject, project);
        }
        if (containsAnyStr(hay, "crédit immobilier", "credit immobilier", "prêt immobilier",
                "pret immobilier", "crédit immo", "credit immo", "emprunt immo")) {
            return "credit_immo";
        }
        if (containsAnyStr(hay, "crédit conso", "credit conso", "crédit à la consommation",
                "credit a la consommation", "crédit consommation", "credit consommation")) {
            return "credit_conso";
        }
        return null;
    }

    /**
     * Sous-type d'assurance demandé (auto / habitation / emprunteur), déduit du message + contexte projet.
     * <p>
     * Le sous-type est choisi par un SCORE (nombre de mots-clés reconnus) et non par le premier mot trouvé :
     * une mention INCIDENTE ne doit pas l'emporter sur le besoin réel — « assurer mon appartement que je viens
     * d'acheter avec un crédit immobilier » est une assurance HABITATION, pas une assurance emprunteur.
     */
    private static String insuranceSubTheme(String message, String projectObject, CurrentProject project) {
        String hay = ((projectObject == null ? "" : projectObject + " ")
                + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        int emprunteur = countWords(hay, "assurance de prêt", "assurance pret", "assurance crédit",
                "assurance credit", "prêt immobilier", "pret immobilier", "crédit immobilier",
                "credit immobilier", "assurance emprunteur", "emprunteur");
        int habitation = countWords(hay, "habitation", "maison", "appartement", "logement", "locataire",
                "résidence", "residence");
        int auto = countWords(hay, "voiture", "véhicule", "vehicule", "auto", "moto", "deux-roues",
                "deux roues", "permis");
        if (emprunteur > 0 && emprunteur >= habitation && emprunteur >= auto) {
            return "assurance_emprunteur";
        }
        if (habitation > 0 && habitation >= auto) {
            return "assurance_habitation";
        }
        if (auto > 0) {
            return "assurance_auto";
        }
        // Aucun sous-type dans le message : on s'appuie sur le projet courant (assurance emprunteur si projet
        // immobilier, auto si projet véhicule), sinon agent générique — plutôt qu'un sous-type inventé.
        if (project != null && project.getType() == ProjectType.REAL_ESTATE_PURCHASE) {
            return "assurance_emprunteur";
        }
        if (project != null && project.getType() == ProjectType.VEHICLE) {
            return "assurance_auto";
        }
        return AgentFiles.GENERIC_THEME;
    }

    /** Nombre de mots-clés/expressions reconnus en MOT ENTIER (voir {@link #containsWord}). */
    private static int countWords(String text, String... keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (containsWord(text, keyword)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Présence d'un des mots/expressions en MOT ENTIER (frontières non alphabétiques, pluriel en « s »
     * toléré) : sans cela « auto » matcherait « prélèvement automatique » et « placer » matcherait
     * « remplacer ».
     */
    private static boolean containsWord(String text, String... words) {
        for (String word : words) {
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "s?(?![\\p{L}\\p{N}])")
                    .matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyStr(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static AIModels.Classification legacyOf(IntentClassification c) {
        return new AIModels.Classification(c.inScope(), c.toLegacyCategory(), c.getReason());
    }

    private List<Map<String, Object>> buildExistingCredits() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BankingModels.Credit credit : bankingDataRepository.credits()) {
            if (credit.mensualite() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", existingCreditType(credit.produit()));
            entry.put("label", credit.produit() == null ? "" : credit.produit());
            entry.put("monthlyPayment", credit.mensualite());
            result.add(entry);
        }
        return result;
    }

    private static String existingCreditType(String produit) {
        if (produit == null) return "LOAN";
        String p = produit.toLowerCase(Locale.ROOT);
        if (p.contains("immo")) return "MORTGAGE";
        if (p.contains("conso")) return "CONSUMER_CREDIT";
        if (p.contains("auto")) return "AUTO_LOAN";
        return "LOAN";
    }

    private static Map<String, Object> currentProjectMap(CurrentProject project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", project.getType() == null ? null : project.getType().name());
        map.put("object", project.getObject());
        map.put("amount", project.getAmount());
        map.put("currency", project.getCurrency());
        return map;
    }

    /** Log structuré [INTENT] / [PRODUCT_FILTER] / [AGENT] / [COACH] pour la démo (§38). */
    private static String buildDebugLog(IntentClassification c, CurrentProject project, boolean restricted,
                                        Set<ProductFamily> allowedFamilies, int catalogBefore, int catalogAfter,
                                        int compatibleCount, int existingCreditsCount,
                                        String agentTheme, String agentLibelle, int agentDataCount,
                                        String derivedTheme) {
        StringBuilder sb = new StringBuilder();
        sb.append("[INTENT]\n");
        sb.append("intent=").append(c.getIntent()).append('\n');
        sb.append("projectType=").append(c.getProjectType()).append('\n');
        if (c.getProjectObject() != null && !c.getProjectObject().isBlank()) {
            sb.append("projectObject=").append(c.getProjectObject()).append('\n');
        }
        if (c.getAmount() != null) {
            sb.append("amount=").append(c.getAmount()).append(' ').append(c.getCurrency()).append('\n');
        }
        sb.append("refersToCurrentProject=").append(c.isRefersToCurrentProject()).append('\n');
        sb.append("projectChanged=").append(c.isProjectChanged()).append('\n');
        sb.append("confidence=").append(c.getConfidence()).append('\n');
        if (c.getReason() != null) {
            sb.append("reason=").append(c.getReason()).append('\n');
        }
        sb.append('\n');
        sb.append("[PRODUCT_FILTER]\n");
        sb.append("restrictedCatalog=").append(restricted).append('\n');
        sb.append("project=").append(project == null ? "-" : project.getType()).append('\n');
        sb.append("allowedFamilies=").append(allowedFamilies).append('\n');
        sb.append("catalogBefore=").append(catalogBefore).append('\n');
        sb.append("catalogAfter=").append(catalogAfter).append('\n');
        sb.append('\n');
        sb.append("[AGENT]\n");
        sb.append("theme=").append(agentTheme).append('\n');
        sb.append("libelle=").append(agentLibelle).append('\n');
        sb.append("dataInjected=").append(agentDataCount).append('\n');
        sb.append("derivedTheme=").append(derivedTheme).append('\n');
        sb.append('\n');
        sb.append("[COACH]\n");
        sb.append("compatibleProducts=").append(compatibleCount).append('\n');
        sb.append("existingCredits=").append(existingCreditsCount).append('\n');
        return sb.toString();
    }

    /** Fichiers hors /data/catalogue toujours visibles ; produits filtrés par familles autorisées. */
    private static boolean isCatalogueEntryAllowed(String path, Set<ProductFamily> allowedFamilies) {
        if (path == null) {
            return false;
        }
        if (!path.startsWith("/data/catalogue/")) {
            return true; // transactions, synthèse : données financières, jamais des offres.
        }
        String base = path.substring("/data/catalogue/".length());
        if (base.contains("/")) {
            return false; // cascade et sous-dossiers : non exposés directement.
        }
        String stem = base.endsWith(".json") ? base.substring(0, base.length() - ".json".length()) : base;
        Set<ProductFamily> docFamilies = catalogueDocFamilies(stem);
        for (ProductFamily family : docFamilies) {
            if (allowedFamilies.contains(family)) {
                return true;
            }
        }
        return false;
    }

    /** Familles de produits documentées par chaque fichier catalogue (déterministe). */
    private static Set<ProductFamily> catalogueDocFamilies(String stem) {
        return switch (stem) {
            case "credit_conso" -> EnumSet.of(ProductFamily.CONSUMER_CREDIT, ProductFamily.PERSONAL_LOAN,
                    ProductFamily.AUTO_LOAN, ProductFamily.INSTALLMENT_PAYMENT, ProductFamily.REVOLVING_CREDIT,
                    ProductFamily.STUDENT_LOAN, ProductFamily.YOUNG_ACTIVE_LOAN, ProductFamily.DRIVER_LICENSE_LOAN,
                    ProductFamily.DEBT_CONSOLIDATION);
            case "credit_immo" -> EnumSet.of(ProductFamily.MORTGAGE, ProductFamily.HOME_IMPROVEMENT_LOAN,
                    ProductFamily.HOME_SAVINGS);
            case "epargne" -> EnumSet.of(ProductFamily.SAVINGS_PRODUCT, ProductFamily.TERM_DEPOSIT,
                    ProductFamily.HOME_SAVINGS, ProductFamily.LIFE_INSURANCE,
                    ProductFamily.RETIREMENT_SAVINGS, ProductFamily.EQUITY_INVESTMENT);
            case "assurance_auto" -> EnumSet.of(ProductFamily.INSURANCE_AUTO);
            case "assurance_habitation" -> EnumSet.of(ProductFamily.INSURANCE_HOME);
            case "assurance_emprunteur_immo" -> EnumSet.of(ProductFamily.INSURANCE_HOME, ProductFamily.MORTGAGE,
                    ProductFamily.INSURANCE_BORROWER);
            default -> Set.of();
        };
    }

    /** Nombre de caractères envoyés à l'IA pour cet appel : prompt système + payload utilisateur. */
    public long payloadCharCount(CoachContext ctx, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerMessage", message);
            payload.put("classification", ctx.legacyClassification());
            payload.put("financialSummary", ctx.financialSummary());
            payload.put("bankingData", ctx.catalog() == null ? Map.of() : ctx.catalog());
            payload.put("additionalData", Map.of("providedData",
                    ctx.providedData() == null ? List.of() : ctx.providedData()));
            payload.put("conversationHistory", ctx.history() == null ? List.of() : ctx.history());
            String userJson = objectMapper.writeValueAsString(payload);
            return AgentFiles.systemPromptFor(ctx.agentTheme()).length() + userJson.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Prompt envoyé à l'IA : système + payload utilisateur allégé, SANS le contenu des données jointes,
     *  ni le catalogue (bankingData), ni la synthèse (financialSummary). */
    public String loggedPrompt(CoachContext ctx, String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerMessage", message);
            payload.put("classification", ctx.legacyClassification());
            List<String> descriptions = ctx.providedData() == null ? List.of()
                    : ctx.providedData().stream().map(entry -> String.valueOf(entry.get("description"))).toList();
            payload.put("providedData", descriptions); // données jointes = descriptions seules, pas le contenu
            payload.put("conversationHistory", ctx.history() == null ? List.of() : ctx.history());
            String system = AgentFiles.systemPromptFor(ctx.agentTheme());
            return "=== PROMPT SYSTÈME ===\n" + system
                    + "\n\n=== PAYLOAD UTILISATEUR (sans le contenu des données jointes) ===\n"
                    + objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "Impossible de reconstituer le prompt : " + e.getMessage();
        }
    }
}
