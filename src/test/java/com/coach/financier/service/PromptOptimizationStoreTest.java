package com.coach.financier.service;

import com.coach.financier.config.PromptOptimizationProperties;
import com.coach.financier.model.AIModels;
import com.coach.financier.model.ConversationModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.model.IntentClassification;
import com.coach.financier.model.PromptOptimizationModels;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistance de l'atelier d'optimisation des prompts : fichiers JSON/JSONL, écritures atomiques,
 * historique APPEND-ONLY (aucune version écrasée), comptage des lignes invalides, verrou de campagne
 * et reprise après redémarrage.
 */
class PromptOptimizationStoreTest {

    @TempDir
    Path tempDir;

    private PromptOptimizationStore store;

    @BeforeEach
    void setUp() {
        PromptOptimizationProperties properties =
                new PromptOptimizationProperties(true, false, tempDir.toString(), 50, 20000, "sel-de-test");
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        store = new PromptOptimizationStore(properties, mapper);
    }

    // --- Fabriques ------------------------------------------------------------------------------

    private static FinancialSummary summary() {
        return new FinancialSummary(3, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31),
                4801.48, 4801.48, 3873.92, 956.0, 2917.92, 927.56, 927.56, 1516.89, 14695.18, 956.0,
                19.9, 12.11, 0, 1516.89, 1200.0, "Loyer", 412, Map.of("LOGEMENT", 956.0),
                12.11, "juin – août 2026");
    }

    private static PromptOptimizationModels.Campaign campaign(String id) {
        return new PromptOptimizationModels.Campaign(id, "credit_conso", "Crédit à la consommation",
                PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt",
                PromptOptimizationModels.CAMPAIGN_RUNNING, "Je veux financer une voiture à 15000 euros",
                5, 0, 50, "snap-1", "V0", "V0", "",
                "DEEPSEEK", "deepseek-chat", 0, 0L, 0L, "", "", "", "",
                "2026-09-16T10:00:00Z", "2026-09-16T10:00:00Z");
    }

    private static PromptOptimizationModels.Snapshot snapshot(String id) {
        return new PromptOptimizationModels.Snapshot("snap-1", id,
                "Je veux financer une voiture à 15000 euros", "credit_conso", "Crédit à la consommation",
                PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt", "V0",
                "PARTIE PROTÉGÉE\n", "Règles de l'agent crédit conso.", "\nFIN DU PROMPT", "",
                "PROMPT SYSTÈME FIGÉ", "GABARIT [agent_principal] [agent]", "RÈGLES DE L'AGENT PRINCIPAL",
                new IntentClassification(), summary(),
                List.of(Map.of("path", "/data/catalogue/credit_conso.json", "description", "Crédit conso")),
                List.of("/data/catalogue/credit_conso.json"),
                Map.of("providedData", List.of(Map.of("description", "Synthèse financière", "data", "…")),
                        "agent", "credit_conso", "agentLibelle", "Crédit à la consommation"),
                List.of(new ConversationModels.Message("user", "Bonjour", java.time.Instant.parse("2026-09-16T09:59:00Z"))),
                "[INTENT]\nintent=FINANCING_REQUEST\n", "DEEPSEEK", "deepseek-chat",
                "hash-prompt-v0", "hash-snapshot", "2026-09-16T10:00:00Z");
    }

    private static PromptOptimizationModels.Iteration iteration(String campaignId, int number) {
        return new PromptOptimizationModels.Iteration("it-" + number, campaignId, number,
                PromptOptimizationModels.versionName(number - 1), "zone V" + (number - 1), "hash-" + number,
                "Réponse du Coach n°" + number,
                new PromptOptimizationModels.ControllerFeedback(
                        PromptOptimizationModels.STATUS_NEEDS_IMPROVEMENT, "correcte mais générique",
                        List.of("projet compris"),
                        List.of(new PromptOptimizationModels.Issue("INSUFFICIENT_PERSONALIZATION", "MEDIUM",
                                "PROMPT", "contexte peu exploité", "relier aux données")),
                        List.of("le ton pédagogique"), "renforcer l'usage du contexte", false),
                new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_UPDATED,
                        "zone V" + number, List.of("précision ajoutée"), List.of("personnalisation"),
                        List.of("ton pédagogique"), List.of(), false),
                PromptOptimizationModels.versionName(number), "zone V" + number, List.of("précision ajoutée"),
                false, false, PromptOptimizationModels.ITERATION_COMPLETED, "", "DEEPSEEK", "deepseek-chat",
                12345L, 4200L, "2026-09-16T10:01:00Z", "2026-09-16T10:01:04Z");
    }

    // --- Campagne + snapshot ----------------------------------------------------------------------

    @Test
    void createsAndReadsACampaignWithItsFrozenSnapshot() {
        store.create(campaign("camp-1"), snapshot("camp-1"));

        var read = store.require("camp-1");
        assertEquals("credit_conso", read.agentId());
        assertEquals(PromptOptimizationModels.CAMPAIGN_RUNNING, read.status());
        assertEquals(5, read.remainingIterations());

        var frozen = store.snapshot("camp-1").orElseThrow();
        assertEquals("Je veux financer une voiture à 15000 euros", frozen.question());
        assertEquals("credit-conso.txt", frozen.zoneFile());
        assertEquals("PARTIE PROTÉGÉE\n", frozen.fixedPrefix());
        assertEquals("Règles de l'agent crédit conso.", frozen.initialEditableSection());
        assertEquals("hash-snapshot", frozen.snapshotHash());
        assertEquals(1, frozen.history().size());
        assertEquals(1, frozen.providedData().size(), "les données jointes figées restent accessibles");
        assertEquals("credit_conso", frozen.additionalData().get("agent"));
    }

    @Test
    void refusesToOverwriteAnExistingCampaign() {
        store.create(campaign("camp-1"), snapshot("camp-1"));

        assertThrows(IllegalStateException.class, () -> store.create(campaign("camp-1"), snapshot("camp-1")));
    }

    @Test
    void listsCampaignsAndUnknownCampaignIsRejected() {
        store.create(campaign("camp-1"), snapshot("camp-1"));

        assertEquals(1, store.list().size());
        assertTrue(store.exists("camp-1"));
        assertThrows(IllegalArgumentException.class, () -> store.require("inconnue"));
    }

    @Test
    void refusesAnInvalidCampaignId() {
        assertThrows(IllegalArgumentException.class, () -> store.campaignDir("../evasion"));
        assertThrows(IllegalArgumentException.class, () -> store.campaignDir("a/b"));
    }

    // --- Itérations -------------------------------------------------------------------------------

    @Test
    void iterationHistoryIsAppendOnly() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendIteration(iteration("camp-1", 1));
        store.appendIteration(iteration("camp-1", 2));

        // Ré-écriture de l'itération 1 : elle ne doit JAMAIS écraser la version enregistrée (§15).
        store.appendIteration(iteration("camp-1", 1));

        var iterations = store.iterations("camp-1");
        assertEquals(2, iterations.values().size(), "aucune itération n'est écrasée ni dupliquée");
        assertEquals(0, iterations.invalidLines());
        assertEquals(2, store.latestIteration("camp-1").orElseThrow().iterationNumber());
    }

    @Test
    void countsInvalidJsonlLinesInsteadOfLosingThem() throws Exception {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendIteration(iteration("camp-1", 1));
        Path file = tempDir.resolve("campaigns").resolve("camp-1").resolve("iterations.jsonl");
        Files.writeString(file, "{ ligne corrompue\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        var iterations = store.iterations("camp-1");

        assertEquals(1, iterations.values().size(), "les lignes valides restent lisibles");
        assertEquals(1, iterations.invalidLines(), "les lignes invalides sont COMPTÉES");
    }

    @Test
    void everyVersionRemainsConsultable() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendIteration(iteration("camp-1", 1));
        store.appendIteration(iteration("camp-1", 2));

        var versions = store.versions("camp-1");

        assertEquals(3, versions.size(), "V0 (snapshot) + V1 + V2");
        assertEquals("V0", versions.get(0).version());
        assertEquals("Règles de l'agent crédit conso.", versions.get(0).editableSection());
        assertEquals("V2", versions.get(2).version());
        assertEquals("zone V2", versions.get(2).editableSection());
        assertEquals("zone V1", store.editableSectionOf("camp-1", "V1").orElseThrow(),
                "une version antérieure reste reconstructible même après V2");
        assertEquals("Règles de l'agent crédit conso.", store.editableSectionOf("camp-1", "V0").orElseThrow());
        assertTrue(store.editableSectionOf("camp-1", "V9").isEmpty());
    }

    @Test
    void iterationsWithoutChangeDoNotCreateAVersion() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        var noChange = new PromptOptimizationModels.Iteration("it-1", "camp-1", 1, "V0", "zone V0", "hash",
                "réponse", null,
                new PromptOptimizationModels.EditorResult(PromptOptimizationModels.EDITOR_NO_CHANGE, "zone V0",
                        List.of(), List.of(), List.of("comportement actuel"), List.of(), false),
                "V0", "zone V0", List.of(), true, false, PromptOptimizationModels.ITERATION_COMPLETED,
                "", "DEEPSEEK", "deepseek-chat", 100L, 200L, "2026-09-16T10:01:00Z", "2026-09-16T10:01:01Z");
        store.appendIteration(noChange);

        assertEquals(1, store.versions("camp-1").size(), "V0 seulement : aucune nouvelle version");
        assertFalse(store.latestIteration("camp-1").orElseThrow().producedNewVersion());
    }

    @Test
    void iterationRoundTripKeepsTheControllerAndEditorDiagnostics() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendIteration(iteration("camp-1", 1));

        var read = store.latestIteration("camp-1").orElseThrow();

        assertEquals(PromptOptimizationModels.STATUS_NEEDS_IMPROVEMENT, read.controllerFeedback().status());
        assertEquals(1, read.controllerFeedback().issues().size());
        assertEquals(PromptOptimizationModels.SOURCE_PROMPT, read.controllerFeedback().issues().get(0).source());
        assertTrue(read.editorResult().updated());
        assertEquals(List.of("précision ajoutée"), read.changeSummary());
        assertEquals(4200L, read.durationMs());
        assertEquals("V1", read.resultingVersion());
    }

    // --- Feedback humain ---------------------------------------------------------------------------

    @Test
    void humanFeedbackIsAppendOnlyAndTracksWhatWasApplied() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendFeedback(new PromptOptimizationModels.HumanFeedback("fb-1", "camp-1", 2,
                PromptOptimizationModels.FEEDBACK_SOURCE_HUMAN, PromptOptimizationModels.PRIORITY_OVERRIDE,
                "Trop long, garder l'explication sur l'épargne.", "2026-09-16T10:05:00Z", false));
        store.appendFeedback(new PromptOptimizationModels.HumanFeedback("fb-2", "camp-1", 3,
                null, null, "Parfait.", "2026-09-16T10:06:00Z", true));
        // Double envoi du même identifiant : ignoré (idempotence).
        store.appendFeedback(new PromptOptimizationModels.HumanFeedback("fb-1", "camp-1", 2,
                null, null, "doublon", "2026-09-16T10:07:00Z", false));

        var feedbacks = store.feedbacks("camp-1");

        assertEquals(2, feedbacks.values().size());
        assertTrue(feedbacks.values().get(0).overrides(), "priorité OVERRIDE par défaut pour un avis humain");
        assertEquals(PromptOptimizationModels.FEEDBACK_SOURCE_HUMAN, feedbacks.values().get(1).source());
        assertEquals("fb-1", store.pendingHumanFeedback("camp-1").orElseThrow().feedbackId(),
                "seul l'avis non appliqué reste en attente");
    }

    // --- Concurrence (§36) --------------------------------------------------------------------------

    @Test
    void rejectsAConcurrentTreatmentOnTheSameCampaign() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<String> outcome = new AtomicReference<>("(non exécuté)");
            store.withCampaignLock("camp-1", () -> {
                Future<?> future = pool.submit(() -> {
                    try {
                        store.withCampaignLock("camp-1", () -> "double");
                        outcome.set("accepté");
                    } catch (IllegalStateException e) {
                        outcome.set("refusé : " + e.getMessage());
                    }
                });
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test interrompu", e);
                } catch (Exception e) {
                    throw new IllegalStateException("échec du traitement concurrent", e);
                }
                return "ok";
            });

            assertTrue(outcome.get().startsWith("refusé"),
                    "un double traitement de la même campagne doit être refusé : " + outcome.get());
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Machine d'état ------------------------------------------------------------------------------

    @Test
    void campaignStateMachineDrivesTheCounters() {
        var running = campaign("camp-1");
        assertEquals(5, running.remainingIterations());
        assertTrue(running.canIterate());

        var done = new PromptOptimizationModels.Campaign("camp-1", "credit_conso", "Crédit à la consommation",
                PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt",
                PromptOptimizationModels.CAMPAIGN_COMPLETED, "question", 10, 10, 50, "snap-1", "V0", "V10",
                "", "DEEPSEEK", "deepseek-chat", 30, 100L, 200L, "", "", "", "",
                "2026-09-16T10:00:00Z", "2026-09-16T10:30:00Z");
        assertEquals(0, done.remainingIterations());
        assertTrue(done.requestedIterationsDone());
        assertFalse(done.canIterate(), "le cycle demandé est terminé");
        assertEquals(10, done.withRequestedIterations(20).remainingIterations(),
                "« continuer » ajoute un cycle au compteur CUMULÉ");
        assertEquals(50, done.withRequestedIterations(999).requestedIterations(),
                "le plafond cumulé de 50 itérations est infranchissable");
    }

    @Test
    void unreadableCampaignStatusIsNeverRunning() {
        var campaign = new PromptOptimizationModels.Campaign("camp-1", "credit_conso", "libelle",
                PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt", "n'importe quoi", "question",
                5, 0, 50, "snap-1", "V0", "V0", "", "DEEPSEEK", "modele", 0, 0L, 0L,
                "", "", "", "", "2026-09-16T10:00:00Z", "2026-09-16T10:00:00Z");

        assertEquals(PromptOptimizationModels.CAMPAIGN_ERROR, campaign.status());
        assertFalse(campaign.isActive());
        assertFalse(PromptOptimizationModels.campaignBusy(campaign.status()));
        assertEquals("Erreur", PromptOptimizationModels.campaignStatusLabel(campaign.status()));
    }

    @Test
    void campaignSurvivesARestart() {
        store.create(campaign("camp-1"), snapshot("camp-1"));
        store.appendIteration(iteration("camp-1", 1));
        // État représentatif d'une campagne mise en pause après 1 itération sur 10 (l'état est tenu par
        // le service, le store ne fait que le persister).
        store.saveCampaign(new PromptOptimizationModels.Campaign("camp-1", "credit_conso",
                "Crédit à la consommation", PromptOptimizationModels.ZONE_AGENT, "credit-conso.txt",
                PromptOptimizationModels.CAMPAIGN_PAUSED, "Je veux financer une voiture à 15000 euros",
                10, 1, 50, "snap-1", "V0", "V1", "", "DEEPSEEK", "deepseek-chat",
                3, 12345L, 4200L, "2026-09-16T10:02:00Z", "2026-09-16T10:02:05Z", "", "",
                "2026-09-16T10:00:00Z", "2026-09-16T10:02:05Z"));

        // Nouvelle instance de store sur le même répertoire : simulation d'un redémarrage du backend.
        PromptOptimizationProperties properties =
                new PromptOptimizationProperties(true, false, tempDir.toString(), 50, 20000, "sel-de-test");
        PromptOptimizationStore reopened = new PromptOptimizationStore(properties, new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

        var campaign = reopened.require("camp-1");
        assertEquals(10, campaign.requestedIterations(), "le cycle demandé survit au redémarrage");
        assertEquals(1, campaign.completedIterations());
        assertEquals(9, campaign.remainingIterations());
        assertEquals(PromptOptimizationModels.CAMPAIGN_PAUSED, campaign.status(), "reprise depuis PAUSED");
        assertEquals("V1", campaign.currentCandidateVersion());
        assertEquals(1, reopened.iterations("camp-1").values().size());
        assertEquals(2, reopened.versions("camp-1").size(), "V0 (snapshot) + V1 (itération 1)");
        assertEquals(AIModels.AIProvider.DEEPSEEK, AIModels.AIProvider.valueOf(campaign.provider()));
    }

    // --- Fils de conversation (mémoire de l'atelier) ----------------------------------------------

    private static PromptOptimizationModels.ConversationThread thread(String id, String campaignId, String updatedAt) {
        return new PromptOptimizationModels.ConversationThread(id, "credit_conso", "Crédit à la consommation",
                PromptOptimizationModels.ZONE_AGENT,
                List.of(new PromptOptimizationModels.Turn("user", "Question du client",
                                campaignId, "", "2026-09-16T10:00:00Z"),
                        new PromptOptimizationModels.Turn("assistant", "Réponse de la version promue",
                                campaignId, "V1", "2026-09-16T10:05:00Z")),
                List.of(campaignId), "2026-09-16T10:00:00Z", updatedAt);
    }

    @Test
    void savesReadsAndListsConversationThreads() {
        store.saveThread(thread("th-1", "po-1", "2026-09-16T10:05:00Z"));
        store.saveThread(thread("th-2", "po-2", "2026-09-16T11:05:00Z"));

        var read = store.thread("th-1").orElseThrow();
        assertEquals("credit_conso", read.agentId());
        assertEquals(2, read.turns().size());
        assertEquals(1, read.exchanges());
        assertEquals(2, read.history().size(), "l'historique rejoué porte la question ET la réponse");
        assertEquals("user", read.history().get(0).role());
        assertEquals("assistant", read.history().get(1).role());

        var threads = store.threads();
        assertEquals(2, threads.size());
        assertEquals("th-2", threads.get(0).threadId(), "le fil le plus récemment modifié est proposé en premier");
        assertEquals("th-1", store.threadOf("po-1").orElseThrow().threadId());
        assertTrue(store.threadOf("po-inexistante").isEmpty());
        assertTrue(store.thread("th-inexistant").isEmpty(), "un identifiant valide inconnu n'est pas une erreur");
        assertTrue(Files.isRegularFile(tempDir.resolve("threads").resolve("th-1.json")));
    }

    @Test
    void refusesAnInvalidThreadIdentifierOrAnEmptyThread() {
        // L'identifiant est validé (anti-traversée de chemin) AVANT toute lecture de fichier.
        assertThrows(IllegalArgumentException.class, () -> store.thread("../evasion"));
        assertThrows(IllegalArgumentException.class, () -> store.saveThread(
                new PromptOptimizationModels.ConversationThread("", "credit_conso", "libelle",
                        PromptOptimizationModels.ZONE_AGENT, List.of(), List.of(),
                        "2026-09-16T10:00:00Z", "2026-09-16T10:00:00Z")));
    }
}
