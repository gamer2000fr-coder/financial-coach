package com.coach.financier.model;

import java.math.BigDecimal;

/** Demande de simulation de crédit (montant, durée, TAEG) — calcul déterministe côté Java. */
public class CreditSimulationRequest {

    private BigDecimal amount;
    private Integer durationMonths;
    private BigDecimal taeg;

    public CreditSimulationRequest() {
    }

    public CreditSimulationRequest(BigDecimal amount, Integer durationMonths, BigDecimal taeg) {
        this.amount = amount;
        this.durationMonths = durationMonths;
        this.taeg = taeg;
    }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getDurationMonths() { return durationMonths; }
    public void setDurationMonths(Integer durationMonths) { this.durationMonths = durationMonths; }

    public BigDecimal getTaeg() { return taeg; }
    public void setTaeg(BigDecimal taeg) { this.taeg = taeg; }
}
