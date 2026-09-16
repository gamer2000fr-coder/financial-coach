package com.coach.financier.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vérifie que l'agent de suivi est bien proposé (et éditable) sur la page « Agents ». */
class AgentPromptStoreTest {

    private final AgentPromptStore store = new AgentPromptStore();

    @Test
    void listsTheSuiviAgent() {
        Map<String, String> suivi = store.entries().stream()
                .filter(entry -> AgentPromptStore.SUIVI_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(suivi, "L'agent de suivi doit apparaître dans la liste déroulante");
        assertEquals("Agent de suivi (fin de conversation)", suivi.get("libelle"));
        assertEquals("suivi.txt", suivi.get("file"));
    }

    @Test
    void suiviAgentIsNotDeclaredAsACoachAgentInAgentsJson() {
        // Il ne doit PAS être déclaré dans agents.json, sinon il deviendrait sélectionnable comme
        // agent de coach (il n'intervient qu'à la clôture, via AgentFiles.suiviSystemPrompt()).
        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.SUIVI_KEY.equalsIgnoreCase(agent.getTheme())),
                "suivi ne doit pas être un thème d'agent de coach (agents.json)");
    }

    @Test
    void readsTheSuiviPromptContent() {
        String content = store.read(AgentPromptStore.SUIVI_KEY);

        assertNotNull(content);
        assertTrue(content.contains("BROUILLON"), "Le prompt de suivi doit décrire le brouillon d'email client");
        assertTrue(content.contains("conseiller"));
    }

    @Test
    void listsTheMarketingAgentAndItsPromptIsEditable() {
        Map<String, String> marketing = store.entries().stream()
                .filter(entry -> AgentPromptStore.MARKETING_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(marketing, "L'agent analyste marketing doit apparaître dans la liste déroulante");
        assertEquals("Agent analyste marketing", marketing.get("libelle"));
        assertEquals("marketing.txt", marketing.get("file"));

        // La clé doit résoudre le fichier éditable ./agent/marketing.txt
        String fileName = store.fileNameOf(AgentPromptStore.MARKETING_KEY);
        assertEquals("marketing.txt", fileName);

        String content = store.read(AgentPromptStore.MARKETING_KEY);
        assertNotNull(content);
        assertTrue(content.length() > 100, "Le prompt marketing doit être un vrai prompt (pas le repli vide)");

        // Il ne doit PAS être déclaré dans agents.json (il n'est jamais un agent de coach).
        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.MARKETING_KEY.equalsIgnoreCase(agent.getTheme())),
                "marketing ne doit pas être un thème d'agent de coach (agents.json)");
    }

    @Test
    void listsTheQualityAgentAndItsPromptIsEditable() {
        Map<String, String> quality = store.entries().stream()
                .filter(entry -> AgentPromptStore.QUALITY_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(quality, "L'agent analyste qualité doit apparaître dans la liste déroulante");
        assertEquals("Agent analyste qualité & satisfaction", quality.get("libelle"));
        assertEquals("qualite_coach_client.txt", quality.get("file"));

        // La clé doit résoudre le fichier éditable ./agent/qualite_coach_client.txt
        assertEquals("qualite_coach_client.txt", store.fileNameOf(AgentPromptStore.QUALITY_KEY));

        String content = store.read(AgentPromptStore.QUALITY_KEY);
        assertNotNull(content);
        assertTrue(content.length() > 500, "Le prompt qualité doit être un vrai prompt (pas le repli vide)");
        assertTrue(content.contains("SATISFACTION"), "Le prompt doit distinguer satisfaction et conformité");

        // Il ne doit PAS être déclaré dans agents.json (il n'est jamais un agent de coach).
        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.QUALITY_KEY.equalsIgnoreCase(agent.getTheme())),
                "qualite ne doit pas être un thème d'agent de coach (agents.json)");
    }

    @Test
    void listsThePromptControllerAgentAndItsPromptIsEditable() {
        Map<String, String> controller = store.entries().stream()
                .filter(entry -> AgentPromptStore.PROMPT_CONTROLLER_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(controller, "L'agent contrôleur de l'atelier doit apparaître dans la liste déroulante");
        assertEquals("Atelier prompts — agent contrôleur (Agent B)", controller.get("libelle"));
        assertEquals("prompt_controller.txt", controller.get("file"));
        assertEquals("prompt_controller.txt", store.fileNameOf(AgentPromptStore.PROMPT_CONTROLLER_KEY));

        String content = store.read(AgentPromptStore.PROMPT_CONTROLLER_KEY);
        assertNotNull(content);
        assertTrue(content.length() > 500, "Le prompt contrôleur doit être un vrai prompt (pas le repli)");
        assertTrue(content.contains("UNIQUEMENT"), "La sortie JSON stricte doit être imposée");
        assertTrue(content.contains("requiresHumanOrBusinessReview"), "Le contrat JSON doit être explicite");
        assertTrue(content.contains("source"), "Le diagnostic doit distinguer PROMPT / DATA / BACKEND_RULE");

        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.PROMPT_CONTROLLER_KEY.equalsIgnoreCase(agent.getTheme())),
                "L'agent contrôleur n'est jamais un agent de coach (agents.json)");
    }

    @Test
    void listsThePromptEditorAgentAndItsPromptIsEditable() {
        Map<String, String> editor = store.entries().stream()
                .filter(entry -> AgentPromptStore.PROMPT_EDITOR_KEY.equals(entry.get("key")))
                .findFirst()
                .orElse(null);

        assertNotNull(editor, "L'agent éditeur de l'atelier doit apparaître dans la liste déroulante");
        assertEquals("Atelier prompts — agent éditeur (Agent A)", editor.get("libelle"));
        assertEquals("prompt_editor.txt", editor.get("file"));
        assertEquals("prompt_editor.txt", store.fileNameOf(AgentPromptStore.PROMPT_EDITOR_KEY));

        String content = store.read(AgentPromptStore.PROMPT_EDITOR_KEY);
        assertNotNull(content);
        assertTrue(content.length() > 500, "Le prompt éditeur doit être un vrai prompt (pas le repli)");
        assertTrue(content.contains("editableSection"), "Le contrat JSON doit exposer editableSection");
        assertTrue(content.contains("HUMAN_OR_BUSINESS_REVIEW_REQUIRED"), "Le statut de revue humaine doit exister");

        assertTrue(com.coach.financier.ai.AgentFiles.agents().stream()
                        .noneMatch(agent -> AgentPromptStore.PROMPT_EDITOR_KEY.equalsIgnoreCase(agent.getTheme())),
                "L'agent éditeur n'est jamais un agent de coach (agents.json)");
    }
}
