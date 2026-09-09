package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public abstract class RemoteAIService implements AIService {
    protected final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String providerName;

    protected RemoteAIService(ObjectMapper objectMapper, String baseUrl, String apiKey, String model, String providerName) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.providerName = providerName;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public AIModels.Classification classifyUserRequest(String message, AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        String system = AgentFiles.readPromptOrDefault("classifieur.txt", """
                Tu es le classificateur d'un coach financier bancaire.
                Ton rôle est de dire si la demande de l'utilisateur relève de l'accompagnement financier et budgétaire personnel.

                Catégories possibles (choisis-en UNE seule) :
                PURCHASE_PROJECT, BUDGET, SAVINGS, CREDIT, CASHFLOW, FINANCIAL_HEALTH, BANK_PRODUCT, OTHER_FINANCIAL, OUT_OF_SCOPE.

                OUT_OF_SCOPE (inScope=false) UNIQUEMENT si la demande est clairement sans AUCUN lien avec les finances personnelles :
                actualité, sport, météo, politique, santé, cuisine/recette, programmation, culture, jeux, etc.,
                ou si elle est frauduleuse/nuisible, ou si elle demande d'inventer ou de modifier des données bancaires.
                Le bavardage, les salutations et les formules de politesse ne sont PAS du hors-sujet.

                RÈGLE PAR DÉFAUT : dans le doute, classe la demande comme DANS le périmètre (inScope=true).
                Une question vague, courte ou ambiguë (par exemple « Bonjour », « Et pour ce montant ? », « Est-ce raisonnable ? », « Combien ? »)
                doit être classée inScope=true dans la catégorie la plus probable (OTHER_FINANCIAL si aucune ne s'impose).

                Réponds UNIQUEMENT en JSON valide : {"inScope":true|false,"category":"...","reason":"..."}
                avec "reason" courte en français.
                N'invente aucune donnée bancaire.
                """);
        String content = call(system, message);
        try {
            return objectMapper.readValue(content, AIModels.Classification.class);
        } catch (Exception e) {
            throw new IllegalStateException("Réponse classifier invalide: " + content, e);
        }
    }

    @Override
    public IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                              AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        String system = AgentFiles.readPromptOrDefault("classifieur.txt", defaultIntentClassifierPrompt());
        StringBuilder user = new StringBuilder();
        if (currentProjectDescription != null && !currentProjectDescription.isBlank()) {
            user.append("CURRENT_PROJECT:\n").append(currentProjectDescription).append("\n\n");
        }
        user.append("USER_MESSAGE:\n").append(userMessage);
        String content = call(system, user.toString());
        try {
            return objectMapper.readValue(content, IntentClassification.class);
        } catch (Exception e) {
            throw new IllegalStateException("Réponse classification invalide: " + content, e);
        }
    }

    /** Prompt par défaut du classifieur d'intention (utilisé si classifieur.txt est absent). */
    private String defaultIntentClassifierPrompt() {
        return """
                Tu analyses les messages d'un client bancaire.

                Ton rôle est uniquement de comprendre la demande et de retourner une structure JSON.

                Tu ne donnes aucun conseil financier.
                Tu ne proposes aucun produit bancaire.
                Tu n'expliques rien directement au client.

                Utilise également CURRENT_PROJECT lorsqu'il est fourni afin de comprendre les expressions comme :
                "mon projet", "pour mon cas", "et en 3 fois ?", "et si je finance seulement la moitié ?",
                "finalement ce sera 12 000 €".

                Retourne :
                - inScope
                - intent (PURCHASE, FINANCING_REQUEST, AFFORDABILITY_CHECK, COMPARE_OPTIONS, BUDGET_ANALYSIS,
                  SAVINGS_ANALYSIS, CREDIT_INFORMATION, PRODUCT_INFORMATION, PROJECT_UPDATE, FOLLOW_UP,
                  OUT_OF_SCOPE, OTHER)
                - projectType (VEHICLE, REAL_ESTATE_PURCHASE, HOME_WORK, ELECTRONICS, FURNITURE, TRAVEL,
                  EDUCATION, WEDDING, HEALTH_EXPENSE, CASH_NEED, DEBT_RESTRUCTURING, SAVINGS, BUDGET,
                  INVESTMENT, INSURANCE, OTHER_FINANCIAL, UNKNOWN)
                - projectObject
                - amount (nombre, sans devise)
                - currency
                - refersToCurrentProject
                - projectChanged
                - confidence (HIGH, MEDIUM, LOW)
                - reason (courte, en français)

                projectType doit être choisi uniquement parmi les valeurs autorisées.
                N'invente aucune donnée absente du message ou du contexte.
                Si le type ne peut pas être déterminé de manière suffisamment fiable : projectType = UNKNOWN.
                Si le message concerne le projet courant sans le modifier : refersToCurrentProject = true,
                projectChanged = false.
                Si une nouvelle information modifie le projet courant : projectChanged = true.

                Réponds UNIQUEMENT en JSON valide.
                """;
    }

    @Override
    public AIModels.AIAnswer answer(String customerMessage, AIModels.Classification classification,
                                    FinancialSummary financialSummary, Object bankingData,
                                    AIModels.BankingContextMode contextMode,
                                    Map<String, Object> additionalData,
                                    List<ConversationModels.Message> history, AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        // Prompt système de l'AGENT ACTIF (générique par défaut), relu depuis ./agent à chaque appel.
        // Le thème est choisi par ChatController et transmis via additionalData."agent".
        String theme = null;
        if (additionalData != null && additionalData.get("agent") instanceof String t) {
            theme = t;
        }
        String system = AgentFiles.systemPromptFor(theme);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("customerMessage", customerMessage);
        payload.put("classification", classification);
        payload.put("financialSummary", financialSummary);
        payload.put("bankingData", bankingData == null ? Map.of() : bankingData);
        payload.put("additionalData", additionalData == null ? Map.of() : additionalData);
        payload.put("conversationHistory", history == null ? List.of() : history);

        String content;
        try {
            content = call(system, objectMapper.writeValueAsString(payload));
            return parseAnswer(content);
        } catch (Exception e) {
            throw new IllegalStateException("Réponse IA invalide: " + contentOrUnknown(e), e);
        }
    }

    private AIModels.AIAnswer parseAnswer(String raw) throws Exception {
        JsonNode node = objectMapper.readTree(raw);
        AIModels.AIStatus status = AIModels.AIStatus.valueOf(node.path("status").asText("ANSWER"));
        String answer = node.path("answer").asText("");
        AIModels.DataRequest dataRequest = node.path("dataRequest").isNull() || node.path("dataRequest").isMissingNode()
                ? null : objectMapper.treeToValue(node.path("dataRequest"), AIModels.DataRequest.class);
        Map<String, Object> update = node.path("financialContextUpdate").isObject()
                ? objectMapper.convertValue(node.path("financialContextUpdate"), Map.class) : Map.of();
        String summary = node.path("conversationSummary").asText("");
        JsonNode synthesis = node.path("financialSynthesis").isObject() ? node.path("financialSynthesis") : null;
        return new AIModels.AIAnswer(status, answer, dataRequest, update, summary, synthesis);
    }

    private String call(String system, String user) {
        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                )
        );
        String rawResponse = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(String.class);
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Réponse IA vide");
        }
        try {
            JsonNode response = objectMapper.readTree(rawResponse);
            return response.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la lecture de la réponse JSON de l'IA", e);
        }
    }

    private String contentOrUnknown(Exception e) { return e.getMessage() == null ? providerName : e.getMessage(); }
}
