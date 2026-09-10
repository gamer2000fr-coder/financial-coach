package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.SuiviModels;
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
    public IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                              AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        String system = AgentFiles.readPromptOrDefault("classifieur.txt", FALLBACK_INTENT_CLASSIFIER_PROMPT);
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

    /**
     * Filet de sécurité MINIMAL si {@code classifieur.txt} est absent (le vrai prompt de
     * classification vit dans {@code ./agent/classifieur.txt}). Volontairement court : il ne
     * duplique pas le fichier et sert juste à obtenir un JSON exploitable.
     */
    private static final String FALLBACK_INTENT_CLASSIFIER_PROMPT =
            "Tu analyses les messages d'un client bancaire. Tu ne donnes aucun conseil et ne proposes aucun produit.\n"
            + "Réponds UNIQUEMENT en JSON valide avec les champs : inScope, intent, projectType, projectObject, "
            + "amount, currency, refersToCurrentProject, projectChanged, confidence, reason.\n"
            + "N'invente aucune donnée absente du message ou du contexte. "
            + "Si le type de projet n'est pas fiable : projectType = UNKNOWN.";

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

    @Override
    public SuiviModels.SuiviResult summarizeConversation(Map<String, Object> context, AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        // Agent de synthèse dédié (rédaction du dossier de suivi conseiller + brouillon client).
        String system = AgentFiles.suiviSystemPrompt();
        String user;
        try {
            user = objectMapper.writeValueAsString(context == null ? Map.of() : context);
        } catch (Exception e) {
            throw new IllegalStateException("Contexte de synthèse non sérialisable", e);
        }
        String content = call(system, user);
        try {
            SuiviModels.SuiviResult result = objectMapper.readValue(content, SuiviModels.SuiviResult.class);
            return result == null ? emptySuivi() : result;
        } catch (Exception e) {
            throw new IllegalStateException("Réponse de synthèse invalide: " + content, e);
        }
    }

    private static SuiviModels.SuiviResult emptySuivi() {
        return new SuiviModels.SuiviResult(
                new SuiviModels.ConversationSummary("", List.of(), List.of()),
                List.of(),
                new SuiviModels.EmailContent("", ""),
                new SuiviModels.EmailContent("", ""));
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
