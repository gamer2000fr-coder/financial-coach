package com.coach.financier.model;

import java.time.LocalDate;
import java.util.Map;

public record FinancialSummary(
        int periodMonths,
        LocalDate periodStart,
        LocalDate periodEnd,
        double averageMonthlyIncome,
        double medianMonthlyIncome,
        double averageMonthlyExpenses,
        double fixedExpenses,
        double variableExpenses,
        double averageMonthlySavings,
        double averageDisposableIncome,
        double currentAccountBalance,
        double savingsBalance,
        double monthlyLoanPayments,
        double debtServiceToIncomeRatio,
        double savingsToIncomeRatio,
        int overdraftOccurrences,
        double minimumObservedBalance,
        double largestRecentExpense,
        String largestRecentExpenseLabel,
        long transactionCount,
        Map<String, Double> averageMonthlyExpensesByCategory,
        double savingsToIncomeRatio3Months,
        String savingsRatePeriodLabel
) {}
