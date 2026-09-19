package com.coach.financier.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat HTTP de l'atelier d'optimisation des prompts (IHM {@code #/prompt-lab}).
 * <p>
 * Vérifie ce que l'IHM consomme réellement : la liste des zones optimisables, et surtout les DEUX formes
 * d'erreur sur lesquelles elle s'appuie ({@code 400 BAD_REQUEST} / {@code 409 CONFLICT}, avec un
 * {@code message} lisible en français — c'est ce que lit {@code apiFetch}).
 * <p>
 * Aucun de ces appels n'ÉCRIT quoi que ce soit : les campagnes refusées ne sont jamais créées et aucune
 * itération n'est lancée (le mode MOCK est justement refusé par l'atelier, et le refus arrive AVANT tout
 * appel au fournisseur).
 * <p>
 * {@code MockMvc} est construit explicitement : Spring Boot 4 n'expose plus
 * {@code @AutoConfigureMockMvc} parmi les modules de test disponibles dans ce POC.
 */
@SpringBootTest
class PromptOptimizationControllerTest {

    private static final String QUESTION = "Je souhaite financer une voiture d'occasion à 15000 euros.";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private Map<String, Object> getBody(String url, int expectedStatus) throws Exception {
        String json = mockMvc.perform(get(url))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private Map<String, Object> postBody(String url, String body, int expectedStatus) throws Exception {
        String json = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    @Test
    void exposesTheOptimizableZonesAndTheCumulatedCeiling() throws Exception {
        Map<String, Object> body = getBody("/api/prompt-optimization/agents", 200);

        assertEquals(Boolean.TRUE, body.get("enabled"));
        assertTrue(((Number) body.get("maxIterations")).intValue() >= 1, "le plafond est exposé à l'IHM");
        List<?> zones = (List<?>) body.get("zones");
        assertEquals(7, zones.size(), "les 7 agents de coach disposent d'une zone optimisable");
        Map<?, ?> first = (Map<?, ?>) zones.get(0);
        assertEquals(Boolean.TRUE, first.get("optimizable"));
        assertTrue(first.get("zoneFile") instanceof String && first.get("editableSection") instanceof String);
        assertTrue(first.get("zoneKey") instanceof String);
    }

    @Test
    void listsTheCampaignsAsAnArray() throws Exception {
        String json = mockMvc.perform(get("/api/prompt-optimization/campaigns"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<?> campaigns = objectMapper.readValue(json, new TypeReference<List<Object>>() {
        });
        assertEquals(campaigns.size(), campaigns.size(), "la liste est bien un tableau JSON");
    }

    /** « Retenir » a été retiré de l'atelier : la promotion est la SEULE décision sur une version. */
    /**
     * CHAÎNAGE : le corps de la requête transporte bien le cycle source et sa version (« la zone de départ du
     * cycle est celle de la version retenue de ce cycle-là »). Le refus arrive AVANT tout appel IA — un cycle
     * source inconnu est donc détectable ici sans fournisseur ni réseau, et l'IHM reçoit un message lisible.
     */
    @Test
    void aChainedCycleReportsAnUnknownSourceCycleWithoutCallingAnyProvider() throws Exception {
        Map<String, Object> body = postBody("/api/prompt-optimization/campaigns", """
                {"agentId":"credit_conso","question":"%s","iterations":1,"provider":"LOCAL",
                 "fromCampaignId":"po-inexistante","fromVersion":"V1"}
                """.formatted(QUESTION), 400);

        assertEquals("BAD_REQUEST", body.get("error"));
        assertTrue(String.valueOf(body.get("message")).contains("Campagne inconnue"),
                "le chaînage est validé avant tout appel IA : " + body.get("message"));
    }

    @Test
    void noLongerExposesTheRetainEndpoints() throws Exception {
        mockMvc.perform(post("/api/prompt-optimization/campaigns/po-inexistante/retain")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"V1\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/prompt-optimization/campaigns/po-inexistante/unretain")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"V1\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * La route d'ACCEPTATION sans écriture existe (400 « campagne inconnue », et non 404 comme une route
     * disparue) : c'est celle qu'utilise le mode automatique de l'Agent C pour enchaîner les cycles du client
     * SANS écrire le prompt de production.
     */
    @Test
    void theAcceptRouteExistsAndReportsAnUnknownCampaign() throws Exception {
        Map<String, Object> body = postBody("/api/prompt-optimization/campaigns/po-inexistante/accept",
                "{\"version\":\"V1\"}", 400);
        assertEquals("BAD_REQUEST", body.get("error"));
        assertTrue(body.get("message") instanceof String);
    }

    @Test
    void refusesTheDemoProviderWithAnExplicitMessage() throws Exception {
        Map<String, Object> body = postBody("/api/prompt-optimization/campaigns", """
                {"agentId":"credit_conso","question":"%s","iterations":1,"provider":"MOCK"}
                """.formatted(QUESTION), 400);

        assertEquals("BAD_REQUEST", body.get("error"));
        assertTrue(String.valueOf(body.get("message")).contains("fournisseur IA réel"),
                "le message explique qu'un fournisseur réel est nécessaire");
    }

    @Test
    void refusesAnEmptyQuestionAndAnOutOfRangeIterationCount() throws Exception {
        Map<String, Object> empty = postBody("/api/prompt-optimization/campaigns", """
                {"agentId":"credit_conso","question":"   ","iterations":3,"provider":"DEEPSEEK"}
                """, 400);
        assertTrue(String.valueOf(empty.get("message")).contains("question de test"));

        Map<String, Object> zero = postBody("/api/prompt-optimization/campaigns", """
                {"agentId":"credit_conso","question":"%s","iterations":0,"provider":"DEEPSEEK"}
                """.formatted(QUESTION), 400);
        assertEquals("BAD_REQUEST", zero.get("error"));

        Map<String, Object> tooMany = postBody("/api/prompt-optimization/campaigns", """
                {"agentId":"credit_conso","question":"%s","iterations":51,"provider":"DEEPSEEK"}
                """.formatted(QUESTION), 400);
        assertEquals("BAD_REQUEST", tooMany.get("error"));
    }

    @Test
    void anUnknownCampaignIsAReadableError() throws Exception {
        Map<String, Object> read = getBody("/api/prompt-optimization/campaigns/po-inexistante", 400);
        assertEquals("BAD_REQUEST", read.get("error"));
        assertTrue(read.get("message") instanceof String);

        Map<String, Object> iterate = postBody("/api/prompt-optimization/campaigns/po-inexistante/iterate",
                "{}", 400);
        assertEquals("BAD_REQUEST", iterate.get("error"));
    }

    @Test
    void anInvalidCampaignIdentifierIsRefused() throws Exception {
        // L'identifiant est validé (anti-traversée de chemin) AVANT toute lecture de fichier.
        Map<String, Object> body = getBody("/api/prompt-optimization/campaigns/po-%40%40%40", 400);
        assertEquals("BAD_REQUEST", body.get("error"));
    }

    // --- Fils de conversation (mémoire de l'atelier) -------------------------------------------------

    @Test
    void listsTheConversationThreadsAsAnArray() throws Exception {
        String json = mockMvc.perform(get("/api/prompt-optimization/threads"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<?> threads = objectMapper.readValue(json, new TypeReference<List<Object>>() {
        });
        assertEquals(threads.size(), threads.size(), "la liste est bien un tableau JSON");
    }

    @Test
    void anUnknownConversationThreadIsAReadableError() throws Exception {
        Map<String, Object> body = getBody("/api/prompt-optimization/threads/th-inexistante", 400);
        assertEquals("BAD_REQUEST", body.get("error"));
        assertTrue(String.valueOf(body.get("message")).contains("Fil de conversation inconnu"),
                "l'IHM affiche un message lisible, jamais un identifiant brut");

        // L'identifiant est validé (anti-traversée de chemin) AVANT toute lecture de fichier.
        assertEquals("BAD_REQUEST",
                getBody("/api/prompt-optimization/threads/th-%40%40%40", 400).get("error"));

        String json = mockMvc.perform(put("/api/prompt-optimization/threads/th-inexistante/turns/1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"texte\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("BAD_REQUEST", objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        }).get("error"));
    }

    // --- Agent C : projet du client (« Générer projet ») ----------------------------------------------

    /**
     * Les refus de « Générer projet » arrivent AVANT tout appel au fournisseur : ils sont donc vérifiables
     * ici sans réseau ni clé API — et ce sont exactement les messages que l'IHM affiche.
     */
    @Test
    void theGeneratedProjectRefusesTheDemoProviderAndAnUnknownAgent() throws Exception {
        Map<String, Object> demo = postBody("/api/prompt-optimization/client/brief", """
                {"agentId":"credit_conso","provider":"MOCK"}
                """, 400);
        assertEquals("BAD_REQUEST", demo.get("error"));
        assertTrue(String.valueOf(demo.get("message")).contains("fournisseur IA réel"),
                "le mode démo ne peut pas inventer de projet : " + demo.get("message"));

        Map<String, Object> unknown = postBody("/api/prompt-optimization/client/brief", """
                {"agentId":"agent-inconnu","provider":"DEEPSEEK"}
                """, 400);
        assertTrue(String.valueOf(unknown.get("message")).contains("Agent inconnu"),
                "l'IHM affiche un message lisible, jamais un identifiant brut");
    }
}
