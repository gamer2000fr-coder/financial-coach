package com.coach.financier.service;

import com.coach.financier.model.CreditSimulation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Calcul déterministe d'une simulation de crédit à échéances constantes, à partir de la GRILLE DE TAUX
 * ({@link CreditRateGridService}) — la même grille que celle fournie au Coach.
 * <p>
 * Deux garde-fous structurants :
 * <ul>
 *   <li>aucun taux n'est inventé : sans règle de grille couvrant le couple (montant, durée), la méthode
 *       renvoie {@link Optional#empty()} avec un motif ;</li>
 *   <li>toute simulation est INDICATIVE et NON CONTRACTUELLE ({@link #DISCLAIMER}) : seuls le contrat de
 *       prêt et la souscription signés font foi.</li>
 * </ul>
 * Le crédit renouvelable (Alterna) ne fait pas l'objet d'une simulation d'amortissement : son taux s'applique
 * aux sommes réellement utilisées.
 * <p>
 * Méthode de calcul (celle de la grille) : taux mensuel {@code i = (1+TAEG/100)^(1/12) - 1} puis
 * {@code M = C*i / (1 - (1+i)^-n)} ; la dernière échéance est ajustée pour solder capital et intérêts.
 */
@Service
public class CreditSimulationService {

    /** Mention obligatoire de toute simulation (règle des prompts du Coach). */
    public static final String DISCLAIMER =
            "Simulation indicative et non contractuelle : seuls le contrat de prêt et la souscription "
                    + "signés font foi.";

    /** Hypothèses retenues, celles de la grille de taux. */
    public static final String HYPOTHESES =
            "Taux hors assurance facultative ; frais de dossier selon l'hypothèse de la grille (0 €) ; "
                    + "TAEG applicable dépendant du dossier et de la durée.";

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final MathContext PRECISION = new MathContext(20, RoundingMode.HALF_UP);
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CreditRateGridService gridService;

    public CreditSimulationService(CreditRateGridService gridService) {
        this.gridService = gridService;
    }

    /**
     * Simulation d'un produit pour un couple (montant, durée), à partir des TAEG de la grille.
     *
     * @return la simulation, ou vide si le produit est inconnu, si aucune règle ne couvre ce couple
     *         (montant, durée), pour un crédit renouvelable, ou si le TAEG dépasserait le taux d'usure
     *         renseigné par la grille : dans tous ces cas, on ne chiffre rien.
     */
    public Optional<CreditSimulation> simulateFromGrid(String productId, BigDecimal amount, Integer durationMonths) {
        if (refusal(productId, amount, durationMonths).isPresent()) {
            return Optional.empty();
        }
        CreditRateGridService.RateRule rule = gridService.ruleFor(productId, amount, durationMonths).orElse(null);
        if (rule == null) {
            return Optional.empty();
        }
        return Optional.of(compute(rule, amount, durationMonths));
    }

    /** Motif du refus (vide si la simulation est possible) : sert à expliquer le refus à l'appelant. */
    public Optional<String> refusal(String productId, BigDecimal amount, Integer durationMonths) {
        if (productId == null || productId.isBlank()) {
            return Optional.of("Produit manquant. Produits disponibles dans la grille : "
                    + String.join(", ", gridService.productIds()) + ".");
        }
        if (amount == null || amount.signum() <= 0) {
            return Optional.of("Montant manquant ou invalide : sans montant, aucun chiffrage n'est possible.");
        }
        if (durationMonths == null || durationMonths <= 0) {
            return Optional.of("Durée manquante ou invalide : sans durée, aucun chiffrage n'est possible.");
        }
        if (gridService.rulesFor(productId).isEmpty()) {
            return Optional.of("Produit absent de la grille de taux : " + productId
                    + ". Produits disponibles : " + String.join(", ", gridService.productIds()) + ".");
        }
        if (gridService.isRevolving(productId)) {
            return Optional.of("Crédit renouvelable : le taux s'applique aux sommes réellement utilisées, aucune "
                    + "mensualité d'amortissement à échéances constantes ne peut être présentée.");
        }
        CreditRateGridService.RateRule rule = gridService.ruleFor(productId, amount, durationMonths).orElse(null);
        if (rule == null) {
            return Optional.of("Aucune règle de la grille ne couvre " + amount.toPlainString() + " € sur "
                    + durationMonths + " mois pour ce produit : le TAEG n'est pas renseigné pour cette tranche.");
        }
        BigDecimal ceiling = gridService.usuryCeilingPercent(amount).orElse(null);
        if (ceiling != null && rule.taegPercent() != null && rule.taegPercent().compareTo(ceiling) > 0) {
            return Optional.of("TAEG de la grille (" + rule.taegPercent().toPlainString()
                    + " %) supérieur au taux d'usure renseigné pour cette tranche de montant ("
                    + ceiling.toPlainString() + " %).");
        }
        return Optional.empty();
    }

    /** Taux mensuel équivalent, en pourcentage : (1 + TAEG/100)^(1/12) − 1 (méthode de la grille). */
    public BigDecimal monthlyRatePercent(BigDecimal taegPercent) {
        return monthlyRate(taegPercent).multiply(HUNDRED).setScale(6, ROUND);
    }

    private BigDecimal monthlyRate(BigDecimal taegPercent) {
        double annual = taegPercent.doubleValue() / 100.0;
        double monthly = Math.pow(1.0 + annual, 1.0 / 12.0) - 1.0;
        return BigDecimal.valueOf(monthly).round(PRECISION);
    }

    private CreditSimulation compute(CreditRateGridService.RateRule rule, BigDecimal amount, int durationMonths) {
        BigDecimal i = monthlyRate(rule.taegPercent());
        BigDecimal monthly;
        BigDecimal last;
        BigDecimal total;
        if (i.signum() == 0) {
            // Crédit à 0 % : mensualité = capital / durée, dernière échéance ajustée (coût total nul).
            monthly = amount.divide(BigDecimal.valueOf(durationMonths), 2, ROUND);
            last = amount.subtract(monthly.multiply(BigDecimal.valueOf(durationMonths - 1L))).setScale(2, ROUND);
            total = amount.setScale(2, ROUND);
        } else {
            BigDecimal onePlusI = BigDecimal.ONE.add(i, PRECISION);
            double pow = Math.pow(onePlusI.doubleValue(), -durationMonths);
            BigDecimal denominator = BigDecimal.ONE.subtract(BigDecimal.valueOf(pow), MC);
            monthly = amount.multiply(i, MC).divide(denominator, 2, ROUND);
            // Solde restant dû après n-1 échéances, puis dernière échéance ajustée pour solder le crédit.
            double growthBefore = Math.pow(onePlusI.doubleValue(), durationMonths - 1);
            BigDecimal balance = amount.multiply(BigDecimal.valueOf(growthBefore), MC)
                    .subtract(monthly.multiply(BigDecimal.valueOf(growthBefore - 1.0), MC).divide(i, MC), MC);
            last = balance.multiply(onePlusI, MC).setScale(2, ROUND);
            total = monthly.multiply(BigDecimal.valueOf(durationMonths - 1L)).add(last).setScale(2, ROUND);
        }
        BigDecimal cost = total.subtract(amount).setScale(2, ROUND);
        return new CreditSimulation(amount, durationMonths, rule.taegPercent(),
                i.multiply(HUNDRED).setScale(6, ROUND), monthly, last, total, cost,
                rule.productId(), rule.productName(), rule.ruleId(), DISCLAIMER, HYPOTHESES);
    }
}
