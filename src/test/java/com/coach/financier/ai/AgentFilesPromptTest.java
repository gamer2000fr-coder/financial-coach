package com.coach.financier.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition du prompt système : gabarit {@code generic.txt} + agent principal + agent spécialisé.
 * Vérifie aussi que les marqueurs de zone éditable (atelier d'optimisation des prompts) ne sont
 * JAMAIS envoyés au LLM — le prompt de production doit rester identique à l'existant.
 */
class AgentFilesPromptTest {

    @Test
    void compositionIncludesTemplatePrincipalAndSpecializedPrompt() {
        String prompt = AgentFiles.composeSystemPrompt("GABARIT\n[agent_principal]\n[agent]\nFIN",
                "PRINCIPAL", "SPECIALISE");

        assertTrue(prompt.contains("GABARIT"));
        assertTrue(prompt.contains("PRINCIPAL"));
        assertTrue(prompt.contains("SPECIALISE"));
        assertTrue(prompt.contains("FIN"));
        assertTrue(prompt.indexOf("PRINCIPAL") < prompt.indexOf("SPECIALISE"),
                "l'agent principal précède l'agent spécialisé");
    }

    @Test
    void zoneMarkersAreNeverSentToTheLlm() {
        String prompt = AgentFiles.composeSystemPrompt("GABARIT\n[agent_principal]\n[agent]",
                "[[[\nPRINCIPAL\n]]]", "[[[\nSPECIALISE\n]]]");

        assertFalse(prompt.contains("[[["), "les marqueurs de zone ne doivent jamais être envoyés au LLM");
        assertFalse(prompt.contains("]]]"), "les marqueurs de zone ne doivent jamais être envoyés au LLM");
        assertTrue(prompt.contains("PRINCIPAL"));
        assertTrue(prompt.contains("SPECIALISE"));
    }

    @Test
    void stripZoneMarkersKeepsTheRestIntact() {
        String stripped = AgentFiles.stripZoneMarkers("ligne 1\n[[[\nligne 2\n]]]\nligne 3\n");

        assertTrue(stripped.contains("ligne 1"));
        assertTrue(stripped.contains("ligne 2"));
        assertTrue(stripped.contains("ligne 3"));
        assertFalse(stripped.contains("[[["));
        assertFalse(stripped.contains("]]]"));
    }

    @Test
    void stripZoneMarkersLeavesAnUnmarkedPromptUnchanged() {
        String prompt = "Tu es un coach financier.\nRéponds en français.";

        assertTrue(prompt.equals(AgentFiles.stripZoneMarkers(prompt)));
    }

    /** Agent générique : le gabarit + l'agent principal, sans agent spécialisé (pas d'inclusion récursive). */
    @Test
    void genericAgentReceivesPrincipalWithoutSpecializedPrompt() {
        String prompt = AgentFiles.systemPromptFor(AgentFiles.GENERIC_THEME);

        assertFalse(prompt.contains("[agent_principal]"));
        assertFalse(prompt.contains("[agent]"));
        assertFalse(prompt.contains("[[["));
        assertFalse(prompt.contains("]]]"));
        assertTrue(prompt.contains("CONTINUITÉ DE CONVERSATION"), "l'agent principal est inclus");
        assertFalse(prompt.contains("Crédit à la consommation"), "aucun agent spécialisé n'est inclus");
    }

    /** Agent spécialisé : gabarit + agent principal + agent du thème, sans marqueur visible. */
    @Test
    void specializedAgentReceivesItsOwnPromptAndNoMarker() {
        String prompt = AgentFiles.systemPromptFor("credit_conso");

        assertFalse(prompt.contains("[[["));
        assertFalse(prompt.contains("]]]"));
        assertTrue(prompt.contains("CONTINUITÉ DE CONVERSATION"), "l'agent principal est inclus");
        assertTrue(prompt.contains("Crédit à la consommation"), "l'agent spécialisé est inclus");
    }

    /** Les deux agents de l'atelier sont chargés depuis ./agent (et non depuis le repli de sécurité). */
    @Test
    void promptLabAgentsUseTheirRealPromptFiles() {
        assertTrue(AgentFiles.promptControllerSystemPrompt().contains("STABILITÉ"),
                "le vrai prompt du contrôleur est chargé (pas le repli)");
        assertTrue(AgentFiles.promptEditorSystemPrompt().contains("ANTI-SURAPPRENTISSAGE"),
                "le vrai prompt de l'éditeur est chargé (pas le repli)");
    }

    /** L'atelier n'est pas lui-même optimisable : aucun de ses prompts ne porte de marqueur. */
    @Test
    void promptLabAgentsCarryNoEditableZone() {
        assertFalse(AgentFiles.promptControllerSystemPrompt().contains("[[["));
        assertFalse(AgentFiles.promptEditorSystemPrompt().contains("[[["));
    }
}
