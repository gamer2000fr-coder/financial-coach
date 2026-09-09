package com.coach.financier.service;

import com.coach.financier.model.CreditSimulation;
import com.coach.financier.model.CreditSimulationRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Calcul déterministe de mensualité de crédit (annuité constante, taux mensuel = TAEG/12).
 * Le LLM ne réalise jamais ce calcul lui-même ; s'il manque le TAEG, on ne simule pas.
 */
@Service
public class CreditSimulationService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;

    /**
     * @return la simulation, ou {@code null} si un paramètre indispensable manque
     *         (montant, durée, TAEG) — on n'invente jamais de valeur.
     */
    public CreditSimulation simulate(CreditSimulationRequest request) {
        if (request == null) {
            return null;
        }
        BigDecimal amount = request.getAmount();
        Integer duration = request.getDurationMonths();
        BigDecimal taeg = request.getTaeg();
        if (amount == null || duration == null || taeg == null
                || amount.signum() <= 0 || duration <= 0) {
            return null;
        }
        // Taux mensuel = TAEG annuel / 12.
        BigDecimal monthlyRate = taeg.divide(BigDecimal.valueOf(1200), 12, ROUND);
        if (monthlyRate.signum() == 0) {
            // Crédit à 0 % : mensualité = capital / durée.
            BigDecimal monthly = amount.divide(BigDecimal.valueOf(duration), 2, ROUND);
            BigDecimal total = amount.setScale(2, ROUND);
            return new CreditSimulation(amount, duration, taeg, monthly, total, BigDecimal.ZERO.setScale(2, ROUND));
        }
        // M = C * r / (1 - (1+r)^-n)
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        double pow = Math.pow(onePlusR.doubleValue(), -duration);
        BigDecimal denominator = BigDecimal.ONE.subtract(BigDecimal.valueOf(pow), MC);
        BigDecimal monthly = amount.multiply(monthlyRate, MC)
                .divide(denominator, MC)
                .setScale(2, ROUND);
        BigDecimal totalRepayment = monthly.multiply(BigDecimal.valueOf(duration)).setScale(2, ROUND);
        BigDecimal totalCost = totalRepayment.subtract(amount).setScale(2, ROUND);
        return new CreditSimulation(amount, duration, taeg, monthly, totalRepayment, totalCost);
    }
}
