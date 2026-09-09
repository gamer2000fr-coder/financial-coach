package com.coach.financier.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

public final class BankingModels {
    private BankingModels() {}

    public record Transaction(
            String transactionId,
            String accountId,
            LocalDate date,
            String label,
            Double debit,
            Double credit,
            String category
    ) {}

    public record Credit(
            String creditId,
            String produit,
            Double mensualite,
            String fin
    ) {}

    public record SavingsAccount(
            String savingsAccountId,
            String produit,
            Double solde
    ) {}

    public record BankingSnapshot(
            JsonNode rawData,
            List<Transaction> transactions,
            List<Credit> credits,
            List<SavingsAccount> savingsAccounts
    ) {}
}
