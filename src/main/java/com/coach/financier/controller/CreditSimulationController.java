package com.coach.financier.controller;

import com.coach.financier.model.CreditSimulation;
import com.coach.financier.service.CreditSimulationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simulation de crédit DÉTERMINISTE, à partir de la grille de taux du crédit à la consommation
 * ({@code data/catalogue/taux/grilles_taux_credit_conso.json}) — les mêmes taux que ceux fournis au Coach.
 * <p>
 * Aucun taux n'est inventé : produit inconnu, couple (montant, durée) non couvert par la grille, crédit
 * renouvelable, ou TAEG au-delà du taux d'usure renseigné ⇒ {@code 400} avec le motif.
 * La réponse porte systématiquement la mention « simulation indicative et non contractuelle : la
 * souscription fait foi » et les hypothèses de la grille.
 */
@RestController
@RequestMapping("/api/credit")
public class CreditSimulationController {

    /** Corps de la requête : produit de la grille + montant + durée en mois. */
    public record SimulationBody(String productId, BigDecimal amount, Integer durationMonths) {
    }

    private final CreditSimulationService simulationService;

    public CreditSimulationController(CreditSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/simulation")
    public ResponseEntity<Object> simulate(@RequestBody(required = false) SimulationBody body) {
        String productId = body == null ? null : body.productId();
        BigDecimal amount = body == null ? null : body.amount();
        Integer durationMonths = body == null ? null : body.durationMonths();

        Optional<String> refusal = simulationService.refusal(productId, amount, durationMonths);
        if (refusal.isPresent()) {
            return ResponseEntity.badRequest().body(error(refusal.get()));
        }
        return simulationService.simulateFromGrid(productId, amount, durationMonths)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.badRequest().body(
                        error("La simulation n'a pas pu être calculée à partir de la grille de taux.")));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", "SIMULATION_IMPOSSIBLE");
        error.put("message", message);
        error.put("disclaimer", CreditSimulationService.DISCLAIMER);
        return error;
    }
}
