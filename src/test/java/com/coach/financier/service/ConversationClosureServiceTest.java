package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.MockAIService;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.BankProduct;
import com.coach.financier.model.BankingModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.ProductFamily;
import com.coach.financier.model.SuiviModels;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie le principe de la clôture : UN SEUL email, au CONSEILLER, avec le brouillon
 * client en pièce jointe — et jamais d'envoi au client.
 */
class ConversationClosureServiceTest {
    private static final String ADVISOR = "conseiller@sg.test";
    private static final String PRODUCT_ID = "sg_auto_tous_risques";
    private static final String PRODUCT_URL = "https://particuliers.sg.fr/assurances/nos-offres/assurance-auto";
    /** Numéro de DÉMO (configuration {@code app.suivi.customer-phone}) : jamais produit par l'IA. */
    private static final String DEMO_PHONE = "0644910925";

    private ConversationService conversationService;
    private AIServiceFactory aiServiceFactory;
    private MailService mailService;
    private AILogService aiLogService;
    private ProductUrlIndex productUrlIndex;
    private ProductCatalogueService productCatalogueService;
    private BankingDataRepository bankingDataRepository;
    private FinancialAnalysisService financialAnalysisService;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        aiServiceFactory = mock(AIServiceFactory.class);
        mailService = mock(MailService.class);
        aiLogService = mock(AILogService.class);
        productCatalogueService = mock(ProductCatalogueService.class);
        bankingDataRepository = mock(BankingDataRepository.class);
        financialAnalysisService = mock(FinancialAnalysisService.class);

        productUrlIndex = mock(ProductUrlIndex.class);
        when(productUrlIndex.urlFor(PRODUCT_ID)).thenReturn(PRODUCT_URL);
        when(productUrlIndex.allUrls()).thenReturn(Set.of(PRODUCT_URL));

        BankProduct product = new BankProduct();
        product.setId(PRODUCT_ID);
        product.setName("Assurance Auto SG – Tous Risques");
        product.setFamily(ProductFamily.INSURANCE_AUTO);
        product.setDescription("Couverture dommages tous accidents, vol, incendie.");
        when(productCatalogueService.all()).thenReturn(List.of(product));

        when(bankingDataRepository.loadSnapshot()).thenReturn(new BankingModels.BankingSnapshot(
                customerNode(), List.of(), List.of(), List.of()));
        when(financialAnalysisService.analyze()).thenReturn(summary());
        when(mailService.unavailabilityReason()).thenReturn("");
        when(mailService.describeTarget()).thenReturn("smtp.gmail.com:587");
        when(mailService.isAvailable()).thenReturn(true);
    }

    @Test
    void close_sendsOnlyToAdvisor_withCustomerDraftAttachment() throws Exception {
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        assertEquals("SENT", response.status());
        assertEquals(List.of(ADVISOR), response.sentTo());
        assertTrue(response.attachmentName().endsWith(".txt"));
        assertTrue(response.preparedCustomerEmail().body().contains("Tous Risques"));

        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SuiviModels.Attachment>> attachments =
                ArgumentCaptor.forClass(List.class);
        verify(mailService, times(1)).sendWithAttachments(to.capture(), anyString(), anyString(),
                anyBoolean(), attachments.capture());

        assertEquals(ADVISOR, to.getValue());
        assertEquals(1, attachments.getValue().size());
        assertEquals(response.attachmentName(), attachments.getValue().get(0).filename());
    }

    @Test
    void close_stripsRejectedProductMentionsFromCustomerDraft() {
        SuiviModels.SuiviResult aiResult = new SuiviModels.SuiviResult(
                new SuiviModels.ConversationSummary("Achat d'une Clio 5", List.of(), List.of()),
                List.of(new SuiviModels.ProductOfInterest(PRODUCT_ID, "Assurance Auto SG – Tous Risques",
                        "INSURANCE_AUTO", "REJECTED", "Le client a écarté cette offre.", null)),
                new SuiviModels.EmailContent("Suivi client", "Email conseiller."),
                new SuiviModels.EmailContent("Votre projet",
                        "Bonjour,\n- Assurance Auto SG – Tous Risques : offre recommandée.\n- Crédit Auto : à étudier.\nCordialement."),
                List.of());

        AIService ai = mock(AIService.class);
        when(ai.summarizeConversation(any(), any())).thenReturn(aiResult);
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(ai);
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        assertTrue(response.rejectedProducts().contains("Assurance Auto SG – Tous Risques"));
        assertFalse(response.preparedCustomerEmail().body().contains("Tous Risques"));
        assertTrue(response.preparedCustomerEmail().body().contains("Crédit Auto"));
        assertTrue(response.warnings().stream().anyMatch(w -> w.toLowerCase().contains("refusé")));
        verify(mailService, times(1)).sendWithAttachments(eq(ADVISOR), anyString(), anyString(),
                anyBoolean(), any());
    }

    @Test
    void close_addsTheEvaluationLinkToTheAdvisorEmail_withoutPersonalData() {
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        String body = response.advisorEmail().body();
        assertTrue(body.contains("Évaluer le suivi du Coach"),
                "Le mail conseiller doit contenir le bloc d'évaluation (§41)");
        assertTrue(body.contains("#/advisor-feedback/session/s1"),
                "Le lien doit cibler directement le dossier de la conversation (§42)");
        assertTrue(body.contains("[URL|Évaluer le suivi du Coach|http://localhost:9898/#/advisor-feedback/session/s1]"),
                "Le lien doit respecter le format [URL|nom|url] pour être cliquable dans les clients mail");
        // §45 : aucune donnée personnelle dans l'URL (ni email client, ni nom du conseiller).
        String link = body.substring(body.indexOf("[URL|Évaluer le suivi du Coach|"));
        link = link.substring(0, link.indexOf(']'));
        assertFalse(link.contains("Michel@gmail.com"), "Aucun email client dans le lien");
        assertFalse(link.contains("Jean Martin"), "Aucun nom dans le lien");
        assertFalse(link.contains("conseiller@sg.test"), "Aucun email conseiller dans le lien");
        // Le brouillon client, lui, ne contient jamais le lien d'évaluation.
        assertFalse(response.preparedCustomerEmail().body().contains("advisor-feedback"),
                "Le brouillon client ne doit pas contenir le lien interne d'évaluation");
        // Lien « dossier client » : il ouvre DIRECTEMENT la pop-in du dossier dans la page Centre d'appels
        // (l'URL de l'outil conseiller n'est qu'un repli quand aucune IHM n'est configurée).
        assertTrue(body.contains(
                        "Dossier client : [URL|Ouvrir le dossier du client|http://localhost:9898/#/centre-appels/s1]"),
                "Le mail conseiller ouvre le dossier dans la page Centre d'appels");
        String dossierLink = body.substring(body.indexOf("[URL|Ouvrir le dossier du client|"));
        dossierLink = dossierLink.substring(0, dossierLink.indexOf(']'));
        assertFalse(dossierLink.contains("Michel@gmail.com"), "Aucun email client dans le lien du dossier");
        assertFalse(dossierLink.contains("Jean Martin"), "Aucun nom dans le lien du dossier");
        assertFalse(body.contains("https://particuliers.sg.fr]"),
                "L'outil conseiller externe n'est plus utilisé quand l'IHM est configurée");
        assertFalse(response.preparedCustomerEmail().body().contains("Ouvrir le dossier du client"),
                "Le brouillon client ne doit pas contenir le lien interne du conseiller");
    }

    @Test
    void close_noLongerAddsTheConversationHistoryLinkToTheAdvisorEmail() {
        // Le lien « Consulter l'historique de la conversation » a été retiré du mail : le conseiller relit
        // les échanges depuis la pop-in du dossier (la page #/conversation/<id> reste disponible).
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        String body = response.advisorEmail().body();
        assertFalse(body.contains("Consulter l'historique de la conversation"),
                "Le mail ne contient plus le bloc d'historique de la conversation");
        assertFalse(body.contains("#/conversation/"),
                "Aucun lien vers la page d'historique dans le mail conseiller");
        assertTrue(body.contains("[URL|Ouvrir le dossier du client|http://localhost:9898/#/centre-appels/s1]"),
                "Le dossier s'ouvre depuis la page Centre d'appels");
        // Le brouillon client ne reçoit jamais les liens internes du conseiller.
        assertFalse(response.preparedCustomerEmail().body().contains("Ouvrir le dossier du client"));
        assertFalse(response.preparedCustomerEmail().body().contains("#/centre-appels/"));
    }

    @Test
    void close_logsTheSuiviAiCall() {
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        service().close("s1", null);

        ArgumentCaptor<String> agent = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> debug = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> mailStatus = ArgumentCaptor.forClass(String.class);
        verify(aiLogService, times(1)).log(eq("s1"), anyString(), anyList(), anyInt(), anyLong(),
                eq(AIModels.AIStatus.ANSWER), anyList(), agent.capture(), prompt.capture(),
                debug.capture(), anyString(), mailStatus.capture());

        assertEquals("SENT", mailStatus.getValue(),
                "La trace de clôture porte l'état d'envoi du mail (affiché sur la page Logs)");
        assertTrue(agent.getValue().toLowerCase().contains("suivi"));
        assertTrue(prompt.getValue().contains("=== PROMPT SYSTÈME"));
        assertTrue(prompt.getValue().contains("CONTEXTE DE CLÔTURE"));
        assertTrue(debug.getValue().contains("[SUIVI]"));
        assertTrue(debug.getValue().contains("mailStatus=SENT"));
        assertTrue(debug.getValue().contains("mailSent=true"));
        assertTrue(debug.getValue().contains("mailTarget=smtp.gmail.com:587"));
        assertTrue(debug.getValue().contains("mailError=(aucune)"));
    }

    @Test
    void close_reportsMailUnavailableReasonInLogs() {
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());
        when(mailService.unavailabilityReason())
                .thenReturn("mot de passe d'application non configuré (spring.mail.password / MAIL_PASSWORD)");

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        assertEquals("MAIL_UNAVAILABLE", response.status());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("spring.mail.password")));
        verify(mailService, never()).sendWithAttachments(anyString(), anyString(), anyString(),
                anyBoolean(), any());

        String debug = captureSuiviDebug();
        assertTrue(debug.contains("mailStatus=MAIL_UNAVAILABLE"));
        assertTrue(debug.contains("mailSent=false"));
        assertTrue(debug.contains("mailError=service mail indisponible : mot de passe d'application non configuré"));
    }

    @Test
    void close_reportsSmtpErrorOriginInLogs() throws Exception {
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());
        doThrow(new IllegalStateException("Échec de l'envoi du mail",
                new jakarta.mail.AuthenticationFailedException("535-5.7.8 Username and Password not accepted")))
                .when(mailService).sendWithAttachments(anyString(), anyString(), anyString(),
                        anyBoolean(), any());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        assertEquals("SEND_FAILED", response.status());
        assertTrue(response.warnings().stream().anyMatch(w -> w.contains("Mail conseiller NON envoyé")));

        String debug = captureSuiviDebug();
        assertTrue(debug.contains("mailStatus=SEND_FAILED"));
        assertTrue(debug.contains("mailSent=false"));
        assertTrue(debug.contains("mailError=échec de l'envoi à " + ADVISOR + " via smtp.gmail.com:587"));
        assertTrue(debug.contains("AuthenticationFailedException — 535-5.7.8 Username and Password not accepted"));
    }

    @Test
    void close_logsTheSuiviAttemptEvenWhenTheAiFails() {
        // Panne de l'agent de synthèse (clé absente, fournisseur injoignable...) : la tentative doit
        // quand même laisser une trace [SUIVI] dans la page Logs, sinon on ne peut rien diagnostiquer.
        AIService failingAi = mock(AIService.class);
        when(failingAi.summarizeConversation(any(), any()))
                .thenThrow(new IllegalStateException("Clé API absente pour le fournisseur DEEPSEEK"));
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.DEEPSEEK);
        when(aiServiceFactory.get(any())).thenReturn(failingAi);
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service().close("s1", null));

        String debug = captureSuiviDebug();
        assertTrue(debug.contains("mailStatus=AI_FAILED"));
        assertTrue(debug.contains("mailSent=false"));
        assertTrue(debug.contains("Clé API absente pour le fournisseur DEEPSEEK"));
        verify(mailService, never()).sendWithAttachments(anyString(), anyString(), anyString(),
                anyBoolean(), any());
    }

    /** Récupère le bloc debug [SUIVI] journalisé pour la trace IA de clôture. */
    private String captureSuiviDebug() {
        ArgumentCaptor<String> debug = ArgumentCaptor.forClass(String.class);
        verify(aiLogService, times(1)).log(anyString(), anyString(), anyList(), anyInt(), anyLong(),
                any(), anyList(), anyString(), anyString(), debug.capture(), anyString(), anyString());
        return debug.getValue();
    }

    @Test
    void close_returnsNoConversationInsteadOf404_whenSessionUnknown() {
        // Backend redémarré / session restaurée par le navigateur : la clôture est un effet de bord
        // « best effort », elle ne doit PAS casser l'appel (ni envoyer quoi que ce soit).
        when(conversationService.find("inconnue")).thenReturn(null);

        SuiviModels.CloseConversationResponse response = service().close("inconnue", null);

        assertEquals("NO_CONVERSATION", response.status());
        assertNull(response.attachmentName());
        assertTrue(response.sentTo().isEmpty());
        assertTrue(response.warnings().stream().anyMatch(w -> w.toLowerCase().contains("aucune conversation")));
        verify(mailService, never()).sendWithAttachments(anyString(), anyString(), anyString(),
                anyBoolean(), any());
        verify(aiLogService, never()).log(anyString(), anyString(), anyList(), anyInt(), anyLong(),
                any(), anyList(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private ConversationClosureService service() {
        return new ConversationClosureService(conversationService, aiServiceFactory, productUrlIndex,
                productCatalogueService, new EmailAttachmentBuilder(), mailService, bankingDataRepository,
                financialAnalysisService, aiLogService, testObjectMapper(), marketingProperties(),
                mock(MarketingEventStore.class), mock(MarketingExtractionService.class),
                qualityChecks(), advisorDossiers(), new CommercialScoreService(financialAnalysisService),
                "Conseiller SG", ADVISOR, "Jean Martin", "txt", "https://particuliers.sg.fr/vos-rendez-vous",
                "https://particuliers.sg.fr", true, DEMO_PHONE);
    }

    /**
     * Dossier évaluable : store pointant vers le répertoire de test (aucune écriture dans ./data) et
     * lien d'évaluation absolu — le mail conseiller doit contenir le lien vers le dossier.
     */
    @Test
    void close_addsTheCommercialScoreAndTheCallLinkToTheAdvisorEmail() {
        // Le score de sens commercial sert au conseiller à PRIORISER sa relance : il est ajouté au mail
        // conseiller (jamais au brouillon client), avec son explication courte et le lien d'appel.
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        SuiviModels.CloseConversationResponse response = service().close("s1", null);

        String body = response.advisorEmail().body();
        assertTrue(body.contains("**Score de sens commercial : "),
                "Le mail conseiller porte le score de sens commercial, en gras (Markdown du projet)");
        assertTrue(UrlLinkRenderer.toHtml(body).contains("<strong>Score de sens commercial :"),
                "Dans le mail HTML, le score apparaît en gras");
        assertFalse(UrlLinkRenderer.toText(body).contains("**"),
                "Le mail en texte brut ne contient aucun astérisque de gras");
        assertTrue(body.contains("/100 — "),
                "Le score est lisible sous la forme « 72/100 — Priorité haute »");
        assertTrue(body.contains("Pourquoi ce score :"),
                "Le score est expliqué en une phrase courte");
        assertTrue(body.contains("Contacter le client : [URL|Appeler le client|tel:" + DEMO_PHONE + "]"),
                "Le mail conseiller propose le lien d'appel du client (numéro de configuration)");
        // Le score et le téléphone sont des informations INTERNES du conseiller.
        assertFalse(response.preparedCustomerEmail().body().contains("Score de sens commercial"),
                "Le brouillon client ne doit jamais contenir le score commercial");
        assertFalse(response.preparedCustomerEmail().body().contains(DEMO_PHONE),
                "Le brouillon client ne doit jamais contenir le numéro de téléphone");
    }

    @Test
    void close_persistsTheDossierUsableByTheCallCenterDirectory() {
        // Le dossier persisté porte de quoi alimenter l'ANNUAIRE du centre d'appels : client, catégorie,
        // titre, score et transcript — sans jamais exposer le brouillon client dans la page.
        when(aiServiceFactory.defaultProvider()).thenReturn(AIModels.AIProvider.MOCK);
        when(aiServiceFactory.get(any())).thenReturn(new MockAIService());
        when(conversationService.find("s1")).thenReturn(conversationWithTousRisques());

        service().close("s1", null);

        com.coach.financier.model.AdvisorFeedbackModels.AdvisorDossier dossier =
                new com.coach.financier.service.AdvisorDossierStore(advisorFeedbackProperties(),
                        testObjectMapper()).findBySession("s1").orElseThrow();
        assertTrue(dossier.score() != null && dossier.score().score() != null,
                "Le dossier persisté porte le score de sens commercial");
        assertNotNull(dossier.client(), "Le dossier persisté porte l'identité métier affichée dans l'annuaire");
        assertEquals("ASSURANCE", dossier.client().category(),
                "Catégorie déduite de la famille de l'offre présentée (assurance auto)");
        assertFalse(dossier.transcript().isEmpty(), "Le transcript est conservé avec le dossier");
    }

    private static com.coach.financier.service.AdvisorDossierService advisorDossiers() {
        return new com.coach.financier.service.AdvisorDossierService(
                new com.coach.financier.service.AdvisorDossierStore(advisorFeedbackProperties(),
                        testObjectMapper()),
                mock(com.coach.financier.service.AdvisorFeedbackStore.class),
                advisorFeedbackProperties(), "http://localhost:9898");
    }

    /** Configuration du module Feedback Conseiller (dossier de test). */
    private static com.coach.financier.config.AdvisorFeedbackProperties advisorFeedbackProperties() {
        return new com.coach.financier.config.AdvisorFeedbackProperties(true, false,
                "./target/advisor-feedback-test", "salt", 1000, 5, "advisor-feedback-v1");
    }

    /**
     * Contrôles qualité : stub qui n'écrit rien sur disque (les contrôles eux-mêmes sont testés dans
     * {@code CoachQualityCheckServiceTest}) — la clôture doit rester indépendante du module Qualité.
     */
    private static CoachQualityCheckService qualityChecks() {
        return new CoachQualityCheckService(qualityProperties(), mock(QualityCheckStore.class),
                new ProjectProductMappingService(), mock(ProductUrlIndex.class), new AILogService(),
                "https://particuliers.sg.fr/vos-rendez-vous");
    }

    /** Configuration qualité par défaut (contrôles standards, sévérités par défaut). */
    private static com.coach.financier.config.QualityProperties qualityProperties() {
        return new com.coach.financier.config.QualityProperties(true, false, "./target/quality-test", "salt",
                1000, 30, 10, "quality-report-v1", "", "", "", "", "", "", "");
    }

    /** Configuration marketing par défaut (module activé, aucun événement persisté dans les tests). */
    private static com.coach.financier.config.MarketingProperties marketingProperties() {
        return new com.coach.financier.config.MarketingProperties(true, false, "./target/marketing-test", "salt",
                "2000,5000,10000,15000,30000", "marketing-events-v1", "marketing-extractor-v1",
                1, 0, 2, 3, 2, 4, 5, -5);
    }

    /** ObjectMapper aligné sur JacksonConfig (module java.time pour sérialiser Instant, LocalDate...). */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static ConversationModels.Conversation conversationWithTousRisques() {
        ConversationModels.Conversation conversation = new ConversationModels.Conversation("s1");
        conversation.addMessage("user", "Quelles sont les garanties de l'assurance auto Tous Risques ?");
        conversation.addMessage("assistant", "L'Assurance Auto SG – Tous Risques couvre les dommages tous accidents.");
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("id", PRODUCT_ID);
        compact.put("name", "Assurance Auto SG – Tous Risques");
        compact.put("family", "INSURANCE_AUTO");
        conversation.addDiscussedProducts(new ArrayList<>(List.of(compact)));
        return conversation;
    }

    private static com.fasterxml.jackson.databind.JsonNode customerNode() {
        try {
            return testObjectMapper().readTree(
                    "{\"customer\":{\"customerId\":\"DEMO001\",\"mail\":\"client@demo.test\"}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FinancialSummary summary() {
        return new FinancialSummary(0, LocalDate.now(), LocalDate.now(), 0, 0, 0, 0, 0, 0, 0,
                1516.89, 14695.18, 956, 0.07, 0.12, 0, 1200, 900, "", 0, Map.of(), 0.1211, "juin – août 2026");
    }
}
