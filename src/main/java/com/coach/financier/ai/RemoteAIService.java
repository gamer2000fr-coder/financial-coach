package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.MarketingModels;
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
                new SuiviModels.EmailContent("", ""),
                List.of());
    }

    @Override
    public MarketingModels.MarketingReport analyzeMarketing(MarketingModels.MarketingAggregates aggregates,
                                                           AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        // L'analyste ne reçoit QUE des statistiques agrégées (jamais les conversations brutes).
        String system = AgentFiles.marketingSystemPrompt();
        String user;
        try {
            user = objectMapper.writeValueAsString(aggregates == null ? Map.of() : aggregates);
        } catch (Exception e) {
            throw new IllegalStateException("Agrégats marketing non sérialisables", e);
        }
        String content = call(system, user);
        try {
            MarketingModels.MarketingReport parsed =
                    objectMapper.readValue(content, MarketingModels.MarketingReport.class);
            return withReportMeta(parsed, aggregates);
        } catch (Exception e) {
            throw new IllegalStateException("Rapport marketing invalide: " + content, e);
        }
    }

    @Override
    public com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport analyzeAdvisorFeedback(
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates,
            AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        // L'analyste ne reçoit QUE les KPI calculés et des commentaires anonymisés (§1 du prompt).
        String system = AgentFiles.advisorFeedbackSystemPrompt();
        String user;
        try {
            user = objectMapper.writeValueAsString(advisorPayload(aggregates));
        } catch (Exception e) {
            throw new IllegalStateException("Agrégats de feedback conseiller non sérialisables", e);
        }
        String content = call(system, user);
        try {
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport parsed =
                    objectMapper.readValue(content,
                            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport.class);
            return withAdvisorMeta(parsed, aggregates);
        } catch (Exception e) {
            throw new IllegalStateException("Rapport Feedback Conseiller invalide: " + content, e);
        }
    }

    /** Payload calqué sur les données d'entrée décrites par le prompt (§1). */
    private static Map<String, Object> advisorPayload(
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates) {
        if (aggregates == null) {
            return Map.of();
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("period", Map.of("from", aggregates.dateFrom(), "to", aggregates.dateTo(),
                "comparisonPeriod", "PREVIOUS_PERIOD"));
        payload.put("kpis", aggregates.kpis());
        payload.put("assessments", aggregates.assessments());
        payload.put("correctionAreas", aggregates.areas());
        payload.put("correctionReasons", aggregates.reasons());
        payload.put("productFeedback", aggregates.products());
        payload.put("interestLevelCorrections", aggregates.interestCorrections());
        payload.put("emailQuality", aggregates.emailQuality());
        payload.put("trends", aggregates.trends());
        payload.put("series", aggregates.series());
        payload.put("anonymizedComments", aggregates.anonymizedComments());
        payload.put("demoData", aggregates.demo());
        payload.put("invalidLines", aggregates.invalidLines());
        return payload;
    }

    /** Complète le rapport et NORMALISE les valeurs d'énumération (statut, sévérité, signaux, priorités). */
    private com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport withAdvisorMeta(
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport report,
            com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates) {
        com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport source = report == null
                ? new com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport(null, null, null,
                List.of(), List.of(), List.of(),
                new com.coach.financier.model.AdvisorFeedbackModels.InterestLevelAnalysis("", List.of(), List.of()),
                new com.coach.financier.model.AdvisorFeedbackModels.SectionAnalysis("", List.of()),
                new com.coach.financier.model.AdvisorFeedbackModels.SectionAnalysis("", List.of()),
                List.of(), List.of(), List.of(), null, null, null, null, null) : report;
        String date = aggregates == null || aggregates.dateTo() == null
                ? source.reportDate() : aggregates.dateTo();
        var summary = source.executiveSummary() == null
                ? new com.coach.financier.model.AdvisorFeedbackModels.AdvisorExecutiveSummary(
                com.coach.financier.model.AdvisorFeedbackModels.STATUS_INSUFFICIENT, "")
                : new com.coach.financier.model.AdvisorFeedbackModels.AdvisorExecutiveSummary(
                com.coach.financier.model.AdvisorFeedbackModels
                        .normalizeReportStatus(source.executiveSummary().status()),
                source.executiveSummary().summary());
        List<com.coach.financier.model.AdvisorFeedbackModels.IssueItem> issues = source.mainIssues().stream()
                .map(item -> new com.coach.financier.model.AdvisorFeedbackModels.IssueItem(
                        item.area(), item.observation(),
                        com.coach.financier.model.AdvisorFeedbackModels.normalizePriority(item.severity())))
                .toList();
        List<com.coach.financier.model.AdvisorFeedbackModels.ProductAnalysisItem> products =
                source.productAnalysis().stream()
                        .map(item -> new com.coach.financier.model.AdvisorFeedbackModels.ProductAnalysisItem(
                                item.productId(), item.productName(), item.observation(),
                                com.coach.financier.model.AdvisorFeedbackModels.normalizeSignal(item.signal())))
                        .toList();
        List<com.coach.financier.model.AdvisorFeedbackModels.AdvisorTrend> trends = source.trends().stream()
                .map(item -> new com.coach.financier.model.AdvisorFeedbackModels.AdvisorTrend(
                        com.coach.financier.model.AdvisorFeedbackModels.normalizeTrendType(item.type()),
                        item.topic(), item.observation()))
                .toList();
        List<com.coach.financier.model.AdvisorFeedbackModels.AdvisorPriorityImprovement> improvements =
                source.priorityImprovements().stream()
                        .map(item -> new com.coach.financier.model.AdvisorFeedbackModels.AdvisorPriorityImprovement(
                                com.coach.financier.model.AdvisorFeedbackModels.normalizePriority(item.priority()),
                                item.title(), item.observation(), item.recommendation(), item.expectedBenefit()))
                        .limit(5) // §20 : au maximum 5 priorités
                        .toList();
        com.coach.financier.model.AdvisorFeedbackModels.AdvisorReportPeriod period = aggregates == null
                ? source.period()
                : new com.coach.financier.model.AdvisorFeedbackModels.AdvisorReportPeriod(
                aggregates.dateFrom(), aggregates.dateTo());
        return new com.coach.financier.model.AdvisorFeedbackModels.AdvisorFeedbackReport(
                date, period, summary, source.strengths(), issues, products, source.interestLevelAnalysis(),
                source.nextActionAnalysis(), source.clientEmailAnalysis(), trends, improvements,
                source.watchPoints(), source.finalAssessment(), java.time.Instant.now().toString(),
                model, Boolean.TRUE, null);
    }

    /** Complète le rapport avec les métadonnées calculées côté backend (date, modèle, horodatage). */    private MarketingModels.MarketingReport withReportMeta(MarketingModels.MarketingReport report,
                                                           MarketingModels.MarketingAggregates aggregates) {
        String reportDate = aggregates == null ? report.reportDate() : aggregates.dateTo();
        return new MarketingModels.MarketingReport(
                reportDate,
                copy(report.executiveSummary()),
                copy(report.mainTrends()),
                copy(report.recommendationPerformance()),
                copy(report.customerFriction()),
                copy(report.crossSellInsights()),
                copy(report.unmetNeeds()),
                copy(report.missingProductInformation()),
                copy(report.aiCoachQuality()),
                copy(report.alerts()),
                copy(report.opportunities()),
                report.finalSummary(),
                java.time.Instant.now().toString(),
                model,
                Boolean.TRUE,
                null);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @Override
    public com.coach.financier.model.QualityModels.QualityReport analyzeQuality(
            com.coach.financier.model.QualityModels.QualityAggregates aggregates,
            AIModels.AIProvider provider) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
        // L'analyste qualité ne reçoit QUE des agrégats calculés + des commentaires anonymisés
        // (jamais de donnée bancaire, jamais d'identité client).
        String system = AgentFiles.qualitySystemPrompt();
        String user;
        try {
            user = objectMapper.writeValueAsString(qualityPayload(aggregates));
        } catch (Exception e) {
            throw new IllegalStateException("Agrégats qualité non sérialisables", e);
        }
        String content = call(system, user);
        try {
            com.coach.financier.model.QualityModels.QualityReport parsed =
                    objectMapper.readValue(content, com.coach.financier.model.QualityModels.QualityReport.class);
            return withQualityMeta(parsed, aggregates);
        } catch (Exception e) {
            throw new IllegalStateException("Rapport qualité invalide: " + content, e);
        }
    }

    /**
     * Payload envoyé à l'agent Qualité, calqué sur la structure d'entrée documentée par le prompt
     * (période, satisfaction, catégories, contrôles, conformité, croisement, tendances). Les valeurs
     * sont STRICTEMENT celles calculées par le backend.
     */
    private static Map<String, Object> qualityPayload(
            com.coach.financier.model.QualityModels.QualityAggregates aggregates) {
        if (aggregates == null) {
            return Map.of();
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("period", Map.of(
                "from", aggregates.dateFrom(),
                "to", aggregates.dateTo(),
                "comparisonPeriod", "PREVIOUS_PERIOD"));
        payload.put("satisfaction", aggregates.satisfaction());
        payload.put("ratingDistribution", aggregates.ratingDistribution());
        payload.put("feedbackCategories", aggregates.feedbackCategories());
        payload.put("commentThemes", aggregates.commentThemes());
        payload.put("anonymizedComments", aggregates.anonymizedComments());
        payload.put("qualityChecks", aggregates.qualityChecks());
        payload.put("implementedChecks", aggregates.implementedChecks());
        payload.put("notImplementedChecks", aggregates.notImplementedChecks());
        payload.put("compliance", aggregates.conformity());
        payload.put("satisfactionVsCompliance", aggregates.satisfactionVsCompliance());
        payload.put("trends", aggregates.trends());
        payload.put("series", aggregates.series());
        payload.put("demoData", aggregates.demo());
        payload.put("invalidLines", aggregates.invalidLines());
        return payload;
    }

    /** Complète le rapport avec les métadonnées système et NORMALISE les valeurs d'énumération. */
    private com.coach.financier.model.QualityModels.QualityReport withQualityMeta(
            com.coach.financier.model.QualityModels.QualityReport report,
            com.coach.financier.model.QualityModels.QualityAggregates aggregates) {
        com.coach.financier.model.QualityModels.QualityReport source = report == null
                ? new com.coach.financier.model.QualityModels.QualityReport(null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null, null, null) : report;
        String date = aggregates == null || aggregates.dateTo() == null
                ? source.reportDate() : aggregates.dateTo();
        com.coach.financier.model.QualityModels.ExecutiveSummary summary = source.executiveSummary() == null
                ? new com.coach.financier.model.QualityModels.ExecutiveSummary(
                com.coach.financier.model.QualityModels.STATUS_INSUFFICIENT, "")
                : new com.coach.financier.model.QualityModels.ExecutiveSummary(
                com.coach.financier.model.QualityModels.normalizeReportStatus(source.executiveSummary().status()),
                source.executiveSummary().summary());
        List<com.coach.financier.model.QualityModels.QualityTrend> trends = source.trends().stream()
                .map(trend -> new com.coach.financier.model.QualityModels.QualityTrend(
                        com.coach.financier.model.QualityModels.normalizeQualityTrendType(trend.type()),
                        trend.topic(), trend.observation()))
                .toList();
        List<com.coach.financier.model.QualityModels.PriorityImprovement> improvements =
                source.priorityImprovements().stream()
                        .map(item -> new com.coach.financier.model.QualityModels.PriorityImprovement(
                                com.coach.financier.model.QualityModels.normalizePriority(item.priority()),
                                item.title(), item.observation(), item.recommendation(), item.expectedBenefit()))
                        .limit(5) // §22 : au maximum 5 améliorations prioritaires
                        .toList();
        List<com.coach.financier.model.QualityModels.QualityAlert> alerts = source.alerts().stream()
                .map(alert -> new com.coach.financier.model.QualityModels.QualityAlert(
                        com.coach.financier.model.QualityModels.normalizeAlertLevel(alert.level()),
                        alert.title(), alert.description()))
                .limit(8) // §23 : au maximum 5 à 8 signaux
                .toList();
        com.coach.financier.model.QualityModels.ReportPeriod period = aggregates == null
                ? source.period()
                : new com.coach.financier.model.QualityModels.ReportPeriod(
                aggregates.dateFrom(), aggregates.dateTo());
        return new com.coach.financier.model.QualityModels.QualityReport(
                date, period, summary, source.satisfactionAnalysis(), source.qualityAndCompliance(),
                source.satisfactionVsCompliance(), source.ruleFriction(), trends, improvements, alerts,
                source.finalAssessment(), java.time.Instant.now().toString(), model, Boolean.TRUE, null);
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
