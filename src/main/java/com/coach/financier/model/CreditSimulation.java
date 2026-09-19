package com.coach.financier.model;

import java.math.BigDecimal;

/**
 * Résultat d'une simulation de crédit à échéances constantes.
 * <p>
 * Deux voies d'obtention, avec la MÊME méthode de calcul (annuité constante) :
 * <ul>
 *   <li>le Coach LLM, qui s'appuie sur la grille de taux fournie ({@code grilles_taux_credit_conso.json}) ;</li>
 *   <li>le backend, via {@code CreditSimulationService} (mêmes règles, mêmes taux, calcul déterministe).</li>
 * </ul>
 * Toute simulation est <b>indicative et non contractuelle</b> : seuls le contrat de prêt et la souscription
 * signés font foi ({@link #getDisclaimer()}).
 */
public class CreditSimulation {

    private final BigDecimal amount;
    private final int durationMonths;
    private final BigDecimal taegPercent;
    private final BigDecimal monthlyRatePercent;
    private final BigDecimal monthlyPayment;
    private final BigDecimal lastInstallment;
    private final BigDecimal totalRepayment;
    private final BigDecimal totalCreditCost;
    private final String productId;
    private final String productName;
    private final String ruleId;
    private final String disclaimer;
    private final String hypotheses;

    public CreditSimulation(BigDecimal amount,
                            int durationMonths,
                            BigDecimal taegPercent,
                            BigDecimal monthlyRatePercent,
                            BigDecimal monthlyPayment,
                            BigDecimal lastInstallment,
                            BigDecimal totalRepayment,
                            BigDecimal totalCreditCost,
                            String productId,
                            String productName,
                            String ruleId,
                            String disclaimer,
                            String hypotheses) {
        this.amount = amount;
        this.durationMonths = durationMonths;
        this.taegPercent = taegPercent;
        this.monthlyRatePercent = monthlyRatePercent;
        this.monthlyPayment = monthlyPayment;
        this.lastInstallment = lastInstallment;
        this.totalRepayment = totalRepayment;
        this.totalCreditCost = totalCreditCost;
        this.productId = productId;
        this.productName = productName;
        this.ruleId = ruleId;
        this.disclaimer = disclaimer;
        this.hypotheses = hypotheses;
    }

    public BigDecimal getAmount() { return amount; }

    public int getDurationMonths() { return durationMonths; }

    /** TAEG annuel (en pourcentage) issu de la grille de taux. */
    public BigDecimal getTaegPercent() { return taegPercent; }

    /** Taux mensuel équivalent : (1 + TAEG/100)^(1/12) − 1, en pourcentage. */
    public BigDecimal getMonthlyRatePercent() { return monthlyRatePercent; }

    public BigDecimal getMonthlyPayment() { return monthlyPayment; }

    /** Dernière échéance, ajustée pour solder exactement le capital et les intérêts. */
    public BigDecimal getLastInstallment() { return lastInstallment; }

    public BigDecimal getTotalRepayment() { return totalRepayment; }

    public BigDecimal getTotalCreditCost() { return totalCreditCost; }

    public String getProductId() { return productId; }

    public String getProductName() { return productName; }

    /** Identifiant de la règle de la grille appliquée (traçabilité du TAEG). */
    public String getRuleId() { return ruleId; }

    /** Mention obligatoire : simulation indicative, la souscription fait foi. */
    public String getDisclaimer() { return disclaimer; }

    /** Hypothèses retenues (hors assurance facultative, frais de dossier, TAEG dépendant du dossier). */
    public String getHypotheses() { return hypotheses; }
}
