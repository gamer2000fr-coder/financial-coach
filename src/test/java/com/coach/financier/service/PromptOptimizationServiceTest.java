package com.coach.financier.service;

import com.coach.financier.ai.AIService;
import com.coach.financier.ai.AIServiceFactory;
import com.coach.financier.ai.DeepSeekService;
import com.coach.financier.ai.LocalAIService;
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
    private FinancialAnalysisService financialAnalysis;

    @BeforeEach
    void setUp() throws Exception {
        ai = new FakeAI();
        factory = new FakeAIServiceFactory(ai);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Même garde-fou qu'en PRODUCTION (`app.prompt-optimization.max-editable-section-length: 200000`) :
        // les prompts réels sont longs (agent principal ~23 400 caractères, crédit conso ~22 500) — un plafond
        // de test à 20 000 refusait à tort la promotion de l'agent crédit conso (zone « rejetée »).
        PromptOptimizationProperties properties =
                new PromptOptimizationProperties(true, false, tempDir.toString(), 50, 200000, "sel-de-test");
        store = new PromptOptimizationStore(properties, mapper);
        ProjectProductMappingService mapping = new ProjectProductMappingService();
        BankingDataRepository banking =
                new BankingDataRepository(mapper, DATA_DIR + "/banking_demo_normalized.json");
        dataRequests = new DataRequestService(mapper, DATA_DIR, true);
        CoachContextBuilder contextBuilder = new CoachContextBuilder(
                financialAnalysis = new FinancialAnalysisService(banking),
                dataRequests,
                new FinancialSynthesisStore(mapper, DATA_DIR + "/synthese_financier.json"),
                new ProductCatalogueService(mapper, mapping, DATA_DIR),
                mapping, banking, mapper);
        service = new PromptOptimizationService(properties, store, new PromptZoneService(), contextBuilder,
                factory, new AILogService(), new AgentPromptStore(),
                new AgentPromptHistoryStore(properties, new PromptZoneService(), mapper), dataRequests,
                financialAnalysis, mapper);
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
        assertThrows(IllegalArgumentException.class, () -> service.acceptVersion(campaignId, "V9"),
                "l'acceptation sans écriture partage le même garde-fou de version");

        service.resume(campaignId, 0);
        var active = assertThrows(IllegalStateException.class, () -> service.promoteVersion(campaignId, "V1"));
        assertTrue(active.getMessage().contains("pendant que la campagne tourne"));
        assertThrows(IllegalStateException.class, () -> service.acceptVersion(campaignId, "V1"));
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

    /**
     * BILAN début ↔ fin d'une conversation : le prompt du PREMIER cycle face au prompt en vigueur à la fin
     * (dernière version réellement PROMUE). La comparaison d'une CAMPAGNE ne montre qu'une question ; celle-ci
     * montre tout le scénario — c'est le bouton « comparer le prompt initial et le prompt final » de l'IHM.
     * <p>
     * La promotion est simulée DANS le store (une promotion réelle réécrirait {@code ./agent/credit-conso.txt},
     * ce qu'aucun test ne doit faire) : le bilan ne lit que des versions, il n'écrit rien.
     */
    @Test
    void theConversationComparisonShowsThePromptAtTheBeginningAndAtTheEnd() {
        var first = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        service.iterate(first.campaignId());
        String threadId = seedExchange(first, "Réponse de la première question.", first.basePromptVersion());

        // Conversation terminée sans qu'une version ait changé la zone : le bilan est un prompt IDENTIQUE
        // (information pour l'utilisateur, jamais une erreur).
        var unchanged = service.comparisonOfThread(threadId);
        assertEquals(threadId, unchanged.threadId());
        assertEquals("credit_conso", unchanged.agentId());
        assertEquals("credit-conso.txt", unchanged.zoneFile());
        assertEquals(first.basePromptVersion(), unchanged.baseVersion());
        assertEquals(unchanged.baseVersion(), unchanged.currentVersion());
        assertTrue(unchanged.identical(), "aucune promotion : le prompt n'a pas bougé");
        assertEquals(0, unchanged.promotionCount());
        assertEquals(1, unchanged.cycleCount());
        assertEquals(1, unchanged.iterationCount());
        assertEquals(unchanged.basePrompt(), unchanged.currentPrompt());
        assertEquals(unchanged.baseEditableSection(), unchanged.currentEditableSection());
        assertFalse(unchanged.summary().isBlank(), "l'IHM affiche une phrase d'explication");

        // La conversation CONTINUE (2e question, même fil) et sa zone est PROMUE : le bilan doit montrer
        // le début de la conversation (V0) face à la version en vigueur à la fin (V1).
        var second = service.start(new StartRequest("credit_conso", QUESTION, 1, null,
                AIModels.AIProvider.DEEPSEEK, threadId));
        String newZone = "Nouvelle zone obtenue par la conversation.";
        store.appendEdition(second.campaignId(),
                new PromptOptimizationModels.PromptVersion("V1", newZone, "hash-v1", 1));
        seedPromotion(second, "V1");

        var changed = service.comparisonOfThread(threadId);
        assertEquals(first.basePromptVersion(), changed.baseVersion(), "début de la conversation = 1er cycle");
        assertEquals("V1", changed.currentVersion(), "fin = dernière version réellement promue");
        assertFalse(changed.identical());
        assertEquals(1, changed.promotionCount());
        assertEquals(2, changed.cycleCount());
        assertEquals(newZone, changed.currentEditableSection());
        assertTrue(changed.currentPrompt().contains(newZone), "le prompt final contient la zone promue");
        assertFalse(changed.basePrompt().contains(newZone), "le prompt initial ne la contient pas");
        assertNotEquals(changed.baseCampaignId(), changed.currentCampaignId(),
                "le bilan relie bien le PREMIER et le DERNIER cycle de la conversation");
        assertTrue(changed.summary().contains("modifiée"), changed.summary());
        assertFalse(changed.summary().contains("V0 à V0"),
                "les noms de version sont locaux au cycle : le bilan ne peut pas parler de « V0 → V0 »");
    }

    @Test
    void theConversationComparisonRefusesAnEmptyOrUnknownConversation() {
        assertThrows(IllegalArgumentException.class, () -> service.comparisonOfThread("th-inexistant"));

        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String threadId = store.threadOf(campaign.campaignId()).orElseThrow().threadId();
        store.saveThread(new PromptOptimizationModels.ConversationThread(threadId, "credit_conso",
                "Crédit à la consommation", PromptOptimizationModels.ZONE_AGENT, List.of(), List.of(),
                "2026-09-17T08:00:00Z", "2026-09-17T08:00:00Z"));
        assertThrows(IllegalStateException.class, () -> service.comparisonOfThread(threadId));
    }

    /** Simule une promotion DANS le store : les tests ne réécrivent jamais un prompt de production. */
    private void seedPromotion(PromptOptimizationModels.Campaign campaign, String version) {
        store.saveCampaignAndReturn(new PromptOptimizationModels.Campaign(
                campaign.campaignId(), campaign.agentId(), campaign.agentLibelle(), campaign.zoneKey(),
                campaign.zoneFile(), PromptOptimizationModels.CAMPAIGN_ACCEPTED, campaign.question(),
                campaign.requestedIterations(), campaign.completedIterations(), campaign.maxIterations(),
                campaign.snapshotId(), campaign.basePromptVersion(), version, version, campaign.provider(),
                campaign.controllerProvider(), campaign.editorProvider(), campaign.model(), campaign.aiCalls(),
                campaign.totalPromptChars(), campaign.totalDurationMs(), campaign.stopRequestedAt(),
                campaign.pausedAt(), campaign.error(), campaign.errorStep(), campaign.createdAt(),
                java.time.Instant.now().toString()));
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

    /**
     * ACCEPTATION SANS ÉCRITURE (mode automatique de l'Agent C) : la version est retenue POUR LA CONVERSATION
     * — sa réponse entre dans le fil, le client garde donc sa mémoire — mais le prompt de PRODUCTION n'est pas
     * touché : ni sauvegarde, ni réécriture. C'est ce qui garantit qu'un scénario enchaîné ne décide rien à la
     * place de l'humain : la promotion reste l'UNIQUE écriture, décidée à la fin, après comparaison début ↔ fin.
     */
    @Test
    void acceptingAVersionNeverWritesTheProductionPrompt() throws Exception {
        Path production = Path.of("agent", "credit-conso.txt");
        String before = Files.readString(production);
        ai.coachAnswers = List.of("Réponse de la version V0.", "Réponse de la version V1.");
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        var iteration = service.iterate(campaign.campaignId());
        assertEquals("V1", iteration.resultingVersion(), "l'éditeur propose une zone DIFFÉRENTE de la production");
        assertFalse(service.appliedInProduction(campaign.campaignId(), "V1"),
                "V1 n'est pas (encore) dans le fichier de production");

        var result = service.acceptVersion(campaign.campaignId(), "V1");

        assertEquals(PromptOptimizationModels.CAMPAIGN_ACCEPTED, result.campaign().status());
        assertEquals("V1", result.campaign().promotedVersion(), "la version est retenue pour la conversation");
        assertTrue(result.backupFile().isEmpty(), "aucune écriture ⇒ aucune sauvegarde");
        assertTrue(result.message().contains("n'a PAS été modifié"), result.message());
        assertEquals(before, Files.readString(production), "le prompt de production reste intact");
        assertFalse(service.appliedInProduction(campaign.campaignId(), "V1"),
                "acceptée ne veut pas dire écrite : l'humain décide à la fin");

        var thread = store.threadOf(campaign.campaignId()).orElseThrow();
        assertEquals(1, thread.exchanges());
        assertEquals("Réponse de la version V1.", thread.history().get(1).content(),
                "la conversation contient bien la réponse de la version ACCEPTÉE");
    }

    /**
     * CHAÎNAGE DES CYCLES (mode automatique de l'Agent C) : un cycle peut partir de la version RETENUE du cycle
     * précédent au lieu du prompt de production — les améliorations s'ACCUMULENT donc dans la conversation, alors
     * que le fichier de production n'est jamais écrit. C'est ce qui rend le bilan « début ↔ fin » cumulatif et
     * permet de promouvoir en UNE fois tout le travail du scénario.
     */
    @Test
    void aCycleCanStartFromTheRetainedVersionOfThePreviousCycle() throws Exception {
        Path production = Path.of("agent", "credit-conso.txt");
        String before = Files.readString(production);
        ai.coachAnswers = List.of("Réponse de la version V0.", "Réponse de la version V1.");
        var first = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        var iteration = service.iterate(first.campaignId());
        assertEquals("V1", iteration.resultingVersion());
        service.acceptVersion(first.campaignId(), "V1");
        String inherited = service.versions(first.campaignId()).get(1).editableSection();
        assertNotEquals(service.versions(first.campaignId()).get(0).editableSection(), inherited,
                "la version retenue modifie bien la zone");
        int coachCallsBefore = ai.coachCalls;

        var second = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK,
                AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.DEEPSEEK, null, first.campaignId(), "V1"));
        var snapshot = service.snapshot(second.campaignId());

        assertEquals(inherited, snapshot.initialEditableSection(),
                "la zone de départ est celle RETENUE par le cycle précédent");
        assertEquals(first.campaignId() + ":V1", snapshot.baseZoneSource());
        assertEquals(PromptOptimizationModels.versionName(0), second.basePromptVersion(),
                "le nommage des versions reste LOCAL au cycle (V0 = zone de départ)");
        assertEquals(inherited, service.versions(second.campaignId()).get(0).editableSection(),
                "la version de référence du nouveau cycle EST la zone héritée");

        var secondIteration = service.iterate(second.campaignId());
        assertEquals(coachCallsBefore + 1, ai.coachCalls, "le cycle hérité rejoue le prompt avec cette zone");
        assertTrue(ai.lastSystemPrompt.contains(inherited),
                "le Coach reçoit le prompt COMPOSÉ à partir de la zone héritée");
        assertFalse(service.appliedInProduction(second.campaignId(), secondIteration.resultingVersion()),
                "rien n'a été écrit : la version retenue reste à promouvoir");
        assertEquals(before, Files.readString(production), "le prompt de production n'est jamais écrit");
    }

    /** Chaîner est refusé quand la demande est incohérente (version, cycle ou zone inconnus). */
    @Test
    void chainingRefusesAnUnknownVersionACampaignOrAForeignZone() {
        var first = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        service.iterate(first.campaignId());

        assertThrows(IllegalArgumentException.class, () -> service.start(chainFrom(first.campaignId(), "V9")));
        assertThrows(IllegalArgumentException.class, () -> service.start(chainFrom("po-inexistante", "V1")));

        // La zone visée est celle d'un AUTRE agent : composer un prompt hybride n'a aucun sens.
        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> service.start(new StartRequest("assurance_auto", QUESTION, 1, null,
                        AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.DEEPSEEK,
                        null, first.campaignId(), "V1")));
        assertTrue(foreign.getMessage().contains("impossible d'enchaîner"), foreign.getMessage());
    }

    private StartRequest chainFrom(String campaignId, String version) {
        return new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK,
                AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.DEEPSEEK, null, campaignId, version);
    }

    /** « Appliquée » décrit le FICHIER, jamais le statut de la campagne. */
    @Test
    void appliedInProductionFollowsTheFileNotTheCampaignStatus() throws Exception {
        Path production = Path.of("agent", "credit-conso.txt");
        String before = Files.readString(production);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        assertTrue(service.appliedInProduction(campaign.campaignId(), campaign.basePromptVersion()),
                "la version de référence EST le prompt en production");

        var iteration = service.iterate(campaign.campaignId());
        service.acceptVersion(campaign.campaignId(), iteration.resultingVersion());

        assertFalse(service.appliedInProduction(campaign.campaignId(), "V1"));
        assertTrue(service.appliedInProduction(campaign.campaignId(), "V0"),
                "la version de référence reste celle du fichier");
        assertEquals(before, Files.readString(production), "aucune écriture dans les deux cas");
    }

    /** Accepter la version de référence (aucune modification proposée) n'écrit rien et le dit clairement. */
    @Test
    void acceptingTheReferenceVersionWritesNothing() throws Exception {
        Path production = Path.of("agent", "credit-conso.txt");
        String before = Files.readString(production);
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        ai.edition = new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_NO_CHANGE, "ZONE V0",
                List.of(), List.of(), List.of(), List.of(), false);
        service.iterate(campaign.campaignId());

        var result = service.acceptVersion(campaign.campaignId(), campaign.basePromptVersion());

        assertEquals(PromptOptimizationModels.CAMPAIGN_ACCEPTED, result.campaign().status());
        assertTrue(result.message().contains("ACCEPTÉE pour la conversation"), result.message());
        assertTrue(result.backupFile().isEmpty());
        assertEquals(before, Files.readString(production));
        assertEquals(1, store.threadOf(campaign.campaignId()).orElseThrow().exchanges(),
                "la conversation est débloquée malgré l'absence d'écriture");
    }

    /**
     * Agent B juge le RESPECT DU PROMPT : il doit donc recevoir le prompt système <b>exactement</b> tel qu'il a
     * été envoyé au Coach — même chaîne, marqueurs de zone exclus —, ainsi que la classification figée et la
     * liste des données disponibles (tout ce que le payload du Coach contenait).
     */
    @Test
    void agentBReceivesTheExactPromptSentToTheCoach() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));

        var iteration = service.iterate(campaign.campaignId());

        Map<String, Object> context = ai.lastControllerContext;
        assertNotNull(context, "Agent B a été appelé");
        String coachPrompt = String.valueOf(context.get("coachPrompt"));
        assertEquals(ai.lastSystemPrompt, coachPrompt,
                "Agent B reçoit EXACTEMENT le prompt système envoyé au Coach");
        assertEquals(service.promptFor(campaign.campaignId(), iteration.promptVersion()), coachPrompt,
                "c'est bien le prompt de la version utilisée par cette itération (zone incluse)");
        assertFalse(coachPrompt.contains("[[["), "les marqueurs de zone ne sont jamais envoyés au LLM");
        assertNotNull(context.get("classification"), "la classification FIGÉE est transmise à Agent B");
        assertNotNull(context.get("availableData"), "la liste des données disponibles est transmise à Agent B");
        assertFalse(String.valueOf(context.get("availableData")).isBlank(),
                "le Coach pouvait demander ces fichiers : Agent B peut donc juger une demande manquée");
        assertTrue(coachPrompt.contains("Crédit à la consommation"),
                "le prompt reçu contient bien la partie métier de l'agent");
    }

    /**
     * PARITÉ DE DONNÉES entre le Coach, l'Agent B et l'Agent A (vérification demandée à la suite d'un
     * diagnostic douteux : « Agent B reproche au Coach d'avoir inventé une offre qui existe dans le
     * catalogue »).
     * <p>
     * Un contrôleur ne peut juger une invention que s'il voit <b>exactement</b> ce que le Coach a vu : les
     * fichiers joints doivent lui être transmis avec leur <b>CONTENU</b> (et pas seulement leurs descriptions),
     * faute de quoi un produit réellement fourni serait signalé à tort comme inventé. L'éditeur, qui écrit à
     * partir du même diagnostic, doit bénéficier de la même parité — sinon il figerait une règle sur un
     * produit ou un taux inexistant. Vérifié ici sur le contenu, pas sur la forme.
     */
    @Test
    void agentBAndAgentAReceiveExactlyTheDataGivenToTheCoach() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null,
                AIModels.AIProvider.DEEPSEEK));

        service.iterate(campaign.campaignId());

        List<?> coachData = (List<?>) ai.lastCoachProvidedData;
        assertNotNull(coachData, "le Coach a reçu des données jointes (fiches produits, guide, cascade…)");
        assertFalse(coachData.isEmpty(), "le contexte de l'agent contient bien des fichiers");
        for (Object entry : coachData) {
            Map<?, ?> file = (Map<?, ?>) entry;
            assertTrue(file.containsKey("description") && file.containsKey("data"),
                    "chaque entrée jointe au Coach porte sa description ET son contenu : " + file.keySet());
        }
        assertEquals(coachData, ai.lastControllerContext.get("providedData"),
                "Agent B reçoit les MÊMES données que le Coach, contenu compris");
        assertEquals(coachData, ai.lastEditorContext.get("providedData"),
                "Agent A reçoit les MÊMES données que le Coach, contenu compris");
        // Le catalogue (chemins + descriptions) que le Coach POUVAIT demander est également transmis.
        assertNotNull(ai.lastControllerContext.get("availableData"),
                "Agent B sait aussi ce que le Coach POUVAIT demander (catalogue)");
        assertNotNull(ai.lastControllerContext.get("additionalData"),
                "Agent B reçoit le projet courant et les produits compatibles calculés par le backend");
    }

    // --- Agent C : le CLIENT simulé (il mène la conversation) -----------------------------------------
    /**
     * Le client simulé ne reçoit QUE ce qu'il faut pour jouer son rôle : le brief écrit par l'humain, les
     * <b>trois chiffres du dossier</b> (compte courant, épargne, mensualité de crédit), son numéro de question,
     * la profondeur demandée et la conversation déjà validée (mémoire du fil).
     */
    @Test
    void theSimulatedClientReceivesTheBriefTheThreeFiguresAndTheConversation() {
        var campaign = service.start(new StartRequest("credit_conso", QUESTION, 1, null, AIModels.AIProvider.DEEPSEEK));
        String threadId = seedExchange(campaign, "Réponse de la version promue.", "V1");

        var turn = service.clientTurn(threadId, "Projet de rénovation de cuisine, ~15 000 €", 2, 5,
                AIModels.AIProvider.DEEPSEEK);

        assertEquals(ai.scriptedClientTurn.question(), turn.question());
        assertFalse(turn.finished());
        Map<String, Object> context = ai.lastClientContext;
        assertEquals("Projet de rénovation de cuisine, ~15 000 €", context.get("clientBrief"));
        assertEquals(2, context.get("turnNumber"));
        assertEquals(5, context.get("depth"), "la profondeur réglée dans l'IHM borne le nombre de questions");
        assertEquals(2, ((List<?>) context.get("previousExchanges")).size(),
                "la conversation déjà validée est transmise au client (il ne se répète pas)");
        Map<?, ?> figures = (Map<?, ?>) context.get("clientFigures");
        assertEquals(3, figures.size(), "exactement trois chiffres : compte courant, épargne, crédit en cours");
        assertNotNull(((Map<?, ?>) figures.get("compteCourant")).get("montant"));
        assertNotNull(((Map<?, ?>) figures.get("epargne")).get("montant"));
        assertNotNull(((Map<?, ?>) figures.get("creditEnCours")).get("montant"));
    }

    @Test
    void theSimulatedClientRequiresABriefARealProviderAndAKnownThread() {
        assertThrows(IllegalArgumentException.class,
                () -> service.clientTurn(null, "   ", 1, 3, AIModels.AIProvider.DEEPSEEK));
        var demo = assertThrows(IllegalArgumentException.class,
                () -> service.clientTurn(null, "Projet de rénovation", 1, 3, AIModels.AIProvider.MOCK));
        assertTrue(demo.getMessage().contains("fournisseur IA réel"), demo.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.clientTurn("th-inexistant", "Projet de rénovation", 1, 3, AIModels.AIProvider.DEEPSEEK));

        // Sans fil : premier tour du scénario, aucune conversation à transmettre.
        service.clientTurn(null, "Projet de rénovation", 1, 3, AIModels.AIProvider.DEEPSEEK);
        assertEquals(List.of(), ai.lastClientContext.get("previousExchanges"));
    }

    /**
     * « Générer projet » : l'Agent C cherche LUI-MÊME un client et un projet correspondant à l'agent de coach
     * sélectionné. Il doit recevoir le périmètre de cet agent (libellé ET prompt), les trois chiffres du dossier
     * — ceux que le client connaîtra — et les briefs déjà proposés, pour en chercher un FRANCHEMENT différent
     * au clic suivant.
     */
    @Test
    void theSimulatedClientInventsAProjectSuitedToTheSelectedAgent() {
        var brief = service.clientBrief("credit_conso",
                List.of("Projet de trésorerie pour des travaux de cuisine, 15 000 €."),
                AIModels.AIProvider.DEEPSEEK);

        assertEquals(ai.scriptedClientBrief.brief(), brief.brief());
        assertTrue(brief.usable());
        Map<String, Object> context = ai.lastClientBriefContext;
        Map<?, ?> agent = (Map<?, ?>) context.get("agent");
        assertEquals("credit_conso", agent.get("id"));
        assertEquals("Crédit à la consommation", agent.get("libelle"));
        String agentPrompt = String.valueOf(context.get("agentPrompt"));
        assertTrue(agentPrompt.contains("Crédit à la consommation"),
                "le prompt de l'agent sert de cadre : le projet doit tomber dans son périmètre");
        assertFalse(agentPrompt.contains("[[[") || agentPrompt.contains("]]]"),
                "les marqueurs de zone ne sont jamais envoyés au LLM");
        Map<?, ?> figures = (Map<?, ?>) context.get("clientFigures");
        assertEquals(3, figures.size(), "exactement les trois chiffres que le client connaîtra ensuite");
        assertEquals(List.of("Projet de trésorerie pour des travaux de cuisine, 15 000 €."),
                context.get("previousBriefs"), "les briefs déjà proposés sont transmis au modèle");
    }

    /**
     * Le modèle ne doit pas proposer « n'importe quoi » (signalement utilisateur : « un achat d'un château »).
     * Deux garde-fous : le contexte contient les TROIS chiffres du dossier ET le PLAFOND que le backend en
     * déduit (un petit modèle calcule mal) ; une proposition au-dessus de ce plafond est REFUSÉE, que le montant
     * soit déclaré ou seulement cité dans le brief.
     */
    @Test
    void theAgentCInventsProjectsThatFitTheCustomerFiguresAndTheBackendCeiling() {
        service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.DEEPSEEK);

        Map<String, Object> context = ai.lastClientBriefContext;
        Map<?, ?> figures = (Map<?, ?>) context.get("clientFigures");
        // Les trois chiffres réels du dossier, AVEC leur montant : c'est ce qui cadre le projet.
        for (String key : List.of("compteCourant", "epargne", "creditEnCours")) {
            Map<?, ?> figure = (Map<?, ?>) figures.get(key);
            assertNotNull(figure.get("montant"), key + " : le montant réel est transmis au modèle");
        }
        BigDecimal savings = new BigDecimal(
                String.valueOf(((Map<?, ?>) figures.get("epargne")).get("montant")));
        Map<?, ?> budget = (Map<?, ?>) context.get("budgetCoherent");
        BigDecimal ceiling = (BigDecimal) budget.get("plafondProjet");
        assertTrue(ceiling.compareTo(savings.multiply(new BigDecimal("2"))) >= 0,
                "le plafond tient compte de l'épargne du client (" + savings + " €) : " + ceiling);
        assertTrue(ceiling.compareTo(new BigDecimal("30000")) >= 0,
                "plancher : un dossier sans épargne peut quand même porter un petit projet à la consommation");

        // Un projet juste EN DESSOUS du plafond reste accepté : le garde-fou n'interdit pas les projets normaux.
        ai.scriptedClientBrief = new PromptOptimizationModels.ClientBrief(
                "Nadia, 41 ans, propriétaire, veut financer 28 000 € de travaux de rénovation énergétique.",
                new BigDecimal("28000"), "projet de travaux cohérent avec le dossier");
        assertEquals(new BigDecimal("28000"),
                service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.DEEPSEEK).montantProjet());

        // Montant DÉCLARÉ hors dossier : refus explicite.
        ai.scriptedClientBrief = new PromptOptimizationModels.ClientBrief(
                "Rémi achète un château de 450 000 € : il veut savoir quelles solutions existent.",
                new BigDecimal("450000"), "projet ambitieux");
        var tooBig = assertThrows(IllegalStateException.class,
                () -> service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.DEEPSEEK));
        assertTrue(tooBig.getMessage().contains("450000"), tooBig.getMessage());
        assertTrue(tooBig.getMessage().contains("plafond admis"), tooBig.getMessage());

        // Montant seulement CITÉ dans le brief (le modèle a oublié de le déclarer) : refusé lui aussi.
        ai.scriptedClientBrief = new PromptOptimizationModels.ClientBrief(
                "Rémi veut s'offrir un château à 450 000 € et voudrait un prêt adapté.",
                null, "projet ambitieux");
        assertThrows(IllegalStateException.class,
                () -> service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.DEEPSEEK));

        // Le crédit immobilier échappe au plafond « 2 × épargne » : un achat à plusieurs centaines de milliers
        // d'euros y est légitime (c'est le Périmètre de l'agent, pas un dépassement de budget).
        ai.scriptedClientBrief = new PromptOptimizationModels.ClientBrief(
                "Léa et Marc achètent leur résidence principale pour 285 000 € avec un apport de 15 000 €.",
                new BigDecimal("265000"), "achat immobilier cohérent");
        var immo = service.clientBrief("credit_immo", List.of(), AIModels.AIProvider.DEEPSEEK);
        assertEquals(new BigDecimal("265000"), immo.montantProjet());
    }

    /**
     * Le projet proposé doit être différent du précédent : le brief COURANT est transmis au modèle, et la
     * proposition n'écrit RIEN (elle reste un texte à relire, modifiable par l'humain).
     */
    @Test
    void theGeneratedProjectIsOnlyAProposalAndNeverWritesAnything() {
        int campaignsBefore = service.campaigns().size();
        int threadsBefore = store.threads().size();

        service.clientBrief("credit_conso", List.of("Premier projet", "Deuxième projet"),
                AIModels.AIProvider.DEEPSEEK);

        assertEquals(List.of("Premier projet", "Deuxième projet"), ai.lastClientBriefContext.get("previousBriefs"));
        assertEquals(campaignsBefore, service.campaigns().size(), "aucune campagne n'est créée");
        assertEquals(threadsBefore, store.threads().size(), "aucune conversation n'est ouverte");
    }

    @Test
    void theGeneratedProjectRequiresARealProviderAKnownAgentAndANonEmptyAnswer() {
        var demo = assertThrows(IllegalArgumentException.class,
                () -> service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.MOCK));
        assertTrue(demo.getMessage().contains("fournisseur IA réel"), demo.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.clientBrief("agent-inconnu", List.of(), AIModels.AIProvider.DEEPSEEK));

        // Réponse vide du modèle : refus explicite plutôt qu'un brief vide proposé à l'humain.
        ai.scriptedClientBrief = new PromptOptimizationModels.ClientBrief("   ", null, "");
        var empty = assertThrows(IllegalStateException.class,
                () -> service.clientBrief("credit_conso", List.of(), AIModels.AIProvider.DEEPSEEK));
        assertTrue(empty.getMessage().contains("aucun projet exploitable"), empty.getMessage());
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
        /** Données JOINTES réellement transmises au Coach (contenu inclus) — parité avec Agent B / Agent A. */
        Object lastCoachProvidedData;
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
            // Données réellement jointes au Coach : sert à vérifier la PARITÉ avec l'Agent B et l'Agent A.
            lastCoachProvidedData = additionalData == null ? null : additionalData.get("providedData");
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

        /** Question scriptée du CLIENT simulé (« Agent C ») + contexte réellement reçu (assertions). */
        PromptOptimizationModels.ClientTurn scriptedClientTurn = new PromptOptimizationModels.ClientTurn(
                "Bonjour, je voudrais rénover ma cuisine : est-ce que ma situation le permet ?", false, "ouverture");
        Map<String, Object> lastClientContext;

        @Override
        public PromptOptimizationModels.ClientTurn clientTurn(Map<String, Object> context,
                                                              AIModels.AIProvider provider) {
            lastClientContext = context;
            return scriptedClientTurn;
        }

        /** Projet scripté du CLIENT simulé (« Générer projet ») + contexte réellement reçu (assertions). */
        PromptOptimizationModels.ClientBrief scriptedClientBrief = new PromptOptimizationModels.ClientBrief(
                "Claire, 34 ans, aide-soignante en CDI, locataire. Elle doit remplacer son véhicule et souhaite "
                        + "financer 7 500 € sur 4 ans ; elle veut savoir quelle mensualité prévoir et si son "
                        + "épargne doit servir d'apport.",
                new BigDecimal("7500"),
                "projet véhicule avec contrainte de mensualité : teste le cadrage du crédit conso");
        Map<String, Object> lastClientBriefContext;

        @Override
        public PromptOptimizationModels.ClientBrief clientBrief(Map<String, Object> context,
                                                              AIModels.AIProvider provider) {
            lastClientBriefContext = context;
            return scriptedClientBrief;
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
            super((OpenAIService) null, (DeepSeekService) null, (LocalAIService) null, (MockAIService) null);
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
