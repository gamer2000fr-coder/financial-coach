package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.model.SuiviModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public abstract class RemoteAIService implements AIService {
    private static final Logger log = LoggerFactory.getLogger(RemoteAIService.class);
    /** Délai de CONNEXION vers le fournisseur IA (ms). */
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    /** Délai de LECTURE d'une réponse (ms) : le Coach peut être long, mais jamais indéfini. */
    private static final int READ_TIMEOUT_MS = 300_000;
    /**
     * Plafond de sortie par DÉFAUT, utilisé si la configuration est absente : il correspond au maximum
     * DOCUMENTÉ de {@code deepseek-chat} (défaut du fournisseur : 4096, qui coupait les réponses longues).
     * <p>
     * Le plafond effectif reste celui du MODÈLE : mesuré chez DeepSeek, une valeur supérieure est acceptée
     * puis plafonnée par le fournisseur ; chez OpenAI, dépasser le maximum du modèle fait échouer l'appel.
     * D'où un réglage PAR FOURNISSEUR ({@code app.ai.deepseek.max-tokens} / {@code app.ai.openai.max-tokens}),
     * jamais deviné par le code.
     */
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 8192;
    /** Longueur de la fin de réponse citée dans les messages d'erreur (diagnostic IHM). */
    private static final int ERROR_TAIL_LENGTH = 200;

    protected final ObjectMapper objectMapper;
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final String providerName;
    /** Plafond de sortie envoyé au modèle (voir {@link #DEFAULT_MAX_OUTPUT_TOKENS}). */
    private final int maxOutputTokens;
    /**
     * Une clé API est-elle EXIGÉE ? Vrai pour les fournisseurs distants (GPT/DeepSeek) ; faux pour un
     * serveur LOCAL (LM Studio, Ollama…) qui n'en demande aucune — l'en-tête {@code Authorization} n'est
     * alors même pas envoyé.
     */
    private final boolean apiKeyRequired;
    /**
     * Faut-il demander le mode JSON natif ({@code response_format: json_object}) ? Vrai pour GPT/DeepSeek ;
     * <b>faux pour LM Studio</b>, qui refuse cette valeur.
     */
    private final boolean jsonResponseFormat;
    /**
     * Délai de LECTURE effectif d'une réponse (ms). Il est RÉGLABLE par fournisseur parce que la latence
     * dépend du service : une API distante répond en quelques secondes, un modèle servi localement peut
     * mettre plusieurs minutes. Un délai trop COURT coupe une réponse en cours de génération ; il est donc
     * cité tel quel dans le message d'erreur (voir {@link #readFailure}).
     */
    private final int readTimeoutMs;
    /**
     * Mode JSON CONTRANT en schéma ({@code response_format: json_schema}) : c'est ce que LM Studio accepte
     * (il refuse {@code json_object}). Le schéma est volontairement permissif (objet libre) : il impose une
     * <b>forme</b> JSON valide sans figer les champs, que les prompts décrivent déjà.
     * <p>
     * Sans cette contrainte, un modèle local « lâché » dans un contexte long (page coach : ~48 000 jetons)
     * répond en PROSE — constaté avec Qwen3.5-9B (« Unrecognized token 'Votre' » côté parsing).
     */
    private final boolean jsonSchemaMode;

    /** Fournisseur DISTANT classique : clé obligatoire et mode JSON natif demandé. */
    protected RemoteAIService(ObjectMapper objectMapper, String baseUrl, String apiKey, String model,
                              String providerName, int maxOutputTokens) {
        this(objectMapper, baseUrl, apiKey, model, providerName, maxOutputTokens, true, true, false,
                READ_TIMEOUT_MS);
    }

    protected RemoteAIService(ObjectMapper objectMapper, String baseUrl, String apiKey, String model,
                              String providerName, int maxOutputTokens, boolean apiKeyRequired,
                              boolean jsonResponseFormat) {
        this(objectMapper, baseUrl, apiKey, model, providerName, maxOutputTokens, apiKeyRequired,
                jsonResponseFormat, false, READ_TIMEOUT_MS);
    }

    /**
     * Variante complète : clé facultative, mode JSON natif ou en schéma, et délai de LECTURE (un modèle
     * servi localement génère bien plus lentement qu'une API distante).
     */
    protected RemoteAIService(ObjectMapper objectMapper, String baseUrl, String apiKey, String model,
                              String providerName, int maxOutputTokens, boolean apiKeyRequired,
                              boolean jsonResponseFormat, boolean jsonSchemaMode, int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.providerName = providerName;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        this.apiKeyRequired = apiKeyRequired;
        this.jsonResponseFormat = jsonResponseFormat;
        this.jsonSchemaMode = jsonSchemaMode;
        this.readTimeoutMs = Math.max(0, readTimeoutMs);
        // Timeouts EXPLICITES : sans eux, une campagne d'optimisation (jusqu'à 3 appels IA par
        // itération, 50 itérations) peut rester bloquée indéfiniment sur un provider muet.
        //
        // HTTP/1.1 IMPOSÉ : le client du JDK négocie HTTP/2 par défaut (mise à niveau « h2c » en clair).
        // Mesuré face à un serveur LOCAL LM Studio : la connexion TCP s'établit, le modèle n'est JAMAIS
        // sollicité et l'appel reste en attente jusqu'au délai de lecture (le serveur ne gère pas la mise
        // à niveau). En HTTP/1.1, la même requête répond en quelques secondes. Tous les fournisseurs
        // utilisés ici (DeepSeek, OpenAI, LM Studio) servent du REST classique en HTTP/1.1.
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS));
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClientBuilder.build());
        // Délai de LECTURE : 0 = AUCUN (une génération locale longue ne doit jamais être coupée).
        // Il est réglable par fournisseur (app.ai.<fournisseur>.read-timeout-seconds).
        if (this.readTimeoutMs > 0) {
            requestFactory.setReadTimeout(Duration.ofMillis(this.readTimeoutMs));
        }
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                              AIModels.AIProvider provider) {
        requireApiKey();
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
        return answerWithSystemPrompt(null, customerMessage, classification, financialSummary, bankingData,
                contextMode, additionalData, history, provider);
    }

    @Override
    public AIModels.AIAnswer answerWithSystemPrompt(String systemPrompt, String customerMessage,
                                                    AIModels.Classification classification,
                                                    FinancialSummary financialSummary, Object bankingData,
                                                    AIModels.BankingContextMode contextMode,
                                                    Map<String, Object> additionalData,
                                                    List<ConversationModels.Message> history,
                                                    AIModels.AIProvider provider) {
        requireApiKey();
        // Prompt système : version FIGÉE fournie par l'atelier d'optimisation (rejeu d'une campagne),
        // sinon l'AGENT ACTIF (générique par défaut) relu depuis ./agent à chaque appel. Le thème est
        // choisi par ChatController et transmis via additionalData."agent".
        String system = resolveSystemPrompt(systemPrompt, additionalData);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("customerMessage", customerMessage);
        payload.put("classification", classification);
        payload.put("financialSummary", financialSummary);
        payload.put("bankingData", bankingData == null ? Map.of() : bankingData);
        payload.put("additionalData", additionalData == null ? Map.of() : additionalData);
        payload.put("conversationHistory", history == null ? List.of() : history);

        String content = null;
        try {
            content = call(system, objectMapper.writeValueAsString(payload));
            return parseAnswer(content);
        } catch (Exception e) {
            // DÉGRADATION UTILE : un modèle qui répond du TEXTE alors que le prompt exige un OBJET JSON ne
            // doit pas faire échouer l'échange — constaté avec un modèle servi localement (Qwen3.5-9B) sur
            // un contexte de plusieurs dizaines de milliers de jetons. Le texte est alors utilisé TEL QUEL
            // comme réponse au client (journalisé, jamais silencieux). Un JSON mal formé ou hors contrat
            // garde, lui, son erreur explicite : c'est un diagnostic, pas un cas d'usage.
            if (isPlainTextAnswer(content)) {
                log.warn("[IA] {} : réponse NON JSON utilisée telle quelle comme réponse au client "
                        + "({} caractères) — le modèle n'a pas respecté le contrat JSON.", providerName,
                        content == null ? 0 : content.strip().length());
                return new AIModels.AIAnswer(AIModels.AIStatus.ANSWER, content.strip(), null, Map.of(), "", null);
            }
            // AUCUNE réponse n'est arrivée (délai de lecture dépassé, appel refusé par le fournisseur, clé
            // absente) : l'erreur d'origine dit DÉJÀ ce qui s'est passé. La préfixer par « Réponse IA
            // invalide » ferait croire à une réponse mal formée alors que le modèle n'a rien rendu — le
            // diagnostic affiché dans l'IHM doit rester exact.
            if (content == null) {
                if (e instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Appel du fournisseur " + providerName
                        + " impossible : " + e.getMessage(), e);
            }
            // La FIN de la réponse est citée : sans elle, « Unexpected end-of-input » ne dit pas si la réponse
            // est vide, coupée en plein texte ou seulement mal formée.
            throw new IllegalStateException("Réponse IA invalide: " + contentOrUnknown(e)
                    + " (fin de la réponse reçue : …" + tailOf(content) + ")", e);
        }
    }

    /**
     * Le modèle a-t-il répondu du TEXTE là où le contrat attend un OBJET JSON ?
     * <p>
     * Vrai uniquement si la réponse ne ressemble PAS à du JSON (elle ne commence ni par {@code \{} ni par
     * {@code [}). Un JSON valide mais hors contrat (statut inconnu, champ manquant) n'est PAS du texte :
     * son erreur reste explicite.
     */
    static boolean isPlainTextAnswer(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.strip();
        return !(trimmed.startsWith("{") || trimmed.startsWith("["));
    }

    /**
     * Prompt système EFFECTIF d'un appel Coach : la version FIGÉE fournie par l'atelier d'optimisation
     * des prompts lorsqu'elle existe (rejeu d'une campagne), sinon le prompt de l'agent actif relu
     * depuis {@code ./agent}.
     * <p>
     * Exposé en {@code protected} : c'est le point de REPRODUCTIBILITÉ que les tests verrouillent — une
     * campagne doit rejouer EXACTEMENT le prompt figé, jamais le prompt courant du disque.
     */
    protected static String resolveSystemPrompt(String systemPromptOverride, Map<String, Object> additionalData) {
        return systemPromptOverride == null || systemPromptOverride.isBlank()
                ? AgentFiles.systemPromptFor(themeOf(additionalData)) : systemPromptOverride;
    }

    /** Thème de l'agent actif transmis par l'appelant via {@code additionalData."agent"}. */
    private static String themeOf(Map<String, Object> additionalData) {
        return additionalData != null && additionalData.get("agent") instanceof String theme ? theme : null;
    }

    /** Vérifie la présence d'une clé API : message explicite, exploitable par l'IHM. */
    protected void requireApiKey() {
        if (apiKeyRequired && apiKey.isBlank()) {
            throw new IllegalStateException("Clé API absente pour le fournisseur " + providerName);
        }
    }

    /**
     * Corps de la requête OpenAI-compatible, isolé pour être VÉRIFIABLE en test : le mode JSON natif n'est
     * demandé qu'aux fournisseurs qui le supportent ({@code response_format: json_object} fait échouer
     * LM Studio avec un 400 « must be 'json_schema' or 'text' »).
     */
    Map<String, Object> requestBody(String system, String user) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", model);
        request.put("temperature", 0.2);
        request.put("max_tokens", maxOutputTokens);
        if (jsonSchemaMode) {
            // Schéma PERMISSIF : la FORME (objet JSON) est garantie, les CHAMPS restent décrits par le prompt.
            request.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "reponse",
                            "strict", false,
                            "schema", Map.of("type", "object", "additionalProperties", true))));
        } else if (jsonResponseFormat) {
            request.put("response_format", Map.of("type", "json_object"));
        }
        request.put("messages", List.of(
                Map.of("role", "system", "content", system == null ? "" : system),
                // RAPPEL DE FORMAT placé à la FIN du message utilisateur (dernière consigne lue) : les contrats
                // JSON sont décrits dans le prompt système, mais un modèle local les perd dans un contexte long
                // et répond en prose. Ce rappel ne change rien au contenu demandé.
                Map.of("role", "user", "content", (user == null ? "" : user) + JSON_FORMAT_REMINDER)
        ));
        return request;
    }

    /** Rappel de FORME (jamais de fond) ajouté en fin de message utilisateur — voir {@link #requestBody}. */
    static final String JSON_FORMAT_REMINDER =
            "\n\nRAPPEL DE FORMAT : réponds UNIQUEMENT par un objet JSON valide "
            + "(premier caractère {, dernier }), sans texte avant ni après.";

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
        Map<String, Object> request = requestBody(system, user);
        var spec = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON);
        if (!apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        String rawResponse;
        try {
            rawResponse = spec.body(request).retrieve().body(String.class);
        } catch (Exception e) {
            throw readFailure(e);
        }
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("Réponse IA vide");
        }
        try {
            JsonNode response = objectMapper.readTree(rawResponse);
            String content = response.path("choices").path(0).path("message").path("content").asText();
            String finishReason = response.path("choices").path(0).path("finish_reason").asText("");
            // Certains modèles encadrent leur JSON par un bloc Markdown : on retire l'encadrement pour que
            // TOUS les points de parsing (coach, suivi, contrôleur, éditeur, rapports) en bénéficient.
            return repairTruncated(stripCodeFence(content), finishReason);
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la lecture de la réponse JSON de l'IA", e);
        }
    }

    /**
     * Traduit un échec de l'appel HTTP. Le cas le plus fréquent — et le plus déroutant — est le
     * <b>dépassement du délai de lecture</b> : un modèle servi localement continue de générer, mais
     * l'application a déjà rendu la main. Le message doit donc dire QUEL délai a expiré et COMMENT le
     * régler, au lieu du « Read timed out » brut du client HTTP.
     */
    private RuntimeException readFailure(Exception e) {
        if (isTimeout(e)) {
            log.warn("[IA] {} : délai de lecture dépassé ({} s) — aucune réponse reçue à temps. {}",
                    providerName, readTimeoutMs / 1000, readTimeoutHint());
            return new IllegalStateException("Délai de lecture dépassé après " + (readTimeoutMs / 1000)
                    + " s sur le fournisseur " + providerName
                    + " : le modèle n'a pas rendu sa réponse à temps. " + readTimeoutHint(), e);
        }
        // Appel REFUSÉ par le fournisseur (400/401/429/500…) : le corps de la réponse est la seule source
        // d'explication (ex. LM Studio : « The number of tokens to keep from the initial prompt is greater
        // than the context length »). On le cite donc au lieu de laisser le message brut du client HTTP.
        if (e instanceof RestClientResponseException response) {
            log.warn("[IA] {} : le fournisseur a refusé l'appel (HTTP {}) : {}", providerName,
                    response.getStatusCode().value(), tailOf(response.getResponseBodyAsString()));
            return new IllegalStateException("Le fournisseur " + providerName + " a refusé l'appel (HTTP "
                    + response.getStatusCode().value() + ") : " + tailOf(response.getResponseBodyAsString()),
                    e);
        }
        return e instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Appel du fournisseur " + providerName + " impossible : "
                        + e.getMessage(), e);
    }

    /**
     * Un délai a-t-il expiré, OU BIEN la cause est-elle tout autre (serveur absent, 500, JSON illisible) ?
     * Les clients HTTP enveloppent la cause (RestClient → {@code ResourceAccessException} →
     * {@code HttpTimeoutException}) : on remonte donc la chaîne des causes plutôt que de tester un seul type.
     */
    static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Comment allonger le délai de ce fournisseur. Surchargé par le fournisseur LOCAL, dont le délai est
     * réglable par configuration ({@code app.ai.local.read-timeout-seconds}).
     */
    protected String readTimeoutHint() {
        return "Allongez le délai de lecture du fournisseur " + providerName
                + " si le service est simplement lent.";
    }

    /**
     * Une réponse JSON **tronquée** (le modèle s'est arrêté en plein milieu) est réparée plutôt que rejetée :
     * l'itération aboutit avec ce que le modèle a réellement produit, et la troncature reste TRACÉE dans la
     * sortie serveur (aucune réparation silencieuse).
     */
    private String repairTruncated(String content, String finishReason) {
        // Cause TRACÉE précisément : « valeur non citée » et « troncature » demandent des corrections
        // différentes chez le modèle, on ne peut pas se contenter d'un « JSON réparé » générique.
        String quoted = JsonRepair.quoteBareFieldValues(content);
        boolean bareValues = !quoted.equals(content) && JsonRepair.isValidJson(quoted, objectMapper);
        String repaired = JsonRepair.repair(content, objectMapper);
        if (!repaired.equals(content)) {
            log.warn("[IA] {} : réponse JSON RÉPARÉE avant analyse — cause : {}. {} caractères reçus, "
                    + "finish_reason={}. Fin reçue : …{}",
                    providerName,
                    bareValues
                            ? "valeurs d'énumération NON CITÉES (le modèle a écrit HIGH au lieu de \"HIGH\")"
                            : "réponse INCOMPLÈTE (troncature)",
                    content.length(), finishReason.isEmpty() ? "non fourni" : finishReason, tailOf(content));
            return repaired;
        }
        if ("length".equals(finishReason)) {
            log.warn("[IA] {} : réponse coupée par la limite de sortie du modèle (max_tokens={}, {} caractères)",
                    providerName, maxOutputTokens, content.length());
        }
        return content;
    }

    /** Fin d'un texte, pour un message de diagnostic (jamais plus de {@link #ERROR_TAIL_LENGTH} caractères). */
    private static String tailOf(String content) {
        if (content == null) {
            return "(vide)";
        }
        return content.length() <= ERROR_TAIL_LENGTH
                ? content : content.substring(content.length() - ERROR_TAIL_LENGTH);
    }

    /**
     * Retire un éventuel encadrement Markdown d'une réponse JSON de modèle
     * ({@code ```json … ```}) : le contenu est valide mais n'est pas parsable tel quel.
     */
    static String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.strip();
    }

    @Override
    public PromptOptimizationModels.ControllerFeedback reviewCoachAnswer(Map<String, Object> context,
                                                                       AIModels.AIProvider provider) {
        requireApiKey();
        String content = call(AgentFiles.promptControllerSystemPrompt(),
                serialize(context, "Contexte de contrôle non sérialisable"));
        PromptOptimizationModels.ControllerFeedback feedback;
        try {
            feedback = objectMapper.readValue(content, PromptOptimizationModels.ControllerFeedback.class);
        } catch (Exception e) {
            throw new IllegalStateException("Diagnostic du contrôleur invalide: " + content, e);
        }
        if (feedback == null) {
            throw new IllegalStateException("Diagnostic du contrôleur vide");
        }
        return feedback;
    }

    @Override
    public PromptOptimizationModels.EditorResult editPromptSection(Map<String, Object> context,
                                                                  AIModels.AIProvider provider) {
        requireApiKey();
        String content = call(AgentFiles.promptEditorSystemPrompt(),
                serialize(context, "Contexte d'édition non sérialisable"));
        PromptOptimizationModels.EditorResult result;
        try {
            result = objectMapper.readValue(content, PromptOptimizationModels.EditorResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Proposition d'édition invalide: " + content, e);
        }
        if (result == null) {
            throw new IllegalStateException("Proposition d'édition vide");
        }
        return result;
    }

    /**
     * CLIENT SIMULÉ de l'atelier (« Agent C ») : il joue le client qui parle au Coach et renvoie UNE question
     * (ou la fin du scénario). Le contexte (brief client, chiffres du dossier, conversation déjà échangée,
     * numéro de question, profondeur) est construit par l'appelant ; cette couche l'envoie et parse la sortie.
     */
    @Override
    public PromptOptimizationModels.ClientTurn clientTurn(Map<String, Object> context, AIModels.AIProvider provider) {
        requireApiKey();
        String content = call(AgentFiles.promptClientSystemPrompt(),
                serialize(context, "Contexte client non sérialisable"));
        PromptOptimizationModels.ClientTurn turn;
        try {
            turn = objectMapper.readValue(content, PromptOptimizationModels.ClientTurn.class);
        } catch (Exception e) {
            throw new IllegalStateException("Question du client invalide: " + content, e);
        }
        return turn == null ? PromptOptimizationModels.ClientTurn.empty() : turn;
    }

    /**
     * CONCEPTION DU PROJET par l'Agent C (« Générer projet ») : il invente le client et la raison de sa
     * visite, dans le périmètre de l'agent de coach sélectionné, en évitant les projets déjà proposés.
     * Le contexte (agent + son prompt, chiffres du dossier, briefs précédents) est construit par l'appelant.
     */
    @Override
    public PromptOptimizationModels.ClientBrief clientBrief(Map<String, Object> context, AIModels.AIProvider provider) {
        requireApiKey();
        String content = call(AgentFiles.promptClientBriefSystemPrompt(),
                serialize(context, "Contexte de génération du projet non sérialisable"));
        PromptOptimizationModels.ClientBrief brief;
        try {
            brief = objectMapper.readValue(content, PromptOptimizationModels.ClientBrief.class);
        } catch (Exception e) {
            throw new IllegalStateException("Projet du client invalide: " + content, e);
        }
        if (brief == null || !brief.usable()) {
            // Une réponse vide n'est pas un projet : mieux vaut un échec explicite qu'un brief vide proposé à
            // l'humain, qui lancerait ensuite un scénario sans aucun cadre.
            throw new IllegalStateException("Projet du client invalide: réponse vide");
        }
        return brief;
    }

    /** Sérialise le contexte transmis à un agent interne de l'atelier d'optimisation des prompts. */
    private String serialize(Map<String, Object> context, String errorMessage) {
        try {
            return objectMapper.writeValueAsString(context == null ? Map.of() : context);
        } catch (Exception e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    private String contentOrUnknown(Exception e) { return e.getMessage() == null ? providerName : e.getMessage(); }
}
