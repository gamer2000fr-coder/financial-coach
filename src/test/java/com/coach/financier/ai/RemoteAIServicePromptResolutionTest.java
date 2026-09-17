package com.coach.financier.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproductibilité du PROMPT SYSTÈME rejoué par l'atelier d'optimisation des prompts : lorsqu'une
 * version figée est fournie, elle est utilisée TELLE QUELLE (jamais le prompt courant du disque) ;
 * sinon le comportement historique est conservé (agent actif relu depuis {@code ./agent}).
 */
class RemoteAIServicePromptResolutionTest {

    /** Sous-classe minimale : le constructeur construit le client HTTP avec ses timeouts. */
    private static final class Probe extends RemoteAIService {
        Probe() {
            super(new ObjectMapper(), "http://localhost:1", "cle-de-test", "modele-de-test", "Test",
                    RemoteAIService.DEFAULT_MAX_OUTPUT_TOKENS);
        }
    }

    private final Probe probe = new Probe();

    @Test
    void frozenPromptWinsOverThePromptOnDisk() {
        String frozen = "=== PROMPT FIGÉ DE LA CAMPAGNE ===";

        String resolved = Probe.resolveSystemPrompt(frozen, Map.of("agent", "credit_conso"));

        assertEquals(frozen, resolved, "une campagne doit rejouer la version figée, pas le disque");
        assertFalse(resolved.contains("Crédit à la consommation"));
    }

    @Test
    void withoutOverrideTheActiveAgentPromptIsUsed() {
        String resolved = Probe.resolveSystemPrompt(null, Map.of("agent", "credit_conso"));

        assertTrue(resolved.contains("Crédit à la consommation"), "prompt de l'agent actif");
        assertTrue(resolved.contains("CONTINUITÉ DE CONVERSATION"), "agent principal inclus");
    }

    @Test
    void blankOverrideFallsBackToTheActiveAgentPrompt() {
        String reference = Probe.resolveSystemPrompt(null, Map.of("agent", "epargne"));

        assertEquals(reference, Probe.resolveSystemPrompt("   ", Map.of("agent", "epargne")));
    }

    @Test
    void withoutAgentInContextTheGenericAgentIsUsed() {
        String resolved = Probe.resolveSystemPrompt(null, Map.of());

        assertTrue(resolved.contains("agent « Générique »"), "repli sur l'agent générique");
        assertFalse(resolved.contains("[["), "aucun marqueur de zone éditable dans le prompt envoyé");
    }
}
