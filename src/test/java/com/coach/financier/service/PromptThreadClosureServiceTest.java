package com.coach.financier.service;

import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.model.SuiviModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie la CLÔTURE d'un scénario d'atelier : le fil est rejoué comme une conversation de chat (mêmes
 * messages, même projet, mêmes offres) et passe dans le MÊME pipeline que la page coach — avec les deux
 * options « email conseiller » et « centre d'appels ».
 */
class PromptThreadClosureServiceTest {
    private PromptOptimizationService promptService;
    private ConversationService conversations;
    private ConversationClosureService closureService;
    private PromptThreadClosureService service;

    @BeforeEach
    void setUp() {
        promptService = mock(PromptOptimizationService.class);
        conversations = new ConversationService(0);
        closureService = mock(ConversationClosureService.class);
        service = new PromptThreadClosureService(promptService, conversations, closureService);
    }

    @Test
    void closingAThreadReplaysItAsAChatConversationForTheSamePipeline() {
        when(promptService.thread("t-1")).thenReturn(thread("t-1", List.of("c1")));
        when(promptService.snapshot("c1")).thenReturn(snapshot());
        when(closureService.close(anyString(), any(), anyBoolean())).thenReturn(response("SENT"));

        PromptThreadClosureService.ThreadClosure result =
                service.close("t-1", true, true, AIModels.AIProvider.MOCK);

        assertEquals(2, result.messages());
        assertEquals("credit_conso", result.agentId());
        assertEquals("SENT", result.response().status());

        ConversationModels.Conversation conversation = conversations.find("t-1");
        assertNotNull(conversation, "la session de chat est reconstruite pour la clôture");
        assertEquals(2, conversation.transcript().size(), "les tours validés deviennent les messages du chat");
        assertEquals("user", conversation.transcript().get(0).role());
        assertEquals(1, conversation.discussedProducts().size(),
                "les offres compatibles du dernier cycle sont transmises à l'agent de suivi");
        assertEquals(ProjectType.VEHICLE, conversation.currentProject().getType(),
                "le projet du cycle est repris (maturité et catégorie du dossier)");
        assertEquals(0, new BigDecimal("15000").compareTo(conversation.currentProject().getAmount()));

        ArgumentCaptor<SuiviModels.CloseConversationRequest> request =
                ArgumentCaptor.forClass(SuiviModels.CloseConversationRequest.class);
        verify(closureService).close(eq("t-1"), request.capture(), eq(true));
        assertEquals(Boolean.TRUE, request.getValue().send(),
                "l'option « email » demande bien l'envoi au conseiller");
        assertEquals(AIModels.AIProvider.MOCK, request.getValue().provider());
    }

    @Test
    void theTwoOptionsAreIndependent() {
        when(promptService.thread("t-1")).thenReturn(thread("t-1", List.of("c1")));
        when(promptService.snapshot("c1")).thenReturn(snapshot());
        when(closureService.close(anyString(), any(), anyBoolean())).thenReturn(response("PREPARED"));

        // « centre d'appel » seulement : dossier archivé, aucun mail envoyé.
        service.close("t-1", false, true, AIModels.AIProvider.MOCK);
        ArgumentCaptor<SuiviModels.CloseConversationRequest> request =
                ArgumentCaptor.forClass(SuiviModels.CloseConversationRequest.class);
        verify(closureService).close(eq("t-1"), request.capture(), eq(true));
        assertEquals(Boolean.FALSE, request.getValue().send());

        // « email » seulement : mail envoyé, aucun dossier écrit (l'annuaire n'est pas encombré).
        service.close("t-1", true, false, AIModels.AIProvider.MOCK);
        verify(closureService).close(eq("t-1"), any(), eq(false));
    }

    @Test
    void anEmptyThreadCannotBeClosed() {
        when(promptService.thread("t-vide")).thenReturn(new PromptOptimizationModels.ConversationThread(
                "t-vide", "credit_conso", "Crédit conso", PromptOptimizationModels.ZONE_AGENT,
                List.of(), List.of(), null, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.close("t-vide", true, true, AIModels.AIProvider.MOCK));

        assertEquals(true, error.getMessage().contains("aucun échange"));
        assertNull(conversations.find("t-vide"), "aucune session de chat n'est créée pour rien");
        verify(closureService, never()).close(anyString(), any(), anyBoolean());
    }

    @Test
    void aThreadWithoutSnapshotIsStillClosed() {
        // Campagne antérieure (snapshot illisible) : la clôture reste possible, sans projet ni offres.
        when(promptService.thread("t-1")).thenReturn(thread("t-1", List.of("c-inconnue")));
        when(promptService.snapshot("c-inconnue")).thenThrow(new IllegalArgumentException("Campagne inconnue"));
        when(closureService.close(anyString(), any(), anyBoolean())).thenReturn(response("PREPARED"));

        service.close("t-1", false, false, AIModels.AIProvider.MOCK);

        ConversationModels.Conversation conversation = conversations.find("t-1");
        assertNotNull(conversation);
        assertEquals(2, conversation.transcript().size());
        assertNull(conversation.currentProject(), "aucun projet inventé quand le snapshot est illisible");
        assertEquals(0, conversation.discussedProducts().size());
    }

    // --- fixtures ---------------------------------------------------------------------------------

    private static PromptOptimizationModels.ConversationThread thread(String threadId, List<String> campaignIds) {
        String now = "2026-09-21T10:00:00Z";
        return new PromptOptimizationModels.ConversationThread(threadId, "credit_conso", "Crédit conso",
                PromptOptimizationModels.ZONE_AGENT,
                List.of(new PromptOptimizationModels.Turn("user", "Je veux un crédit auto de 15 000 €",
                                campaignIds.get(0), "V1", now),
                        new PromptOptimizationModels.Turn("assistant", "Voici la simulation du Crédit Auto Expresso.",
                                campaignIds.get(0), "V1", now)),
                campaignIds, now, now);
    }

    /** Snapshot minimal portant un projet, une offre compatible et une synthèse financière. */
    private static PromptOptimizationModels.Snapshot snapshot() {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("type", "VEHICLE");
        project.put("object", "voiture d'occasion");
        project.put("amount", 15000);
        project.put("currency", "EUR");
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("id", "sg_credit_auto_expresso");
        product.put("name", "Crédit Auto Expresso");
        product.put("family", "AUTO_LOAN");
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put("currentProject", project);
        additional.put("compatibleProducts", new ArrayList<>(List.of(product)));
        return new PromptOptimizationModels.Snapshot("snap-1", "c1", "Je veux un crédit auto", "credit_conso",
                "Crédit conso", PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt", "V0",
                "", "", "", "", "prompt figé", "gabarit", "principal", null, null, null, List.of(),
                additional, List.of(), "", "MOCK", "mock-model", "hash", "hash", "2026-09-21T10:00:00Z");
    }

    private static SuiviModels.CloseConversationResponse response(String status) {
        return new SuiviModels.CloseConversationResponse("t-1", status, "Conseiller", "conseiller@sg.test",
                "dossier.eml", List.of(), new SuiviModels.ConversationSummary("", List.of(), List.of()),
                List.of(), List.of(), new SuiviModels.EmailContent("", ""),
                new SuiviModels.EmailContent("", ""), List.of());
    }
}
