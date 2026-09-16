package com.coach.financier.ai;

import com.coach.financier.model.AIModels;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Décision de périmètre : l'atelier d'optimisation des prompts exige un fournisseur IA RÉEL.
 * En mode démo, la réponse du Coach ne dépend pas du prompt (elle est calculée à partir de la synthèse
 * backend) : évaluer une « amélioration » y serait donc dénué de sens. La couche IA le refuse
 * EXPLICITEMENT au lieu de produire un diagnostic factice.
 */
class MockAIServiceAtelierTest {

    private final MockAIService mock = new MockAIService();

    @Test
    void controllerRefusesToRunInDemoMode() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mock.reviewCoachAnswer(Map.of(), AIModels.AIProvider.MOCK));

        assertEquals(MockAIService.NO_REAL_PROVIDER_MESSAGE, error.getMessage());
        assertTrue(error.getMessage().contains("fournisseur IA réel"), "message exploitable par l'IHM");
    }

    @Test
    void editorRefusesToRunInDemoMode() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mock.editPromptSection(Map.of(), AIModels.AIProvider.MOCK));

        assertEquals(MockAIService.NO_REAL_PROVIDER_MESSAGE, error.getMessage());
    }
}
