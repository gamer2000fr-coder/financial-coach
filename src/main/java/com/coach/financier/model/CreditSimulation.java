package com.coach.financier.model;

import java.math.BigDecimal;

/**
 * Résultat d'une simulation de crédit. Le calcul est fait par le backend
 * (CreditSimulationService), jamais par le LLM.
 */
public class CreditSimulation {

    private final BigDecimal amount;
    private final int durationMonths;
    private final BigDecimal taeg;
    private final BigDecimal monthlyPayment;
    private final BigDecimal totalRepayment;
    private final BigDecimal totalCreditCost;

    public CreditSimulation(BigDecimal amount, int durationMonths, BigDecimal taeg,
                            BigDecimal monthlyPayment, BigDecimal totalRepayment, BigDecimal totalCreditCost) {
        this.amount = amount;
        this.durationMonths = durationMonths;
        this.taeg = taeg;
        this.monthlyPayment = monthlyPayment;
        this.totalRepayment = totalRepayment;
        this.totalCreditCost = totalCreditCost;
    }

    public BigDecimal getAmount() { return amount; }
    public int getDurationMonths() { return durationMonths; }
    public BigDecimal getTaeg() { return taeg; }
    public BigDecimal getMonthlyPayment() { return monthlyPayment; }
    public BigDecimal getTotalRepayment() { return totalRepayment; }
    public BigDecimal getTotalCreditCost() { return totalCreditCost; }
}
