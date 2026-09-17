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

    /**
     * Valeur d'énumération INCONNUE : défaut réel observé (« intent = DEBT_RESTRUCTURING », qui est une valeur
     * de {@code projectType}) — sans tolérance, tout l'appel échouait en HTTP 500
     * « Réponse classification invalide ».
     */
    @Test
    void unknownEnumValueFallsBackInsteadOfFailingTheWholeCall() throws Exception {
        String raw = "{\"inScope\":true,\"intent\":\"DEBT_RESTRUCTURING\","
                + "\"projectType\":\"DEBT_RESTRUCTURING\",\"confidence\":\"HIGH\","
                + "\"reason\":\"Le client veut racheter son crédit.\"}";

        com.coach.financier.model.IntentClassification c =
                mapper.readValue(raw, com.coach.financier.model.IntentClassification.class);

        assertEquals(com.coach.financier.model.FinancialIntent.OTHER, c.getIntent(),
                "un intent hors liste retombe sur OTHER au lieu de faire échouer la requête");
        assertEquals(com.coach.financier.model.ProjectType.DEBT_RESTRUCTURING, c.getProjectType(),
                "le projectType VALIDE est conservé (il pilote le choix de l'agent)");
        assertEquals(com.coach.financier.model.ConfidenceLevel.HIGH, c.getConfidence());
        assertNotNull(c.toLegacyCategory());
        assertEquals(AIModels.RequestCategory.CREDIT, c.toLegacyCategory(),
                "un rachat de crédit reste une demande de crédit pour l'IHM");
    }

    /** Un PLACEMENT (PEA, assurance-vie) reste une question d'ÉPARGNE, jamais de crédit. */
    @Test
    void anInvestmentIsNeverLabelledAsCredit() throws Exception {
        String raw = "{\"inScope\":true,\"intent\":\"PRODUCT_INFORMATION\",\"projectType\":\"INVESTMENT\","
                + "\"confidence\":\"HIGH\"}";

        com.coach.financier.model.IntentClassification c =
                mapper.readValue(raw, com.coach.financier.model.IntentClassification.class);

        assertEquals(com.coach.financier.model.ProjectType.INVESTMENT, c.getProjectType());
        assertEquals(AIModels.RequestCategory.SAVINGS, c.toLegacyCategory(),
                "un placement ne doit pas être affiché comme un crédit");
    }
}
