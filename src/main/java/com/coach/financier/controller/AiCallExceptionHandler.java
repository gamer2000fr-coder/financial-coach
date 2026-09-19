package com.coach.financier.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rend VISIBLE, dans l'IHM, la cause d'un échec d'appel au fournisseur IA.
 * <p>
 * Sans ce point unique, une réponse 500 de Spring contient bien l'exception… mais dans la trace
 * ({@code trace}), jamais dans le champ {@code message} que le frontend lit : l'utilisateur voyait donc
 * « Erreur HTTP 500 » sans savoir s'il s'agissait d'un délai dépassé, d'un contexte trop grand ou d'une
 * réponse hors contrat. Le message exact — celui construit par le fournisseur
 * (« Délai de lecture dépassé après N s … », « Réponse IA invalide: … ») — est désormais renvoyé.
 * <p>
 * <b>502 Bad Gateway</b> est le code juste : la défaillance vient du service IA en amont, pas de
 * l'application (une erreur 500 laisserait croire à un bug interne).
 * <p>
 * Les contrôleurs qui gèrent déjà ces exceptions localement (atelier d'optimisation : 400/409 avec leurs
 * propres messages) gardent la main : un {@code @ExceptionHandler} de contrôleur prime sur cet avis.
 */
@RestControllerAdvice
public class AiCallExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiCallExceptionHandler.class);

    /** Cause technique consignée dans la réponse, à côté du message lisible. */
    private static final String PROVIDER_FAILURE = "ECHEC_FOURNISSEUR_IA";

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onIllegalState(IllegalStateException exception) {
        String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? "Échec de l'appel au fournisseur IA (aucun détail fourni)."
                : exception.getMessage();
        // La trace reste côté SERVEUR (diagnostic), le client ne reçoit que le message exploitable.
        log.warn("[IA] échec de l'appel au fournisseur : {}", message);
        log.debug("[IA] trace complète de l'échec fournisseur", exception);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.BAD_GATEWAY.value());
        body.put("error", PROVIDER_FAILURE);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
