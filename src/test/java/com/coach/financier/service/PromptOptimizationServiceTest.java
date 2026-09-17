package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.DeepSeekService;
import com.coach.financier.ai.MockAIService;
import com.coach.financier.ai.OpenAIService;
import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.service.PromptOptimizationService.StartRequest;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.AdvisorFeedbackModels;
import com.coach.financier.model.ConfidenceLevel;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialIntent;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.MarketingModels;
import com.coach.financier.model.ProjectType;
import com.coach.financier.model.PromptOptimizationModels;
import com.coach.financier.model.QualityModels;
import com.coach.financier.model.SuiviModels;
import com.coach.financier.repository.BankingDataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration de l'atelier : reproductibilité (prompt FIGÉ rejoué, jamais le prompt courant du disque),
 * machine d'état, arrêt gracieux, avis humain prioritaire, validation backend des zones et erreurs.
 * <p>
 * Aucun appel réseau : un faux fournisseur IA scripte la boucle. Aucun fichier de production n'est
 * modifié (les campagnes vivent dans un répertoire temporaire ; la promotion n'est jamais exécutée ici).
 */
class PromptOptimizationServiceTest {

    private static final String QUESTION = "Je souhaite financer une voiture d'occasion à 15000 euros.";
    private static final String DATA_DIR = "./data";

    @TempDir
    Path tempDir;

    private FakeAI ai;
    private FakeAIServiceFactory factory;
    private PromptOptimizationService service;
    private PromptOptimizationStore store;
    private DataRequestService dataRequests;

    @BeforeEach
    void setUp() throws Exception {
        ai = new FakeAI();
        factory = new FakeAIServiceFactory(ai);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);        PromptOptimizationProperties properties =
                new PromptOptimizationProperties(true, false, tempDir.toString(), 50, 20000, "sel-de-test");
        store = new PromptOptimizationStore(properties, mapper);
        ProjectProductMappingService mapping = new ProjectProductMappingService();
        BankingDataRepository banking =
                new BankingDataRepository(mapper, DATA_DIR + "/banking_demo_normalized.json");
        dataRequests = new DataRequestService(mapper, DATA_DIR, true);
        CoachContextBuilder contextBuilder = new CoachContextBuilder(
                new FinancialAnalysisService(banking),
                dataRequests,
                new FinancialSynthesisStore(mapper, DATA_DIR + "/synthese_financier.json"),
                new ProductCatalogueService(mapper, mapping, DATA_DIR),
                mapping, banking, mapper);
        service = new PromptOptimizationService(properties, store, new PromptZoneService(), contextBuilder,
                factory, new AILogService(), new AgentPromptStore(),
                new AgentPromptHistoryStore(properties, new PromptZoneService(), mapper), dataRequests, mapper);
    }

    // --- Démarrage ---------------------------------------------------------------------------------

    @Test
    void startRefusesInvalidRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("credit_conso", QUESTION, 0, null, AIModels.AIProvider.DEEPSEEK)));
        assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("credit_conso", QUESTION, 51, null, AIModels.AIProvider.DEEPSEEK)));
        assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("credit_conso", "   ", 3, null, AIModels.AIProvider.DEEPSEEK)));
        assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("agent-inexistant", QUESTION, 3, null, AIModels.AIProvider.DEEPSEEK)));

        // Le mode démo ne dépend pas du prompt : une campagne y serait dénuée de sens.
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("credit_conso", QUESTION, 3, null, AIModels.AIProvider.MOCK)));
        assertTrue(error.getMessage().contains("fournisseur IA réel"));
    }

    @Test
    void startRefusesTheGenericAgentSpecialisedZone() {
        var zone = service.describeZone(com.coach.financier.ai.AgentFiles.agentFor("generic"),
                PromptOptimizationModels.ZONE_AGENT);

        assertFalse(zone.optimizable());
        assertTrue(zone.error().contains("agent principal"));
    }

    @Test
    void startFreezesTheReferenceSnapshot() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));

        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, campaign.status());
        assertEquals("credit_conso", campaign.agentId());
        assertEquals("credit-conso.txt", campaign.zoneFile());
        assertEquals(2, campaign.remainingIterations());
        assertEquals(50, campaign.maxIterations());
        assertEquals("V0", campaign.basePromptVersion());

        var snapshot = service.snapshot(campaign.campaignId());
        assertEquals(QUESTION, snapshot.question());
        assertEquals("credit-conso.txt", snapshot.zoneFile());
        assertTrue(snapshot.initialEditableSection().contains("Crédit à la consommation"),
                "la zone V0 est celle du fichier de l'agent");
        assertFalse(snapshot.frozenSystemPrompt().contains("[[["), "aucun marqueur envoyé au LLM");
        assertTrue(snapshot.frozenSystemPrompt().contains("CONTINUITÉ DE CONVERSATION"),
                "l'agent principal est figé dans le prompt de référence");
        assertFalse(snapshot.frozenTemplate().isBlank(), "le gabarit est figé");
        assertFalse(snapshot.frozenPrincipal().isBlank(), "l'agent principal est figé");
        assertNotNull(snapshot.classification(), "la classification est GELÉE (jamais recalculée)");
        assertTrue(snapshot.providedData().size() > 1, "les données jointes réellement envoyées sont figées");
        assertFalse(snapshot.snapshotHash().isBlank());
        assertEquals(List.of("V0"), service.versions(campaign.campaignId()).stream()
                .map(PromptOptimizationModels.PromptVersion::version).toList());
    }

    @Test
    void startingANewCampaignClosesThePreviousOne() {
        // L'IHM ne propose pas de « reprendre une campagne » : une nouvelle campagne ne doit donc JAMAIS
        // être bloquée par une précédente laissée en cours (rechargement de page, onglet fermé).
        var first = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        var second = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));

        assertEquals(PromptOptimizationModels.CAMPAIGN_CANCELLED, service.campaign(first.campaignId()).status(),
                "la campagne précédente est clôturée (statut CANCELLED)");
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, second.status());
        assertEquals(2, service.campaigns().size(), "rien n'est supprimé : les deux campagnes restent lisibles");
        assertEquals(1, service.versions(first.campaignId()).size(), "ses données restent consultables");
        assertThrows(IllegalStateException.class, () -> service.iterate(first.campaignId()),
                "on ne peut plus itérer sur une campagne remplacée");
    }

    // --- Itérations --------------------------------------------------------------------------------

    @Test
    void iterationReplaysTheFrozenPromptAndProducesANewVersion() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        var iteration = service.iterate(campaignId);

        assertEquals(1, iteration.iterationNumber());
        assertEquals("V0", iteration.promptVersion());
        assertEquals("V1", iteration.resultingVersion());
        assertEquals("Réponse du Coach pour la question de test.", iteration.coachResponse());
        assertNotNull(iteration.controllerFeedback());
        assertTrue(iteration.editorResult().updated());
        assertEquals(3, ai.coachCalls + ai.controllerCalls + ai.editorCalls, "3 appels IA par itération");
        assertTrue(ai.lastSystemPrompt.contains("Crédit à la consommation"), "prompt de l'agent utilisé");
        assertFalse(ai.lastSystemPrompt.contains("[[["), "le LLM ne reçoit jamais les marqueurs de zone");

        var updated = service.campaign(campaignId);
        assertEquals(1, updated.completedIterations());
        assertEquals(1, updated.remainingIterations());
        assertEquals("V1", updated.currentCandidateVersion());
        assertEquals(4, updated.aiCalls(), "3 appels pour l'itération + 1 pour la classification gelée");
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, updated.status());
        assertEquals("ZONE V1", service.versions(campaignId).get(1).editableSection());
    }

    @Test
    void threeConsecutiveIterationsWithoutChangePauseTheCampaign() {
        // L'éditeur ne propose plus rien : le cycle doit s'arrêter AVANT d'avoir consommé toutes les itérations.
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_NO_CHANGE, "ZONE V0",
                List.of(), List.of(), List.of(), List.of(), false);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 5, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        service.iterate(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, service.campaign(campaignId).status(),
                "2 itérations sans progrès ne suffisent pas à arrêter la campagne");
        service.iterate(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, service.campaign(campaignId).status());

        service.iterate(campaignId);

        var stopped = service.campaign(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_PAUSED, stopped.status(),
                "3 itérations consécutives sans nouvelle version ⇒ mise en pause automatique");
        assertTrue(stopped.error().contains("Plateau"), stopped.error());
        assertEquals(3, service.iterations(campaignId).size(),
                "les itérations restantes du cycle ne sont PAS consommées");
        assertTrue(service.versions(campaignId).size() == 1, "aucune nouvelle version n'a été produite");

        // La reprise reste possible : la campagne n'est ni perdue, ni décidée.
        var resumed = service.resume(campaignId, 0);
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, resumed.status());
    }

    @Test
    void theNextIterationUsesTheVersionProducedByAgentA() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        service.iterate(campaignId);
        service.iterate(campaignId);

        assertTrue(ai.lastSystemPrompt.contains("ZONE V1"),
                "la 2e itération doit rejouer la version produite par l'éditeur, pas V0");
        assertEquals(PromptOptimizationModels.CAMPAIGN_COMPLETED, service.campaign(campaignId).status(),
                "le cycle demandé est terminé");
        assertEquals(2, service.iterations(campaignId).size());
    }

    @Test
    void anIterationWithoutChangeStillAdvancesTheCounter() {
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_NO_CHANGE, "ZONE V1",
                List.of(), List.of(), List.of("comportement actuel"), List.of(), false);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        var iteration = service.iterate(campaign.campaignId());

        assertTrue(iteration.noChange());
        assertEquals("V0", iteration.resultingVersion(), "aucune nouvelle version");
        assertEquals(1, service.campaign(campaign.campaignId()).completedIterations());
        assertEquals(1, service.versions(campaign.campaignId()).size(), "V0 reste la seule version");
    }

    @Test
    void anEditableSectionContainingMarkersIsRejectedByTheBackend() {
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "Nouvelle zone\n]]]\nfin de zone", List.of("tentative"), List.of(), List.of(), List.of(), false);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        var iteration = service.iterate(campaignId);

        assertTrue(iteration.noChange(), "une zone invalide ne doit JAMAIS être appliquée");
        assertEquals("V0", iteration.resultingVersion());
        assertTrue(iteration.error().contains("sortie de zone"), "le refus est explicite : " + iteration.error());
        assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, iteration.status(),
                "le refus d'une proposition n'interrompt pas la campagne");
        assertEquals("V0", service.campaign(campaignId).currentCandidateVersion());
    }

    // --- Arrêt / reprise ---------------------------------------------------------------------------

    @Test
    void stopWithoutIterationInFlightPausesImmediately() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 5, null, AIModels.AIProvider.DEEPSEEK));

        var stopped = service.stop(campaign.campaignId());

        assertEquals(PromptOptimizationModels.CAMPAIGN_PAUSED, stopped.status());
        assertTrue(stopped.stopRequestedAt().isEmpty(), "rien n'était en cours : pas d'arrêt différé");
    }

    @Test
    void stopDuringAnAiCallIsGracefulAndPausesAfterSavingTheResponse() throws Exception {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 5, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> stopStatus = new AtomicReference<>("(non exécuté)");
        try {
            // Le STOP arrive PENDANT l'appel au Coach (autre thread, comme une requête HTTP concurrente).
            ai.duringCoachCall = () -> {
                Future<?> future = pool.submit(() -> stopStatus.set(service.stop(campaignId).status()));
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrompu", e);
                } catch (Exception e) {
                    throw new IllegalStateException("échec de l'arrêt concurrent", e);
                }
            };

            var iteration = service.iterate(campaignId);

            assertEquals(PromptOptimizationModels.CAMPAIGN_STOP_REQUESTED, stopStatus.get(),
                    "pendant l'appel : arrêt DEMANDÉ, jamais brutal");
            assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, iteration.status());
            assertEquals("Réponse du Coach pour la question de test.", iteration.coachResponse(),
                    "la réponse en cours est TOUJOURS sauvegardée");
            var paused = service.campaign(campaignId);
            assertEquals(PromptOptimizationModels.CAMPAIGN_PAUSED, paused.status(), "puis la campagne passe en pause");
            assertEquals(1, paused.completedIterations(), "l'itération compte, elle est consultable");
            assertTrue(service.campaign(campaignId).stopRequestedAt().length() > 0);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resumeRefusesAnActiveCampaignAndTheCumulatedCeiling() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        var active = assertThrows(IllegalStateException.class, () -> service.resume(campaignId, 1));
        assertTrue(active.getMessage().contains("pause"));

        service.stop(campaignId);
        var ceiling = assertThrows(IllegalArgumentException.class, () -> service.resume(campaignId, 50));
        assertTrue(ceiling.getMessage().contains("CUMULÉES"), "le plafond cumulé est infranchissable");
    }

    @Test
    void resumeAppliesTheHumanFeedbackBeforeTheNextCoachCall() {
        // 2 itérations demandées : après la 1re, la campagne est encore RUNNING et peut être mise en pause.
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.iterate(campaignId);
        service.stop(campaignId);
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V2 issue de l'avis humain", List.of("avis appliqué"), List.of(), List.of(), List.of(), true);
        service.addHumanFeedback(campaignId, "Trop long : garder l'explication sur l'épargne.");

        var resumed = service.resume(campaignId, 1);

        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, resumed.status());
        assertEquals("V2", resumed.currentCandidateVersion(),
                "l'avis humain est appliqué par l'Agent A AVANT tout nouvel appel au Coach");
        assertEquals(1, resumed.completedIterations(), "une édition issue d'un avis ne consomme pas d'itération");
        assertEquals(3, resumed.requestedIterations(), "« continuer » ajoute un cycle au compteur cumulé (§33)");
        assertEquals("ZONE V2 issue de l'avis humain",
                service.versions(campaignId).stream()
                        .filter(version -> "V2".equals(version.version()))
                        .map(PromptOptimizationModels.PromptVersion::editableSection).findFirst().orElseThrow());

        service.iterate(campaignId);
        assertTrue(ai.lastSystemPrompt.contains("ZONE V2 issue de l'avis humain"),
                "la réponse suivante tient compte de l'avis humain");
        assertTrue(service.feedbacks(campaignId).get(0).applied(), "l'avis est marqué comme appliqué");
    }

    // --- Erreurs (§35) -----------------------------------------------------------------------------

    @Test
    void coachFailureStopsTheCampaignWithoutLosingPreviousIterations() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.iterate(campaignId);
        ai.coachFailure = new IllegalStateException("Fournisseur injoignable");

        var failed = service.iterate(campaignId);

        assertEquals(PromptOptimizationModels.ITERATION_ERROR, failed.status());
        assertTrue(failed.error().contains("Fournisseur injoignable"));
        var campaignState = service.campaign(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_ERROR, campaignState.status());
        assertEquals(PromptOptimizationService.STEP_COACH, campaignState.errorStep());
        assertEquals(2, service.iterations(campaignId).size(), "l'itération en échec est conservée elle aussi");
        assertEquals(1, service.iterations(campaignId).get(0).iterationNumber(),
                "les itérations déjà terminées restent consultables");
    }

    @Test
    void controllerFailureMarksTheCampaignWithTheFailingStep() {
        ai.controllerFailure = new IllegalStateException("Diagnostic illisible");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        service.iterate(campaign.campaignId());

        var campaignState = service.campaign(campaign.campaignId());
        assertEquals(PromptOptimizationService.STEP_CONTROLLER, campaignState.errorStep());
        assertEquals(PromptOptimizationModels.CAMPAIGN_ERROR, campaignState.status());
    }

    /**
     * Une demande de données INSATISFIABLE (le Coach réclame un fichier que le catalogue ne permet pas de
     * fournir) ne doit JAMAIS bloquer : c'est exactement le comportement du chat en production — l'itération est
     * conservée avec la réponse de repli (celle que le client recevrait), le motif est tracé dans `error`, et la
     * campagne reste utilisable (décision, promotion, conversation).
     */
    @Test
    void anUnresolvableDataRequestDegradesTheIterationWithoutBlocking() {
        ai.coachStatus = AIModels.AIStatus.NEED_DATA;
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        var iteration = service.iterate(campaign.campaignId());

        assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, iteration.status(),
                "la demande non satisfiable ne bloque plus l'itération");
        assertEquals(PromptOptimizationService.UNAVAILABLE_DATA_ANSWER, iteration.coachResponse(),
                "la réponse est celle du repli de PRODUCTION (celle que le client recevrait)");
        assertTrue(iteration.error().contains("NEED_DATA"), iteration.error());
        assertNotNull(iteration.controllerFeedback(), "Agent B juge la réponse de repli, comme toute réponse client");
        assertEquals(PromptOptimizationModels.CAMPAIGN_COMPLETED, service.campaign(campaign.campaignId()).status(),
                "la campagne reste utilisable (décision, promotion, conversation)");
    }

    @Test
    void agentBReceivesTheContentOfTheDataProvidedToTheCoach() throws Exception {
        ai.needsDataPaths = List.of("/data/catalogue/credit_conso.json");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        service.iterate(campaign.campaignId());

        Map<String, Object> context = ai.lastControllerContext;
        assertNotNull(context, "Agent B a bien été appelé");
        List<?> provided = (List<?>) context.get("providedData");
        assertNotNull(provided, "le contenu des données fournies est transmis au contrôleur");
        assertFalse(provided.isEmpty(), "au moins la fiche de l'agent est fournie");
        String flattened = new ObjectMapper().writeValueAsString(provided);
        assertTrue(flattened.contains("particuliers.sg.fr"),
                "Agent B peut vérifier les URL officielles avant de crier à l'invention (faux positif signalé)");
        assertEquals(provided.size(), ((List<?>) context.get("providedDataDescriptions")).size(),
                "les descriptions restent disponibles pour nommer les fichiers fournis");

        // MÊME PARITÉ pour l'ÉDITEUR : il écrit des règles applicables aux données réelles.
        Map<String, Object> editorContext = ai.lastEditorContext;
        assertNotNull(editorContext, "Agent A a bien été appelé");
        List<?> editorProvided = (List<?>) editorContext.get("providedData");
        assertNotNull(editorProvided, "le contenu des données fournies est aussi transmis à l'éditeur");
        assertEquals(provided.size(), editorProvided.size(), "mêmes données que le contrôleur");
        assertTrue(new ObjectMapper().writeValueAsString(editorProvided).contains("particuliers.sg.fr"),
                "l'éditeur peut vérifier l'existence des produits et URL officielles");
    }

    @Test
    void theContextIsCompletedWhenTheCoachAsksForAllowedDataThenAgentBJudgesTheClientAnswer() {
        ai.needsDataPaths = List.of("/data/catalogue/credit_conso.json");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        var before = service.snapshot(campaignId);

        var iteration = service.iterate(campaignId);

        assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, iteration.status(),
                "comme en production : données fournies puis réponse du Coach");
        assertFalse(iteration.coachResponse().isBlank(), "la réponse destinée au client existe bien");
        assertFalse(iteration.contextAddedData().isEmpty(), "les données ajoutées au contexte sont TRACÉES");
        assertNotNull(iteration.controllerFeedback(), "Agent B juge la réponse CLIENT (jamais la demande de données)");
        var completed = service.snapshot(campaignId);
        assertTrue(completed.providedData().size() > before.providedData().size(),
                "le fichier demandé est ajouté au contexte de référence");
        assertNotEquals(before.snapshotHash(), completed.snapshotHash(),
                "l'empreinte du contexte de référence est recalculée");

        // Le contexte enrichi est PERSISTÉ : l'itération suivante l'utilise sans rien recharger.
        service.resume(campaignId, 1);
        ai.needsDataPaths = List.of();
        int callsBefore = ai.coachCalls;
        var second = service.iterate(campaignId);
        assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, second.status());
        assertEquals(1, ai.coachCalls - callsBefore, "un seul appel Coach normal, sans complétion");
        assertTrue(second.contextAddedData().isEmpty(), "aucune donnée ajoutée : le contexte est déjà complet");
        assertEquals(completed.providedData(), service.snapshot(campaignId).providedData());
    }

    @Test
    void iterateIsRefusedWhenPausedOrWithoutRemainingIterations() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.stop(campaignId);
        assertThrows(IllegalStateException.class, () -> service.iterate(campaignId));

        service.resume(campaignId, 0);
        service.iterate(campaignId);
        var error = assertThrows(IllegalStateException.class, () -> service.iterate(campaignId));
        assertTrue(error.getMessage().contains("Aucune itération possible"));
    }

    // --- Décisions humaines -------------------------------------------------------------------------

    @Test
    void promotionRefusesAnUnknownVersionAndAnActiveCampaign() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.iterate(campaignId);

        assertThrows(IllegalArgumentException.class, () -> service.promoteVersion(campaignId, "V9"));

        service.resume(campaignId, 0);
        var active = assertThrows(IllegalStateException.class, () -> service.promoteVersion(campaignId, "V1"));
        assertTrue(active.getMessage().contains("pendant que la campagne tourne"));
        // Aucun fichier de production n'a été touché (la promotion n'est jamais exécutée dans ces tests).
    }

    @Test
    void promotionPreservesTheLineEndingsOfTheOriginalPromptFile() {
        // Les prompts du POC sont en CRLF : la promotion ne doit réécrire que la ZONE, pas tout le fichier.
        String original = "PARTIE PROTÉGÉE\r\n[[[\r\nZONE V0\r\n]]]\r\nSUITE\r\n";
        String composed = "PARTIE PROTÉGÉE\n[[[\nZONE V1\n]]]\nSUITE\n";

        String promoted = PromptOptimizationService.preserveLineEndings(original, composed);
        assertEquals("PARTIE PROTÉGÉE\r\n[[[\r\nZONE V1\r\n]]]\r\nSUITE\r\n", promoted);
        assertFalse(promoted.replace("\r\n", "").contains("\n"), "aucune fin de ligne LF isolée");

        // Un fichier en LF (déploiement Linux) reste en LF.
        assertEquals("a\nc\n", PromptOptimizationService.preserveLineEndings("a\nb\n", "a\nc\n"));
    }

    @Test
    void humanFeedbackRefusesAnEmptyText() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        assertThrows(IllegalArgumentException.class, () -> service.addHumanFeedback(campaign.campaignId(), "  "));
    }

    @Test
    void rejectKeepsEverythingAndClosesTheCampaign() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.iterate(campaignId);

        var rejected = service.reject(campaignId);

        assertEquals(PromptOptimizationModels.CAMPAIGN_REJECTED, rejected.status());
        assertEquals(1, service.iterations(campaignId).size(), "rien n'est supprimé : tout reste consultable");
        assertTrue(rejected.promotedVersion().isEmpty(), "aucune promotion n'a eu lieu");
    }

    @Test
    void zonesAreDescribedForEveryAgent() {
        var zones = service.zones();

        assertEquals(7, zones.size(), "les agents de coach déclarés dans agents.json");
        assertTrue(zones.stream().allMatch(PromptOptimizationService.ZoneInfo::optimizable),
                "tous les agents sont pourvus d'une zone éditable valide");
        var generic = zones.stream().filter(zone -> "generic".equals(zone.agentId())).findFirst().orElseThrow();
        assertEquals(PromptOptimizationModels.ZONE_PRINCIPAL, generic.zoneKey());
        assertEquals("principal.txt", generic.zoneFile());
    }

    // --- §46 : compteurs, versions, protection du prompt de production ---------------------------------

    @Test
    void startAcceptsOneIterationAndTheCumulatedCeiling() {
        var single = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        assertEquals(1, single.requestedIterations(), "1 itération est une campagne valide");
        assertEquals(1, single.remainingIterations());

        // Un autre agent reste optimisable en parallèle (la contrainte porte sur UN agent à la fois).
        var ceiling = service.start(new StartRequest("epargne", QUESTION, 50, null, AIModels.AIProvider.DEEPSEEK));
        assertEquals(50, ceiling.requestedIterations(), "50 = plafond backend accepté");
        assertEquals(50, ceiling.maxIterations());
        assertEquals(50, ceiling.remainingIterations());
    }

    @Test
    void remainingIterationsFollowTheCycleUntilCompletion() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V1 : explication liée aux données", List.of("personnalisation"), List.of(), List.of(),
                List.of(), false);
        service.iterate(campaignId);
        var afterFirst = service.campaign(campaignId);
        assertEquals(1, afterFirst.completedIterations());
        assertEquals(1, afterFirst.remainingIterations(), "le compteur restant décrémente exactement");
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, afterFirst.status());

        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V2 : formulation plus concise", List.of("concision"), List.of(), List.of(), List.of(), false);
        service.iterate(campaignId);

        var finished = service.campaign(campaignId);
        assertEquals(2, finished.completedIterations());
        assertEquals(0, finished.remainingIterations());
        assertEquals(PromptOptimizationModels.CAMPAIGN_COMPLETED, finished.status(), "fin de campagne : COMPLETED");
        assertEquals("V2", finished.currentCandidateVersion());
        var error = assertThrows(IllegalStateException.class, () -> service.iterate(campaignId));
        assertTrue(error.getMessage().contains("Aucune itération possible"),
                "aucune itération ne peut démarrer au-delà du cycle demandé");
        assertEquals(List.of("V0", "V1", "V2"), service.versions(campaignId).stream()
                .map(PromptOptimizationModels.PromptVersion::version).toList(),
                "TOUTES les versions restent consultables");
    }

    @Test
    void protectedPartsAreIdenticalInEveryVersion() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V1 : zone réécrite par l'Agent A", List.of("changement"), List.of(), List.of(), List.of(), false);
        service.iterate(campaignId);
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V2 : zone réécrite une seconde fois", List.of("changement"), List.of(), List.of(), List.of(),
                false);
        service.iterate(campaignId);

        String reference = null;
        for (PromptOptimizationModels.PromptVersion version : service.versions(campaignId)) {
            String withoutZone = service.promptFor(campaignId, version.version())
                    .replace(version.editableSection(), "");
            if (reference == null) {
                reference = withoutZone;
            }
            assertEquals(reference, withoutZone,
                    "les parties PROTÉGÉES sont identiques dans toutes les versions (V0 → VN) — "
                            + "seule la zone éditable change");
        }
        assertNotNull(reference);
    }

    @Test
    void theSnapshotNeverChangesBetweenTheFirstAndLastVersion() throws Exception {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        Path snapshotFile = tempDir.resolve("campaigns").resolve(campaignId).resolve("snapshot.json");
        String before = Files.readString(snapshotFile);
        var beforeSnapshot = service.snapshot(campaignId);

        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                "ZONE V1", List.of("changement"), List.of(), List.of(), List.of(), false);
        service.iterate(campaignId);
        service.addHumanFeedback(campaignId, "Formulation trop longue.");

        assertEquals(before, Files.readString(snapshotFile),
                "le fichier de snapshot n'est JAMAIS réécrit : la campagne rejoue toujours les mêmes conditions");
        var after = service.snapshot(campaignId);
        assertEquals(beforeSnapshot.snapshotHash(), after.snapshotHash());
        assertEquals(beforeSnapshot.question(), after.question());
        assertEquals(beforeSnapshot.initialEditableSection(), after.initialEditableSection());
        assertEquals(beforeSnapshot.frozenSystemPrompt(), after.frozenSystemPrompt());
        assertEquals(beforeSnapshot.promptHash(), after.promptHash());
        assertEquals(beforeSnapshot.providedData(), after.providedData());
        assertEquals(beforeSnapshot.fixedPrefix(), after.fixedPrefix());
        assertEquals(beforeSnapshot.fixedSuffix(), after.fixedSuffix());
    }

    @Test
    void iterationsNeverTouchTheProductionPromptFile() {
        String file = "credit-conso.txt";
        String before = com.coach.financier.ai.AgentFiles.readPromptOrDefault(file, "");

        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        service.iterate(campaign.campaignId());

        assertEquals(before, com.coach.financier.ai.AgentFiles.readPromptOrDefault(file, ""),
                "aucune itération ne modifie le prompt de PRODUCTION : seule une promotion HUMAINE le fait (§49)");
    }

    // --- §46 : erreurs de l'Agent A et reprise après erreur --------------------------------------------

    @Test
    void editorFailureKeepsThePreviousVersionAndMarksTheStep() {
        ai.editorFailure = new IllegalStateException("Zone illisible");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();

        var iteration = service.iterate(campaignId);

        assertEquals(PromptOptimizationModels.ITERATION_ERROR, iteration.status());
        assertTrue(iteration.error().contains("Zone illisible"));
        var state = service.campaign(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_ERROR, state.status());
        assertEquals(PromptOptimizationService.STEP_EDITOR, state.errorStep());
        assertEquals("V0", state.currentCandidateVersion(), "aucune version n'a été produite");
        assertEquals(List.of("V0"), service.versions(campaignId).stream()
                .map(PromptOptimizationModels.PromptVersion::version).toList());
    }

    @Test
    void anErroredCampaignCanBeResumedAfterTheCauseIsFixed() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        ai.coachFailure = new IllegalStateException("Fournisseur injoignable");
        service.iterate(campaignId);
        assertEquals(PromptOptimizationModels.CAMPAIGN_ERROR, service.campaign(campaignId).status());

        ai.coachFailure = null;
        var resumed = service.resume(campaignId, 0);

        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, resumed.status(), "une campagne en erreur est reprenable");
        assertTrue(resumed.error().isEmpty(), "l'erreur est effacée : la cause a été corrigée");
        assertTrue(resumed.errorStep().isEmpty());
        assertEquals(1, resumed.remainingIterations(), "l'itération en échec a été conservée dans l'historique");

        var retried = service.iterate(campaignId);
        assertEquals(PromptOptimizationModels.ITERATION_COMPLETED, retried.status());
        assertEquals(PromptOptimizationModels.CAMPAIGN_COMPLETED, service.campaign(campaignId).status());
        assertEquals(2, service.iterations(campaignId).size(), "l'échec est conservé ET la reprise est tracée");
    }

    @Test
    void aSecondResumeIsRefusedAndStartsNoExtraIteration() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 4, null, AIModels.AIProvider.DEEPSEEK));
        String campaignId = campaign.campaignId();
        service.stop(campaignId);

        service.resume(campaignId, 0);
        int callsAfterResume = ai.coachCalls;

        var refused = assertThrows(IllegalStateException.class, () -> service.resume(campaignId, 0));

        assertTrue(refused.getMessage().contains("pause"));
        assertEquals(callsAfterResume, ai.coachCalls, "un double REPRENDRE ne lance aucune itération");
        assertTrue(service.iterations(campaignId).isEmpty());
        assertEquals(0, ai.editorCalls, "aucun avis humain en attente : l'Agent A n'est pas appelé");
    }

    // --- §46 : un fournisseur PAR étape (coach / Agent B / Agent A) -------------------------------------

    @Test
    void eachStepUsesItsOwnProvider() {
        FakeAI gpt = new FakeAI();
        FakeAI deepseek = new FakeAI();
        factory.route(AIModels.AIProvider.GPT, gpt);
        factory.route(AIModels.AIProvider.DEEPSEEK, deepseek);

        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null,
                AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.GPT, AIModels.AIProvider.GPT));
        service.iterate(campaign.campaignId());

        assertEquals(1, deepseek.coachCalls, "le coach répond avec SON fournisseur");
        assertEquals(0, gpt.coachCalls);
        assertEquals(1, gpt.controllerCalls, "l'Agent B diagnostique avec le sien");
        assertEquals(0, deepseek.controllerCalls);
        assertEquals(1, gpt.editorCalls, "l'Agent A réécrit avec le sien");
        assertEquals(0, deepseek.editorCalls);

        var stored = service.campaign(campaign.campaignId());
        assertEquals("DEEPSEEK", stored.provider());
        assertEquals("GPT", stored.controllerProvider());
        assertEquals("GPT", stored.editorProvider());

        var iteration = service.iterations(campaign.campaignId()).get(0);
        assertEquals("DEEPSEEK", iteration.provider(), "chaque itération trace le routage utilisé");
        assertEquals("GPT", iteration.controllerProvider());
        assertEquals("GPT", iteration.editorProvider());
    }

    @Test
    void aSingleProviderAppliesToTheThreeSteps() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null,
                AIModels.AIProvider.DEEPSEEK));

        assertEquals("DEEPSEEK", campaign.provider());
        assertEquals("DEEPSEEK", campaign.controllerProvider(), "un seul fournisseur ⇒ les trois étapes");
        assertEquals("DEEPSEEK", campaign.editorProvider());

        service.iterate(campaign.campaignId());
        assertEquals(1, ai.coachCalls);
        assertEquals(1, ai.controllerCalls);
        assertEquals(1, ai.editorCalls);
    }

    @Test
    void aDemoProviderIsRefusedForAnyOfTheThreeSteps() {
        List<StartRequest> requests = List.of(
                new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.MOCK),
                new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK,
                        AIModels.AIProvider.MOCK, AIModels.AIProvider.DEEPSEEK),
                new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK,
                        AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.MOCK));

        for (StartRequest request : requests) {
            var error = assertThrows(IllegalArgumentException.class, () -> service.start(request));
            assertTrue(error.getMessage().contains("fournisseur IA réel"), error.getMessage());
        }
    }

    @Test
    void aCampaignSavedBeforeTheProviderRoutingFallsBackToTheCoachProvider() throws Exception {
        Path dir = tempDir.resolve("campaigns").resolve("camp-herite");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("campaign.json"), """
                {"campaignId":"camp-herite","agentId":"credit_conso","agentLibelle":"Crédit à la consommation",
                 "zoneKey":"agent","zoneFile":"credit-conso.txt","status":"PAUSED","question":"Question héritée",
                 "requestedIterations":2,"completedIterations":1,"maxIterations":50,"snapshotId":"snap-herite",
                 "basePromptVersion":"V0","currentCandidateVersion":"V1","retainedVersions":[],"promotedVersion":"",
                 "provider":"DEEPSEEK","model":"","aiCalls":4,"totalPromptChars":10,"totalDurationMs":20,
                 "stopRequestedAt":"","pausedAt":"","error":"","errorStep":"",
                 "createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-01T10:00:00Z"}
                """);

        var campaign = store.require("camp-herite");

        assertEquals("DEEPSEEK", campaign.provider());
        assertEquals("DEEPSEEK", campaign.controllerProvider(), "campagne antérieure : repli sur le coach");
        assertEquals("DEEPSEEK", campaign.editorProvider());
    }

    // --- Fil de conversation : la mémoire de l'atelier -----------------------------------------------

    /**
     * Une promotion enregistre l'échange dans le fil (question + réponse de la version promue). Le test
     * simule cette écriture — comme le fait {@code promoteVersion} — parce qu'une promotion réelle réécrit
     * le prompt de PRODUCTION (aucun test ne doit modifier {@code ./agent}) : la partie testée ici est la
     * REPRISE du fil par le cycle suivant, pas l'écriture du fichier de prompt.
     */
    private String seedExchange(PromptOptimizationModels.Campaign campaign, String answer, String version) {
        String threadId = store.threadOf(campaign.campaignId()).orElseThrow().threadId();
        store.saveThread(store.thread(threadId).orElseThrow()
                .withExchange(campaign.campaignId(), campaign.question(), answer, version));
        return threadId;
    }

    @Test
    void startOpensAConversationThreadAndAttachesTheCampaign() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        var thread = store.threadOf(campaign.campaignId()).orElseThrow();

        assertEquals("credit_conso", thread.agentId());
        assertEquals(PromptOptimizationModels.ZONE_AGENT, thread.zoneKey());
        assertEquals(List.of(campaign.campaignId()), thread.campaignIds());
        assertTrue(thread.empty(), "aucun échange n'entre dans la mémoire avant une promotion");
        assertTrue(thread.history().isEmpty(), "le premier cycle démarre sans mémoire");
        assertEquals(1, store.threads().size());
    }

    @Test
    void aFollowUpCycleReplaysTheValidatedHistoryAndThePreviousProject() {
        var first = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        String threadId = seedExchange(first, "Réponse de la version promue V2.", "V2");
        ai.coachCalls = 0;
        ai.lastHistory = List.of();

        var second = service.start(new StartRequest("credit_conso",
                "Et si j'allongeais la durée à 60 mois ?", 1, null, AIModels.AIProvider.DEEPSEEK, threadId));

        // 1) L'historique est FIGÉ dans le snapshot du nouveau cycle (reproductibilité).
        var frozenHistory = store.snapshot(second.campaignId()).orElseThrow().history();
        assertEquals(2, frozenHistory.size());
        assertEquals("user", frozenHistory.get(0).role());
        assertEquals(QUESTION, frozenHistory.get(0).content());
        assertEquals("assistant", frozenHistory.get(1).role());
        assertEquals("Réponse de la version promue V2.", frozenHistory.get(1).content());

        // 2) Il est réellement TRANSMIS au Coach (même contrat que le chat).
        service.iterate(second.campaignId());
        assertEquals(2, ai.lastHistory.size());
        assertEquals("Réponse de la version promue V2.", ai.lastHistory.get(1).content());
        assertNotNull(ai.lastHistory.get(0).timestamp(), "l'historique rejoué est daté");

        // 3) La question de suivi reste dans le PROJET de l'échange précédent (comme dans le chat).
        assertTrue(ai.lastCurrentProjectDescription.contains("type = VEHICLE"),
                "le classifieur reçoit le projet précédent : " + ai.lastCurrentProjectDescription);
        assertTrue(ai.lastCurrentProjectDescription.contains("15000"),
                "le montant connu du projet est transmis : " + ai.lastCurrentProjectDescription);

        // 4) Le fil enchaîne les deux campagnes et ne duplique pas l'échange.
        var thread = store.thread(threadId).orElseThrow();
        assertEquals(List.of(first.campaignId(), second.campaignId()), thread.campaignIds());
        assertEquals(1, thread.exchanges());
        assertEquals(2, thread.turns().size());
    }

    @Test
    void startRefusesAnUnknownOrForeignConversationThread() {
        var unknown = assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK, "th-inexistant")));
        assertTrue(unknown.getMessage().contains("Fil de conversation inconnu"), unknown.getMessage());

        store.saveThread(new PromptOptimizationModels.ConversationThread("th-epargne", "epargne", "Épargne",
                PromptOptimizationModels.ZONE_AGENT, List.of(), List.of(),
                "2026-09-17T08:00:00Z", "2026-09-17T08:00:00Z"));
        var foreign = assertThrows(IllegalArgumentException.class, () -> service.start(
                new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK, "th-epargne")));
        assertTrue(foreign.getMessage().contains("appartient à"), foreign.getMessage());
        assertTrue(store.threads().size() >= 1, "un démarrage refusé n'écrit rien");
    }

    @Test
    void theReplayedAnswerCanBeCorrectedByTheHuman() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String threadId = seedExchange(campaign, "Réponse à corriger", "V1");

        var updated = service.updateTurn(threadId, 1, "  Réponse corrigée  ");

        assertEquals("Réponse corrigée", updated.history().get(1).content());
        assertEquals("Réponse corrigée", service.thread(threadId).history().get(1).content(),
                "la correction est persistée : c'est elle qui sera rejouée");
        assertEquals("user", service.thread(threadId).history().get(0).role());

        assertThrows(IllegalArgumentException.class, () -> service.updateTurn(threadId, 5, "hors bornes"));
        assertThrows(IllegalArgumentException.class, () -> service.updateTurn(threadId, 0, "   "));
        assertThrows(IllegalArgumentException.class, () -> service.thread("th-inexistant"));
        assertThrows(IllegalArgumentException.class, () -> service.thread(""));
    }

    @Test
    void aNewCycleWithoutThreadStartsWithoutMemory() {
        var first = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        seedExchange(first, "Réponse promue", "V1");
        ai.lastHistory = List.of();

        var second = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        assertTrue(store.snapshot(second.campaignId()).orElseThrow().history().isEmpty(),
                "sans fil, le cycle démarre sans mémoire");
        service.iterate(second.campaignId());
        assertTrue(ai.lastHistory.isEmpty(), "le Coach ne reçoit aucune mémoire");
        assertEquals(2, store.threads().size(), "chaque cycle sans fil ouvre sa propre conversation");
        assertNotEquals(first.campaignId(), second.campaignId());
    }

    /**
     * ACCEPTER SANS CHANGEMENT : quand l'Agent A n'a rien proposé, aucune version nouvelle n'existe — et sans
     * cette acceptation l'humain resterait bloqué (aucune décision ⇒ aucune réponse de l'IA dans le fil ⇒
     * conversation impossible à enchaîner). La version acceptée est alors IDENTIQUE au prompt en production :
     * rien n'est écrit (ni sauvegarde, ni réécriture).
     * <p>
     * Le test ne peut pas corrompre le prompt de production : la zone recomposée depuis la version du snapshot
     * EST, par construction, celle du fichier courant — une écriture hypothétique serait identique. Le contenu
     * du fichier est malgré tout comparé avant/après pour vérifier la garantie « aucune écriture ».
     */
    @Test
    void aCampaignWithoutAnyProposedChangeCanStillBeAccepted() throws Exception {
        Path production = Path.of("agent", "credit-conso.txt");
        String before = Files.readString(production);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_NO_CHANGE,
                "zone inchangée", List.of(), List.of(), List.of(), List.of(), false);

        var iteration = service.iterate(campaign.campaignId());
        assertTrue(iteration.noChange());
        assertEquals(campaign.basePromptVersion(), iteration.resultingVersion());
        assertEquals(1, store.versions(campaign.campaignId()).size(), "aucune version nouvelle produite");

        var result = service.promoteVersion(campaign.campaignId(), campaign.basePromptVersion());

        assertEquals(PromptOptimizationModels.CAMPAIGN_ACCEPTED, result.campaign().status());
        assertEquals(campaign.basePromptVersion(), result.campaign().promotedVersion());
        assertTrue(result.backupFile().isEmpty(), "aucune écriture ⇒ aucune sauvegarde à restaurer");
        assertTrue(result.message().contains("Aucune modification"), result.message());
        assertEquals(before, Files.readString(production), "le prompt de production n'est pas réécrit");

        // La conversation est DÉBLOQUÉE : la réponse de l'IA est entrée dans le fil (c'est ce qui permet la suite).
        var thread = store.threadOf(campaign.campaignId()).orElseThrow();
        assertEquals(1, thread.exchanges());
        assertEquals(2, thread.history().size());
        assertEquals(ai.coachAnswer, thread.history().get(1).content());
        assertEquals(campaign.basePromptVersion(), thread.turns().get(1).version());
    }

    /**
     * La conversation doit contenir la réponse produite PAR la version acceptée — jamais celle qui a motivé le
     * changement (générée avec la version PRÉCÉDENTE), sinon la mémoire du cycle suivant décrirait un prompt qui
     * n'est plus en production.
     * <p>
     * L'éditeur « modifie » ici la zone en la réécrivant à l'IDENTIQUE : une nouvelle version est bien produite
     * (le numéro avance) mais le contenu ne change pas, donc la promotion n'écrit rien — le test ne touche pas au
     * prompt de production.
     */
    private String zoneRewrittenIdentical(String campaignId) {
        return store.snapshot(campaignId).orElseThrow().initialEditableSection();
    }

    @Test
    void theExchangeRecordsTheAnswerProducedByTheAcceptedVersion() {
        ai.coachAnswers = List.of("Réponse de la version V0.", "Réponse de la version V1.", "Réponse de la version V2.");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 2, null, AIModels.AIProvider.DEEPSEEK));
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                zoneRewrittenIdentical(campaign.campaignId()), List.of("zone réécrite à l'identique"),
                List.of(), List.of(), List.of(), false);

        var first = service.iterate(campaign.campaignId());
        var second = service.iterate(campaign.campaignId());
        assertEquals("V0", first.promptVersion());
        assertEquals("V1", first.resultingVersion());
        assertEquals("V1", second.promptVersion(), "la 2e itération a RÉELLEMENT répondu avec V1");
        int aiCallsBeforePromotion = service.campaign(campaign.campaignId()).aiCalls();

        var result = service.promoteVersion(campaign.campaignId(), "V1");

        var thread = store.threadOf(campaign.campaignId()).orElseThrow();
        assertEquals(2, thread.history().size());
        assertEquals("Réponse de la version V1.", thread.history().get(1).content(),
                "c'est la réponse de la version PROMUE, pas celle qui a motivé le changement");
        assertEquals("V1", thread.turns().get(1).version());
        assertEquals(2, ai.coachCalls, "la réponse de V1 existait déjà : aucun appel IA supplémentaire");
        assertEquals(aiCallsBeforePromotion, result.campaign().aiCalls(), "aucun appel IA ajouté");
        assertTrue(result.message().contains("produite par cette version"), result.message());
    }

    @Test
    void aVersionThatNeverAnsweredIsReplayedToProduceItsAnswer() {
        ai.coachAnswers = List.of("Réponse de la version V0.", "Réponse de la version V1.");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                zoneRewrittenIdentical(campaign.campaignId()), List.of("zone réécrite à l'identique"),
                List.of(), List.of(), List.of(), false);

        var only = service.iterate(campaign.campaignId());
        assertEquals("V1", only.resultingVersion());
        assertEquals(1, ai.coachCalls);
        int aiCallsBeforePromotion = service.campaign(campaign.campaignId()).aiCalls();

        var result = service.promoteVersion(campaign.campaignId(), "V1");

        assertEquals(2, ai.coachCalls, "V1 n'avait jamais répondu : le Coach est rejoué avec cette version");
        var thread = store.threadOf(campaign.campaignId()).orElseThrow();
        assertEquals("Réponse de la version V1.", thread.history().get(1).content());
        assertEquals(aiCallsBeforePromotion + 1, result.campaign().aiCalls(),
                "l'appel de rejeu est compté dans la campagne");
        assertTrue(result.message().contains("générée"), result.message());
    }

    // --- Doubles de test ------------------------------------------------------------------------------

    /** Faux fournisseur IA : scripte la boucle de l'atelier, sans réseau. */
    private static final class FakeAI implements AIService {
        IntentClassification classification = classification();
        String coachAnswer = "Réponse du Coach pour la question de test.";
        AIModels.AIStatus coachStatus = AIModels.AIStatus.ANSWER;
        PromptOptimizationModels.ControllerFeedback feedback = new PromptOptimizationModels.ControllerFeedback(
                PromptOptimizationModels.STATUS_NEEDS_IMPROVEMENT, "correcte mais générique",
                List.of("projet correctement compris"),
                List.of(new PromptOptimizationModels.Issue("INSUFFICIENT_PERSONALIZATION", "MEDIUM", "PROMPT",
                        "contexte financier peu exploité", "relier l'explication aux données utiles")),
                List.of("le ton pédagogique"), "renforcer l'utilisation sélective du contexte", false);
        PromptOptimizationModels.EditorResult edition = new PromptOptimizationModels.EditorResult(
                PromptOptimizationModels.EDITOR_UPDATED, "ZONE V1", List.of("précision ajoutée"),
                List.of("personnalisation insuffisante"), List.of("le ton pédagogique"), List.of(), false);
        RuntimeException coachFailure;
        RuntimeException controllerFailure;
        RuntimeException editorFailure;
        Runnable duringCoachCall;
        /**
         * Réponses du Coach consommées dans l'ORDRE des appels (la dernière est répétée si la liste est
         * épuisée) : permet de distinguer la réponse d'une version de celle d'une autre.
         */
        List<String> coachAnswers = List.of();
        /** Fichiers que le Coach demande au PREMIER appel (vide = réponse directe). */
        List<String> needsDataPaths = List.of();
        /** Contexte exact transmis à Agent B (contrôleur) au dernier appel. */
        Map<String, Object> lastControllerContext;
        /** Contexte exact transmis à Agent A (éditeur) au dernier appel. */
        Map<String, Object> lastEditorContext;
        /** Description du projet transmise au CLASSIFIEUR au dernier appel (mémoire du fil). */
        String lastCurrentProjectDescription = "";
        /** Historique transmis au COACH au dernier appel (exactement `conversationHistory`). */
        List<ConversationModels.Message> lastHistory = List.of();
        int coachCalls;
        int controllerCalls;
        int editorCalls;
        String lastSystemPrompt = "";

        private static IntentClassification classification() {
            IntentClassification c = new IntentClassification();
            c.setInScope(true);
            c.setIntent(FinancialIntent.FINANCING_REQUEST);
            c.setProjectType(ProjectType.VEHICLE);
            c.setAmount(new BigDecimal("15000"));
            c.setCurrency("EUR");
            c.setConfidence(ConfidenceLevel.HIGH);
            return c;
        }

        @Override
        public IntentClassification classifyIntent(String userMessage, String currentProjectDescription,
                                                  AIModels.AIProvider provider) {
            lastCurrentProjectDescription = currentProjectDescription == null ? "" : currentProjectDescription;
            return classification;
        }

        @Override
        public AIModels.AIAnswer answer(String customerMessage, AIModels.Classification classification,
                                        FinancialSummary financialSummary, Object bankingData,
                                        AIModels.BankingContextMode contextMode,
                                        Map<String, Object> additionalData,
                                        List<ConversationModels.Message> history, AIModels.AIProvider provider) {
            throw new UnsupportedOperationException(
                    "L'atelier doit rejouer un prompt FIGÉ (answerWithSystemPrompt), jamais le prompt du disque.");
        }

        @Override
        public AIModels.AIAnswer answerWithSystemPrompt(String systemPrompt, String customerMessage,
                                                        AIModels.Classification classification,
                                                        FinancialSummary financialSummary, Object bankingData,
                                                        AIModels.BankingContextMode contextMode,
                                                        Map<String, Object> additionalData,
                                                        List<ConversationModels.Message> history,
                                                        AIModels.AIProvider provider) {
            coachCalls++;
            lastSystemPrompt = systemPrompt;
            lastHistory = history == null ? List.of() : history;
            if (duringCoachCall != null) {
                duringCoachCall.run();
            }
            if (coachFailure != null) {
                throw coachFailure;
            }
            if (!needsDataPaths.isEmpty() && coachCalls == 1) {
                return new AIModels.AIAnswer(AIModels.AIStatus.NEED_DATA, "",
                        new AIModels.DataRequest(needsDataPaths), Map.of(), "", null);
            }
            String answer = coachAnswers.isEmpty() ? coachAnswer
                    : coachAnswers.get(Math.min(coachCalls - 1, coachAnswers.size() - 1));
            return new AIModels.AIAnswer(coachStatus, answer, null, Map.of(), "", null);
        }

        @Override
        public PromptOptimizationModels.ControllerFeedback reviewCoachAnswer(Map<String, Object> context,
                                                                            AIModels.AIProvider provider) {
            controllerCalls++;
            lastControllerContext = context;
            if (controllerFailure != null) {
                throw controllerFailure;
            }
            return feedback;
        }

        @Override
        public PromptOptimizationModels.EditorResult editPromptSection(Map<String, Object> context,
                                                                      AIModels.AIProvider provider) {
            editorCalls++;
            lastEditorContext = context;
            if (editorFailure != null) {
                throw editorFailure;
            }
            if (context.get("humanFeedback") != null) {
                // L'avis humain est transmis à l'éditeur : on applique la zone configurée et on le signale.
                return new PromptOptimizationModels.EditorResult(edition.status(), edition.editableSection(),
                        edition.changeSummary(), edition.feedbackAddressed(), edition.preservedBehaviors(),
                        edition.unresolvedPoints(), true);
            }
            return edition;
        }

        @Override
        public SuiviModels.SuiviResult summarizeConversation(Map<String, Object> context,
                                                             AIModels.AIProvider provider) {
            throw new UnsupportedOperationException("Non utilisé par l'atelier");
        }

        @Override
        public MarketingModels.MarketingReport analyzeMarketing(MarketingModels.MarketingAggregates aggregates,
                                                                AIModels.AIProvider provider) {
            throw new UnsupportedOperationException("Non utilisé par l'atelier");
        }

        @Override
        public QualityModels.QualityReport analyzeQuality(QualityModels.QualityAggregates aggregates,
                                                          AIModels.AIProvider provider) {
            throw new UnsupportedOperationException("Non utilisé par l'atelier");
        }

        @Override
        public AdvisorFeedbackModels.AdvisorFeedbackReport analyzeAdvisorFeedback(
                AdvisorFeedbackModels.AdvisorFeedbackAggregates aggregates, AIModels.AIProvider provider) {
            throw new UnsupportedOperationException("Non utilisé par l'atelier");
        }
    }

    /** Fabrique routée : un faux fournisseur PAR fournisseur réel (vérifie le routage des 3 étapes). */
    private static final class FakeAIServiceFactory extends AIServiceFactory {
        private final AIService service;
        private final Map<AIModels.AIProvider, AIService> routes = new LinkedHashMap<>();

        FakeAIServiceFactory(AIService service) {
            super((OpenAIService) null, (DeepSeekService) null, (MockAIService) null);
            this.service = service;
        }

        void route(AIModels.AIProvider provider, AIService routed) {
            routes.put(provider, routed);
        }

        @Override
        public AIService get(AIModels.AIProvider provider) {
            return routes.getOrDefault(provider, service);
        }

        @Override
        public AIModels.AIProvider defaultProvider() {
            return AIModels.AIProvider.DEEPSEEK;
        }
    }
}
