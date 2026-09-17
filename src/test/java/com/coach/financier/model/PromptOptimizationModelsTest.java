package com.coach.financier.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing TOLÉRANT des sorties des agents internes de l'atelier d'optimisation des prompts.
 * <p>
 * Ces sorties sont produites par un LLM : champs manquants, listes nulles, valeurs d'énumération
 * inconnues ou champs supplémentaires ne doivent jamais faire échouer le parsing, et un statut
 * illisible ne doit JAMAIS être interprété comme « tout va bien » ni comme une modification à appliquer.
 */
class PromptOptimizationModelsTest {

    /** Même configuration que {@link com.coach.financier.config.JacksonConfig} (champs inconnus tolérés). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesTheDocumentedControllerJson() throws Exception {
        String json = """
                {
                  "status": "NEEDS_IMPROVEMENT",
                  "summary": "La réponse est correcte mais trop générale.",
                  "positivePoints": ["Le projet est correctement compris."],
                  "issues": [
                    {
                      "type": "INSUFFICIENT_PERSONALIZATION",
                      "severity": "MEDIUM",
                      "source": "PROMPT",
                      "observation": "La situation financière fournie est peu exploitée.",
                      "expectedBehavior": "Relier l'explication aux données utiles."
                    }
                  ],
                  "mustPreserve": ["Le ton pédagogique."],
                  "recommendationForPromptEditor": "Renforcer l'utilisation sélective du contexte.",
                  "requiresHumanOrBusinessReview": false
                }
                """;

        var feedback = MAPPER.readValue(json, PromptOptimizationModels.ControllerFeedback.class);

        assertEquals(PromptOptimizationModels.STATUS_NEEDS_IMPROVEMENT, feedback.status());
        assertFalse(feedback.noImprovementNeeded());
        assertEquals(1, feedback.issues().size());
        assertEquals(1, feedback.promptIssues().size());
        assertEquals(1, feedback.positivePoints().size());
        assertEquals("Le ton pédagogique.", feedback.mustPreserve().get(0));
        assertFalse(feedback.requiresHumanOrBusinessReview());
        assertEquals("Amélioration nécessaire", feedback.statusText());
    }

    @Test
    void ignoresUnknownFieldsAndToleratesMissingLists() throws Exception {
        String json = """
                {"status": "GOOD", "summary": "Rien à signaler.", "champ_inconnu_du_backend": 42}
                """;

        var feedback = MAPPER.readValue(json, PromptOptimizationModels.ControllerFeedback.class);

        assertEquals(PromptOptimizationModels.STATUS_GOOD, feedback.status());
        assertTrue(feedback.positivePoints().isEmpty());
        assertTrue(feedback.issues().isEmpty());
        assertTrue(feedback.mustPreserve().isEmpty());
        assertEquals("", feedback.recommendationForPromptEditor());
        assertTrue(feedback.noImprovementNeeded(), "GOOD sans issue = aucune modification à demander");
    }

    @Test
    void unreadableControllerStatusNeverMeansGood() {
        var feedback = new PromptOptimizationModels.ControllerFeedback("peut-être", null, null, null, null, null, null);

        assertEquals(PromptOptimizationModels.STATUS_NEEDS_IMPROVEMENT, feedback.status());
        assertFalse(feedback.noImprovementNeeded());
        assertFalse(feedback.requiresHumanOrBusinessReview(), "le booléen absent vaut false");
    }

    @Test
    void normalizesCaseInsensitiveEnumsAndFallsBackOnUnknownValues() {
        var issue = new PromptOptimizationModels.Issue("invented_url", "high", "backend_rule", " obs ", " corriger ");

        assertEquals("INVENTED_URL", issue.type());
        assertEquals(PromptOptimizationModels.SEVERITY_HIGH, issue.severity());
        assertEquals(PromptOptimizationModels.SOURCE_BACKEND_RULE, issue.source());
        assertEquals("obs", issue.observation());
        assertEquals("corriger", issue.expectedBehavior());
        assertFalse(issue.fromPrompt());

        var unknown = new PromptOptimizationModels.Issue("", "??", "??", null, null);
        assertEquals(PromptOptimizationModels.TYPE_OTHER, unknown.type());
        assertEquals(PromptOptimizationModels.SEVERITY_MEDIUM, unknown.severity());
        assertEquals(PromptOptimizationModels.SOURCE_UNKNOWN, unknown.source());
        assertEquals("", unknown.observation());
    }

    @Test
    void onlyIssuesFromTheEditableZoneAreActionable() {
        var feedback = new PromptOptimizationModels.ControllerFeedback("NEEDS_IMPROVEMENT", "resume",
                List.of(), List.of(
                        new PromptOptimizationModels.Issue("EXCESSIVE_REPETITION", "LOW", "PROMPT", "obs", "attendu"),
                        new PromptOptimizationModels.Issue("INVENTED_DATA", "HIGH", "BACKEND_RULE", "obs", "attendu")),
                List.of(), "reco", Boolean.TRUE);

        assertEquals(2, feedback.issues().size());
        assertEquals(1, feedback.promptIssues().size());
        assertEquals(PromptOptimizationModels.SOURCE_PROMPT, feedback.promptIssues().get(0).source());
        assertTrue(feedback.requiresHumanOrBusinessReview());
    }

    @Test
    void parsesTheDocumentedEditorJson() throws Exception {
        String json = """
                {
                  "status": "UPDATED",
                  "editableSection": "Tu accompagnes le client de manière pédagogique.",
                  "changeSummary": ["Précision sur l'usage sélectif du contexte"],
                  "feedbackAddressed": ["Personnalisation insuffisante"],
                  "preservedBehaviors": ["Ton pédagogique"],
                  "unresolvedPoints": [],
                  "humanFeedbackApplied": true
                }
                """;

        var result = MAPPER.readValue(json, PromptOptimizationModels.EditorResult.class);

        assertTrue(result.updated());
        assertFalse(result.reviewRequired());
        assertEquals("Zone modifiée", result.statusText());
        assertEquals("Tu accompagnes le client de manière pédagogique.", result.editableSection());
        assertEquals(1, result.changeSummary().size());
        assertTrue(result.humanFeedbackApplied());
    }

    @Test
    void unreadableEditorStatusFallsBackToHumanReview() {
        var result = new PromptOptimizationModels.EditorResult("???", "zone inchangée", null, null, null, null, null);

        assertTrue(result.reviewRequired(), "un statut illisible ne doit JAMAIS appliquer une modification");
        assertFalse(result.updated());
        assertTrue(result.changeSummary().isEmpty());
        assertFalse(result.humanFeedbackApplied());
        assertEquals("zone inchangée", result.editableSection(), "la zone reste inchangée");
    }

    @Test
    void labelsAreReadableAndHumanizeUnknownCodes() {
        assertEquals("Aucune amélioration nécessaire",
                PromptOptimizationModels.controllerStatusLabel(PromptOptimizationModels.STATUS_GOOD));
        assertEquals("Revue humaine requise",
                PromptOptimizationModels.editorStatusLabel(PromptOptimizationModels.EDITOR_REVIEW_REQUIRED));
        assertEquals("Racine", PromptOptimizationModels.severityLabel("RACINE"));
        assertEquals("Majeure", PromptOptimizationModels.severityLabel("HIGH"));
        assertEquals("URL inventée", PromptOptimizationModels.issueTypeLabel("INVENTED_URL"));
        assertEquals("Some new code", PromptOptimizationModels.issueTypeLabel("SOME_NEW_CODE"),
                "un code inconnu est humanisé, jamais affiché brut");
        assertEquals("", PromptOptimizationModels.humanize(null));
    }

    // --- Fil de conversation de l'atelier : la MÉMOIRE des cycles -----------------------------------

    private static PromptOptimizationModels.ConversationThread emptyThread() {
        return new PromptOptimizationModels.ConversationThread("th-1", "credit_conso",
                "Crédit à la consommation", PromptOptimizationModels.ZONE_AGENT, List.of(), List.of(),
                "2026-09-17T08:00:00Z", "2026-09-17T08:00:00Z");
    }

    @Test
    void aThreadOnlyKeepsCompleteExchanges() {
        var thread = emptyThread();
        assertTrue(thread.empty());
        assertEquals(0, thread.exchanges());
        assertTrue(thread.history().isEmpty(), "un fil neuf n'apporte aucune mémoire");

        var first = thread.withCampaign("po-1")
                .withExchange("po-1", "Je veux financer une voiture à 15 000 €", "Voici les solutions…", "V2");

        assertEquals(1, first.exchanges());
        assertEquals(2, first.turns().size());
        assertEquals(2, first.history().size());
        assertEquals("user", first.history().get(0).role());
        assertEquals("assistant", first.history().get(1).role());
        assertEquals("V2", first.turns().get(1).version(), "la réponse porte la version promue");
        assertEquals(List.of("po-1"), first.campaignIds());
        assertTrue(first.turns().get(0).user());
        assertTrue(first.turns().get(1).assistant());
        // L'association d'une campagne est IDEMPOTENTE (elle est faite au démarrage, puis à la promotion).
        assertEquals(1, first.withCampaign("po-1").campaignIds().size());
    }

    @Test
    void promotingAgainTheSameCampaignReplacesItsExchange() {
        var thread = emptyThread()
                .withExchange("po-1", "Question 1", "Réponse V1", "V1")
                .withExchange("po-2", "Question 2", "Réponse V2", "V2")
                .withExchange("po-1", "Question 1", "Réponse V3", "V3");

        assertEquals(2, thread.exchanges(), "une seule réponse par campagne : jamais de doublon");
        assertEquals(4, thread.turns().size());
        assertEquals(List.of("Question 1", "Réponse V3", "Question 2", "Réponse V2"),
                thread.turns().stream().map(turn -> turn.content()).toList(),
                "l'échange corrigé reste à sa place : l'ordre chronologique est conservé");
        assertEquals(List.of("po-1", "po-2"), thread.campaignIds());
        assertEquals("Réponse V2", thread.lastTurn().content());
    }

    @Test
    void theReplayedAnswerCanBeCorrectedButNeverHorsBornes() {
        var thread = emptyThread().withExchange("po-1", "Question", "Réponse initiale", "V1");

        var corrected = thread.withTurnContent(1, "Réponse corrigée par le conseiller");

        assertEquals("Réponse corrigée par le conseiller", corrected.history().get(1).content());
        assertEquals("Réponse initiale", thread.history().get(1).content(), "le fil d'origine n'est pas modifié");
        assertThrows(IllegalArgumentException.class, () -> thread.withTurnContent(4, "hors bornes"));
        assertThrows(IllegalArgumentException.class, () -> thread.withTurnContent(-1, "hors bornes"));
    }

    @Test
    void anUnreadableRoleNeverInventsAnAiAnswer() {
        var turn = new PromptOptimizationModels.Turn("ASSISTANT-TYPO", "  texte  ", "po-1", null, null);

        assertEquals(PromptOptimizationModels.ROLE_USER, turn.role(),
                "un rôle illisible devient une question client, jamais une réponse IA");
        assertEquals("texte", turn.content());
        assertEquals("", turn.version());
        assertEquals("po-1", turn.campaignId());
        assertFalse(turn.createdAt().isBlank(), "un tour est toujours daté");
        assertEquals("user", turn.asMessage().role(), "l'historique est au format attendu par le chat");
    }

    // --- Agent C : le CLIENT simulé (il mène la conversation) ---------------------------------------

    @Test
    void parsesTheDocumentedClientJson() throws Exception {
        var turn = MAPPER.readValue("""
                {"question":"Bonjour, je voudrais rénover ma cuisine. Est-ce possible ?",
                 "endConversation":false,"reason":"ouverture du scénario"}
                """, PromptOptimizationModels.ClientTurn.class);

        assertEquals("Bonjour, je voudrais rénover ma cuisine. Est-ce possible ?", turn.question());
        assertFalse(turn.endConversation());
        assertFalse(turn.finished(), "le client continue la conversation");
    }

    @Test
    void aClientTurnWithoutQuestionEndsTheScenario() {
        // Champ absent ou vide : on ne fabrique JAMAIS une question (le scénario s'arrête proprement).
        var empty = new PromptOptimizationModels.ClientTurn("   ", null, null);
        assertEquals("", empty.question());
        assertFalse(empty.endConversation());
        assertTrue(empty.finished());
        assertTrue(PromptOptimizationModels.ClientTurn.empty().finished());
        // Clôture explicite demandée par le client.
        var closing = new PromptOptimizationModels.ClientTurn("Merci, j'ai tout ce qu'il me faut.", true, "");
        assertTrue(closing.finished());
        assertEquals("Merci, j'ai tout ce qu'il me faut.", closing.question());
    }

    @Test
    void aThreadSurvivesAJsonRoundTrip() throws Exception {
        var thread = emptyThread().withExchange("po-1", "Question 1", "Réponse promue V2", "V2");

        String json = MAPPER.writeValueAsString(thread);
        var read = MAPPER.readValue(json, PromptOptimizationModels.ConversationThread.class);

        assertEquals(thread.turns().size(), read.turns().size());
        assertEquals(2, read.history().size());
        assertEquals("Question 1", read.history().get(0).content());
        assertEquals("Réponse promue V2", read.history().get(1).content());
        assertEquals("V2", read.turns().get(1).version());
        assertEquals(List.of("po-1"), read.campaignIds());
    }
}
