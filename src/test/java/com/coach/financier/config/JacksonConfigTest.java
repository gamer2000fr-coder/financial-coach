package com.coach.financier.config;

import com.coach.financier.model.AIModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROBUSTESSE du parsing des réponses LLM.
 * <p>
 * Un modèle en mode JSON renvoie régulièrement des caractères de contrôle BRUTS dans les chaînes (retours
 * à la ligne d'un texte de plusieurs paragraphes) : sans tolérance, tout l'appel échoue avec
 * {@code Illegal unquoted character ((CTRL-CHAR, code 10)): has to be escaped using backslash} — c'est
 * exactement le défaut observé sur l'étape COACH d'une campagne d'optimisation.
 */
class JacksonConfigTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void parsesAJsonStringContainingRawNewlines() throws Exception {
        // RETOUR À LA LIGNE BRUT dans la valeur "answer" (exactement ce que produit un modèle bavard).
        String raw = "{\"status\":\"ANSWER\",\"answer\":\"Ligne 1\nLigne 2 avec un tableau :\n| a | b |\"}";

        JsonNode node = mapper.readTree(raw);

        assertEquals("ANSWER", node.path("status").asText());
        assertTrue(node.path("answer").asText().contains("Ligne 1\nLigne 2"), "le texte reste intact");
    }

    @Test
    void parsesAJsonStringContainingRawTabsAndCarriageReturns() throws Exception {
        String raw = "{\"status\":\"ANSWER\",\"answer\":\"Colonne A\tColonne B\r\nSuite\"}";

        AIModels.AIAnswer answer = mapper.readValue(raw, AIModels.AIAnswer.class);

        assertNotNull(answer);
        assertTrue(answer.answer().contains("Colonne A\tColonne B"));
    }

    @Test
    void parsesATrailingCommaProducedByAModel() throws Exception {
        String raw = "{\"status\":\"ANSWER\",\"answer\":\"ok\",}";

        JsonNode node = mapper.readTree(raw);

        assertEquals("ok", node.path("answer").asText());
    }

    @Test
    void keepsTheOtherTolerancesOfTheApplicationMapper() throws Exception {
        // Champ inconnu (catalogues et rapports évolutifs) : ne doit jamais faire échouer la lecture.
        JsonNode node = mapper.readTree("{\"status\":\"ANSWER\",\"answer\":\"ok\",\"champInconnu\":42}");

        assertEquals("ok", node.path("answer").asText());
    }
}
