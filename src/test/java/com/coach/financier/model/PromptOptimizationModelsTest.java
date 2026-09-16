package com.coach.financier.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
