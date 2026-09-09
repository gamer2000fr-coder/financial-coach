package com.coach.financier.controller;

import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AgentDefinition;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.BankProduct;
import com.coach.financier.model.BankingModels;
import com.coach.financier.model.ChatModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.ProjectType;
import com.coach.financier.repository.BankingDataRepository;
import com.coach.financier.service.AILogService;
import com.coach.financier.service.ConversationService;
import com.coach.financier.service.DataRequestService;
import com.coach.financier.service.FinancialAnalysisService;
import com.coach.financier.service.FinancialSynthesisStore;
import com.coach.financier.service.ProductCatalogueService;
import com.coach.financier.service.ProjectProductMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ConversationService conversationService;
    private final FinancialAnalysisService financialAnalysisService;
    private final DataRequestService dataRequestService;
    private final AIServiceFactory aiServiceFactory;
    private final FinancialSynthesisStore financialSynthesisStore;
    private final AILogService aiLogService;
    private final ProductCatalogueService productCatalogueService;
    private final ProjectProductMappingService mappingService;
    private final BankingDataRepository bankingDataRepository;
    private final ObjectMapper objectMapper;

    /** Intentions qui déclenchent la bascule vers l'agent spécialisé du thème. */
    private static final Set<FinancialIntent> AGENT_ROUTABLE_INTENTS = EnumSet.of(
            FinancialIntent.FINANCING_REQUEST, FinancialIntent.PRODUCT_INFORMATION,
            FinancialIntent.CREDIT_INFORMATION);

    public ChatController(ConversationService conversationService,
                          FinancialAnalysisService financialAnalysisService,
                          DataRequestService dataRequestService,
                          AIServiceFactory aiServiceFactory,
                          FinancialSynthesisStore financialSynthesisStore,
                          AILogService aiLogService,
                          ProductCatalogueService productCatalogueService,
                          ProjectProductMappingService mappingService,
                          BankingDataRepository bankingDataRepository,
                          ObjectMapper objectMapper) {
        this.conversationService = conversationService;
        this.financialAnalysisService = financialAnalysisService;
        this.dataRequestService = dataRequestService;
        this.aiServiceFactory = aiServiceFactory;
        this.financialSynthesisStore = financialSynthesisStore;
        this.aiLogService = aiLogService;
        this.productCatalogueService = productCatalogueService;
        this.mappingService = mappingService;
        this.bankingDataRepository = bankingDataRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ChatModels.ChatResponse chat(@Valid @RequestBody ChatModels.ChatRequest request) {
        var conversation = conversationService.getOrCreate(request.sessionId());
        var provider = request.provider() == null ? aiServiceFactory.defaultProvider() : request.provider();
        var ai = aiServiceFactory.get(provider);

        conversation.addMessage("user", request.message());

        // 1) IA de compréhension : périmètre + intention + type de projet (+ montant/objet).
        String currentProjectText = describeCurrentProject(conversation.currentProject());
        boolean guardDisabled = Boolean.TRUE.equals(request.disableOutOfScopeGuard());
        IntentClassification classification;
        if (guardDisabled) {
            classification = new IntentClassification();
            classification.setInScope(true);
            classification.setIntent(FinancialIntent.OTHER);
            classification.setProjectType(ProjectType.OTHER_FINANCIAL);
            classification.setConfidence(ConfidenceLevel.HIGH);
            classification.setReason("Contrôle OUT_OF_SCOPE désactivé par l'utilisateur.");
        } else {
            classification = ai.classifyIntent(request.message(), currentProjectText, provider);
        }

        if (classification.isOutOfScope()) {
            String response = "Je suis spécialisé dans l'accompagnement financier et budgétaire. "
                    + "Je peux par exemple vous aider à évaluer un achat, votre capacité d'épargne ou l'impact d'un projet sur votre budget.";
            conversation.addMessage("assistant", response);
            return new ChatModels.ChatResponse(request.sessionId(), provider, classification.toLegacyCategory(), false,
                    AIModels.AIStatus.ANSWER, response, null, conversation.summary(),
                    AgentFiles.libelleFor(AgentFiles.GENERIC_THEME));
        }

        // 2) Backend : mise à jour du projet courant dans la session.
        updateCurrentProject(conversation, classification);

        var summary = financialAnalysisService.analyze();
        conversation.setFinancialSummary(summary);

        // Engagements existants (crédits en cours) — distincts des produits proposés.
        List<Map<String, Object>> existingCredits = buildExistingCredits();

        // 3) Backend : filtrage métier — produits compatibles chargés UNIQUEMENT si nécessaire.
        CurrentProject project = conversation.currentProject();
        boolean requiresProducts = requiresProducts(classification);
        boolean projectUsable = project != null && project.getType() != ProjectType.UNKNOWN && project.getType() != null;
        boolean unknownOrLow = classification.needsClarification()
                || (requiresProducts && !projectUsable);

        if (requiresProducts && unknownOrLow) {
            String clarify = "Pour vous proposer un financement adapté, pourriez-vous préciser à quoi servira "
                    + "cet argent (par exemple l'achat d'un véhicule, des travaux, etc.) ?";
            conversation.addMessage("assistant", clarify);
            return new ChatModels.ChatResponse(request.sessionId(), provider, classification.toLegacyCategory(), true,
                    AIModels.AIStatus.ANSWER, clarify, summary, conversation.summary(),
                    AgentFiles.libelleFor(AgentFiles.GENERIC_THEME));
        }

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
        String agentTheme = selectAgentTheme(request.message(), classification, conversation.currentProject());
        AgentDefinition activeAgent = AgentFiles.agentFor(agentTheme);
        String agentLibelle = AgentFiles.libelleFor(agentTheme);

        // 4) Contexte compact transmis au coach.
        //    Catalogue RESTREINT en contexte financement : seuls les fichiers de données et les
        //    produits compatibles restent visibles -> on empêche structurellement l'IA de
        //    récupérer un produit hors périmètre (ex. credit_immo.json pour un véhicule).
        boolean restrictCatalog = requiresProducts && projectUsable;
        Set<ProductFamily> debugFamilies = Set.of();
        int catalogBefore = dataRequestService.catalogEntries().size();
        Set<String> allowedCatalogPaths = null;
        List<Map<String, String>> visibleEntries;
        if (restrictCatalog) {
            debugFamilies = mappingService.getAllowedFamilies(project.getType());
            allowedCatalogPaths = new HashSet<>();
            visibleEntries = new ArrayList<>();
            for (Map<String, String> entry : dataRequestService.catalogEntries()) {
                if (isCatalogueEntryAllowed(entry.get("path"), debugFamilies)) {
                    allowedCatalogPaths.add(entry.get("path"));
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
                agentTheme, agentLibelle, agentDataCount);

        // 5) IA Coach (avec boucle NEED_DATA existante, limitée à 3). Prompt = agent actif.
        long sentChars = payloadCharCount(request.message(), legacy, summary, catalog, providedData,
                conversation.messages(), agentTheme);
        String promptSnapshot = buildLoggedPrompt(request.message(), legacy, providedData,
                conversation.messages(), agentTheme);
        AIModels.AIAnswer answer = ai.answer(request.message(), legacy, summary, catalog,
                AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, additionalData, conversation.messages(), provider);
        logAiCall(request.sessionId(), request.message(), providedData, conversation.messages().size(), sentChars,
                agentLibelle, promptSnapshot, debug, answer);
        int safetyLoop = 0;
        while (answer.status() == AIModels.AIStatus.NEED_DATA && safetyLoop < 3) {
            safetyLoop++;
            List<String> paths = answer.dataRequest() == null ? List.of() : answer.dataRequest().paths();
            List<Map<String, Object>> fetched = dataRequestService.fetch(paths, allowedCatalogPaths);
            if (fetched.isEmpty()) {
                break; // l'IA ne demande rien de valide : on arrête la boucle.
            }
            providedData.addAll(fetched);
            sentChars = payloadCharCount(request.message(), legacy, summary, catalog, providedData,
                    conversation.messages(), agentTheme);
            promptSnapshot = buildLoggedPrompt(request.message(), legacy, providedData,
                    conversation.messages(), agentTheme);
            answer = ai.answer(request.message(), legacy, summary, catalog,
                    AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, additionalData, conversation.messages(), provider);
            logAiCall(request.sessionId(), request.message(), providedData, conversation.messages().size(), sentChars,
                    agentLibelle, promptSnapshot, debug, answer);
        }

        if (answer.status() == AIModels.AIStatus.NEED_DATA) {
            answer = new AIModels.AIAnswer(AIModels.AIStatus.ANSWER,
                    "Je n'ai pas pu finaliser l'analyse demandée à partir des données disponibles.", null, Map.of(),
                    conversation.summary(), null);
        }

        conversation.addMessage("assistant", answer.answer());
        if (answer.conversationSummary() != null && !answer.conversationSummary().isBlank()) {
            conversation.setSummary(answer.conversationSummary());
        }

        return new ChatModels.ChatResponse(request.sessionId(), provider, legacy.category(), true,
                answer.status(), answer.answer(), summary, conversation.summary(), agentLibelle);
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
        // Assurance-vie = placement (épargne), pas une assurance dommage.
        if (hay.contains("assurance-vie") || containsAnyStr(hay, "livret", "épargne", "epargne",
                "pea", "placer", "placement")) {
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

    /** Sous-type d'assurance demandé (auto / habitation / emprunteur), déduit du message + contexte projet. */
    private static String insuranceSubTheme(String message, String projectObject, CurrentProject project) {
        String hay = ((projectObject == null ? "" : projectObject + " ")
                + (message == null ? "" : message)).toLowerCase(Locale.ROOT);
        if (containsAnyStr(hay, "emprunteur", "prêt immobilier", "pret immobilier",
                "crédit immobilier", "credit immobilier", "assurance de prêt", "assurance crédit")) {
            return "assurance_emprunteur";
        }
        if (containsAnyStr(hay, "voiture", "véhicule", "vehicule", "auto", "moto", "conduite", "permis")) {
            return "assurance_auto";
        }
        if (containsAnyStr(hay, "habitation", "maison", "appartement", "logement",
                "locataire", "résidence", "residence")) {
            return "assurance_habitation";
        }
        // Aucun sous-type dans le message : on s'appuie sur le projet courant (assurance auto si véhicule,
        // emprunteur si projet immobilier), sinon agent générique.
        if (project != null && project.getType() == ProjectType.REAL_ESTATE_PURCHASE) {
            return "assurance_emprunteur";
        }
        if (project != null && project.getType() == ProjectType.VEHICLE) {
            return "assurance_auto";
        }
        return AgentFiles.GENERIC_THEME;
    }

    private static boolean containsAnyStr(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void updateCurrentProject(ConversationModels.Conversation conversation, IntentClassification c) {
        ProjectType type = c.getProjectType();
        if (type == null || type == ProjectType.UNKNOWN) {
            return;
        }
        CurrentProject current = conversation.currentProject();
        if (current == null || (c.getIntent() == FinancialIntent.PROJECT_UPDATE) || c.isProjectChanged()
                || (current.getType() != type)) {
            current = new CurrentProject();
            conversation.setCurrentProject(current);
        }
        current.apply(c);
    }

    private AIModels.Classification legacyOf(IntentClassification c) {
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

    private static String describeCurrentProject(CurrentProject project) {
        if (project == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("type = ").append(project.getType()).append('\n');
        if (project.getObject() != null) {
            sb.append("object = ").append(project.getObject()).append('\n');
        }
        if (project.getAmount() != null) {
            sb.append("amount = ").append(project.getAmount()).append(' ')
                    .append(project.getCurrency() == null ? "EUR" : project.getCurrency());
        }
        return sb.toString().trim();
    }

    private static Map<String, Object> currentProjectMap(CurrentProject project) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", project.getType() == null ? null : project.getType().name());
        map.put("object", project.getObject());
        map.put("amount", project.getAmount());
        map.put("currency", project.getCurrency());
        return map;
    }

    private void logAiCall(String sessionId, String clientMessage,
                           List<Map<String, Object>> providedData, int historyCount, long charCount,
                           String agent, String promptSnapshot, String debug, AIModels.AIAnswer answer) {
        List<String> dataSent = providedData.stream()
                .map(entry -> String.valueOf(entry.get("description")))
                .toList();
        List<String> requestedData = answer.dataRequest() == null || answer.dataRequest().paths() == null
                ? List.of()
                : answer.dataRequest().paths().stream().map(AILogService::stem).toList();
        aiLogService.log(sessionId, clientMessage, dataSent, historyCount, charCount,
                answer.status(), requestedData, agent, promptSnapshot, debug, answer.answer());
    }

    /** Log structuré [INTENT] / [PRODUCT_FILTER] / [COACH] pour la démo (§38). */
    private static String buildDebugLog(IntentClassification c, CurrentProject project, boolean restricted,
                                        Set<ProductFamily> allowedFamilies, int catalogBefore, int catalogAfter,
                                        int compatibleCount, int existingCreditsCount,
                                        String agentTheme, String agentLibelle, int agentDataCount) {
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

    /** Nombre de caractères envoyés à l'IA pour cet appel : prompt système (principal + règles métier) + payload utilisateur. */
    private long payloadCharCount(String message, AIModels.Classification classification, FinancialSummary summary,
                                  Object catalog, List<Map<String, Object>> providedData,
                                  List<ConversationModels.Message> history, String agentTheme) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerMessage", message);
            payload.put("classification", classification);
            payload.put("financialSummary", summary);
            payload.put("bankingData", catalog == null ? Map.of() : catalog);
            payload.put("additionalData", Map.of("providedData", providedData == null ? List.of() : providedData));
            payload.put("conversationHistory", history == null ? List.of() : history);
            String userJson = objectMapper.writeValueAsString(payload);
            return AgentFiles.systemPromptFor(agentTheme).length() + userJson.length();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Prompt envoyé à l'IA : système (principal + règles métier) + payload utilisateur allégé,
     *  SANS le contenu des données jointes, ni le catalogue (bankingData), ni la synthèse (financialSummary). */
    private String buildLoggedPrompt(String message, AIModels.Classification classification,
                                     List<Map<String, Object>> providedData,
                                     List<ConversationModels.Message> history, String agentTheme) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("customerMessage", message);
            payload.put("classification", classification);
            List<String> descriptions = providedData == null ? List.of()
                    : providedData.stream().map(entry -> String.valueOf(entry.get("description"))).toList();
            payload.put("providedData", descriptions); // données jointes = descriptions seules, pas le contenu
            payload.put("conversationHistory", history == null ? List.of() : history);
            String system = AgentFiles.systemPromptFor(agentTheme);
            return "=== PROMPT SYSTÈME ===\n" + system
                    + "\n\n=== PAYLOAD UTILISATEUR (sans le contenu des données jointes) ===\n"
                    + objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "Impossible de reconstituer le prompt : " + e.getMessage();
        }
    }
}
