package com.coach.financier.controller;

import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.AgentFiles;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ChatModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.CurrentProject;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.ProjectType;
import com.coach.financier.service.AILogService;
import com.coach.financier.service.CoachContext;
import com.coach.financier.service.CoachContextBuilder;
import com.coach.financier.service.ConversationService;
import com.coach.financier.service.DataRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ConversationService conversationService;
    private final DataRequestService dataRequestService;
    private final AIServiceFactory aiServiceFactory;
    private final AILogService aiLogService;
    private final CoachContextBuilder coachContextBuilder;

    public ChatController(ConversationService conversationService,
                          DataRequestService dataRequestService,
                          AIServiceFactory aiServiceFactory,
                          AILogService aiLogService,
                          CoachContextBuilder coachContextBuilder) {
        this.conversationService = conversationService;
        this.dataRequestService = dataRequestService;
        this.aiServiceFactory = aiServiceFactory;
        this.aiLogService = aiLogService;
        this.coachContextBuilder = coachContextBuilder;
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

        // 3) Contexte du tour : synthèse financière, engagements, produits compatibles, catalogue
        //    filtré, données d'agent et debug. Construit par CoachContextBuilder, PARTAGÉ avec
        //    l'atelier d'optimisation des prompts (qui doit rejouer EXACTEMENT le même contexte).
        CoachContext ctx = coachContextBuilder.build(request.message(), classification,
                conversation.currentProject(), conversation.messages());
        FinancialSummary summary = ctx.financialSummary();
        conversation.setFinancialSummary(summary);

        if (ctx.clarificationRequired()) {
            String clarify = CoachContextBuilder.CLARIFICATION_MESSAGE;
            conversation.addMessage("assistant", clarify);
            return new ChatModels.ChatResponse(request.sessionId(), provider, classification.toLegacyCategory(), true,
                    AIModels.AIStatus.ANSWER, clarify, summary, conversation.summary(),
                    AgentFiles.libelleFor(AgentFiles.GENERIC_THEME));
        }

        // Mémorise, pour le dossier de suivi de fin de conversation, les offres réellement
        // présentées au client (et non tout le catalogue).
        conversation.addDiscussedProducts(ctx.compatibleProducts());
        AIModels.Classification legacy = ctx.legacyClassification();

        // 5) IA Coach (avec boucle NEED_DATA existante, limitée à 3). Prompt = agent actif.
        long sentChars = coachContextBuilder.payloadCharCount(ctx, request.message());
        String promptSnapshot = coachContextBuilder.loggedPrompt(ctx, request.message());
        AIModels.AIAnswer answer = ai.answer(request.message(), legacy, summary, ctx.catalog(),
                AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, ctx.additionalData(), ctx.history(), provider);
        logAiCall(request.sessionId(), request.message(), ctx.providedData(), ctx.history().size(), sentChars,
                ctx.agentLibelle(), promptSnapshot, ctx.debug(), answer);
        int safetyLoop = 0;
        while (answer.status() == AIModels.AIStatus.NEED_DATA && safetyLoop < 3) {
            safetyLoop++;
            List<String> paths = answer.dataRequest() == null ? List.of() : answer.dataRequest().paths();
            List<Map<String, Object>> fetched = dataRequestService.fetch(paths, ctx.allowedCatalogPaths());
            if (fetched.isEmpty()) {
                break; // l'IA ne demande rien de valide : on arrête la boucle.
            }
            ctx.providedData().addAll(fetched);
            sentChars = coachContextBuilder.payloadCharCount(ctx, request.message());
            promptSnapshot = coachContextBuilder.loggedPrompt(ctx, request.message());
            answer = ai.answer(request.message(), legacy, summary, ctx.catalog(),
                    AIModels.BankingContextMode.SYNTHESIS_AVAILABLE, ctx.additionalData(), ctx.history(), provider);
            logAiCall(request.sessionId(), request.message(), ctx.providedData(), ctx.history().size(), sentChars,
                    ctx.agentLibelle(), promptSnapshot, ctx.debug(), answer);
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
                answer.status(), answer.answer(), summary, conversation.summary(), ctx.agentLibelle());
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

}
