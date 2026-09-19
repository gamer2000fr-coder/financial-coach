package com.coach.financier.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un échec du fournisseur IA doit être LISIBLE dans l'IHM : le frontend n'affiche que le champ
 * {@code message} d'une réponse en erreur. Or la réponse 500 par défaut de Spring met la cause dans
 * {@code trace}, pas dans {@code message} — d'où ce gestionnaire, vérifié ici sur ses deux points
 * sensibles : le message est TRANSMIS tel quel, et le code HTTP dit que la panne est en AMONT (502).
 */
class AiCallExceptionHandlerTest {

    private final AiCallExceptionHandler handler = new AiCallExceptionHandler();

    @Test
    void theProviderMessageIsTransmittedToTheClient() {
        String cause = "Délai de lecture dépassé après 1800 s sur le fournisseur Local (LM Studio) : "
                + "le modèle n'a pas rendu sa réponse à temps.";

        ResponseEntity<Map<String, Object>> response =
                handler.onIllegalState(new IllegalStateException(cause));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode(),
                "la panne vient du service IA : 502, pas 500 (qui accuserait l'application)");
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(cause, body.get("message"), "le message doit être transmis à l'identique");
        assertEquals(HttpStatus.BAD_GATEWAY.value(), body.get("status"));
        assertEquals("ECHEC_FOURNISSEUR_IA", body.get("error"));
        assertFalse(body.containsKey("trace"), "aucune trace technique n'est exposée au client");
    }

    @Test
    void aFailureWithoutMessageStillExplainsItself() {
        ResponseEntity<Map<String, Object>> response =
                handler.onIllegalState(new IllegalStateException());

        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertTrue(String.valueOf(body.get("message")).contains("fournisseur IA"),
                "un échec sans message doit rester compréhensible : " + body.get("message"));
    }
}
