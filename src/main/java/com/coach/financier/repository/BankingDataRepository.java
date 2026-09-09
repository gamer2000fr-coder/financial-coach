package com.coach.financier.repository;

import com.coach.financier.model.BankingModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class BankingDataRepository {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE);

    private final JsonNode root;
    private final List<BankingModels.Transaction> transactions;
    private final List<BankingModels.Credit> credits;
    private final List<BankingModels.SavingsAccount> savingsAccounts;

    public BankingDataRepository(ObjectMapper objectMapper,
                                 @Value("${app.data.banking-file:./data/banking_demo_normalized.json}") String bankingFile)
            throws IOException {
        this.root = loadRoot(objectMapper, bankingFile);
        this.transactions = loadTransactions(root);
        this.credits = loadCredits(root);
        this.savingsAccounts = loadSavings(root);
    }

    /**
     * Les données bancaires sont lues depuis le système de fichiers (répertoire consolidé
     * {@code ./data} à la racine du projet). Un repli sur le classpath
     * {@code data/banking_demo_normalized.json} est conservé pour les exécutions packagées
     * qui embarquent le fichier dans les resources.
     */
    private static JsonNode loadRoot(ObjectMapper objectMapper, String bankingFile) throws IOException {
        Path path = Path.of(bankingFile).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                return objectMapper.readTree(input);
            }
        }
        try (InputStream input = new ClassPathResource("data/banking_demo_normalized.json").getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    public BankingModels.BankingSnapshot loadSnapshot() {
        return new BankingModels.BankingSnapshot(root, transactions, credits, savingsAccounts);
    }

    public List<BankingModels.Transaction> findTransactions(LocalDate from, LocalDate to, List<String> categories,
                                                             Double minimumAmount, Integer limit) {
        var normalizedCategories = categories == null ? List.<String>of() : categories.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();

        return transactions.stream()
                .filter(t -> from == null || !t.date().isBefore(from))
                .filter(t -> to == null || !t.date().isAfter(to))
                .filter(t -> normalizedCategories.isEmpty() || normalizedCategories.contains(t.category()))
                .filter(t -> minimumAmount == null || amountOf(t) >= minimumAmount)
                .sorted((a, b) -> b.date().compareTo(a.date()))
                .limit(limit == null ? 200 : Math.max(1, Math.min(limit, 1000)))
                .toList();
    }

    public List<BankingModels.Transaction> allTransactions() { return transactions; }
    public List<BankingModels.Credit> credits() { return credits; }
    public List<BankingModels.SavingsAccount> savingsAccounts() { return savingsAccounts; }

    private static double amountOf(BankingModels.Transaction t) {
        return t.debit() != null ? t.debit() : (t.credit() != null ? t.credit() : 0d);
    }

    private List<BankingModels.Transaction> loadTransactions(JsonNode root) {
        List<BankingModels.Transaction> result = new ArrayList<>();
        for (JsonNode account : root.path("accounts")) {
            String accountId = account.path("accountId").asText();
            for (JsonNode month : account.path("transactions").path("months")) {
                for (JsonNode op : month.path("operations")) {
                    String dateText = op.path("date").asText(null);
                    if (dateText == null || dateText.isBlank()) continue;
                    LocalDate date = LocalDate.parse(dateText, DATE_FORMATTER);
                    Double debit = nullableDouble(op, "debit");
                    Double credit = nullableDouble(op, "credit");
                    String label = op.path("nature_operation").asText("");
                    result.add(new BankingModels.Transaction(
                            op.path("transactionId").asText(),
                            op.path("accountId").asText(accountId),
                            date,
                            label,
                            debit,
                            credit,
                            categorize(label)
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<BankingModels.Credit> loadCredits(JsonNode root) {
        List<BankingModels.Credit> result = new ArrayList<>();
        for (JsonNode node : root.path("credits")) {
            result.add(new BankingModels.Credit(
                    node.path("creditId").asText(),
                    node.path("produit").asText(),
                    nullableDouble(node, "mensualite"),
                    node.path("fin").asText(null)
            ));
        }
        return List.copyOf(result);
    }

    private List<BankingModels.SavingsAccount> loadSavings(JsonNode root) {
        List<BankingModels.SavingsAccount> result = new ArrayList<>();
        for (JsonNode node : root.path("savingsAccounts")) {
            result.add(new BankingModels.SavingsAccount(
                    savingsAccountId(node),
                    node.path("produit").asText(""),
                    nullableDouble(node, "solde")
            ));
        }
        return List.copyOf(result);
    }

    /**
     * Dans le jeu de données normalisé, les comptes d'épargne sont représentés comme
     * des comptes bancaires (clé {@code accountId}, ex. "LIVRET-A-001"), et non plus
     * comme des produits (ancienne clé {@code savingsAccountId}). On accepte les deux
     * formes pour rester robuste quelle que soit la structure du fichier chargé.
     */
    private static String savingsAccountId(JsonNode node) {
        String accountId = node.path("accountId").asText(null);
        return (accountId == null || accountId.isBlank())
                ? node.path("savingsAccountId").asText("")
                : accountId;
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static String categorize(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.toUpperCase(Locale.ROOT);
        if (containsAny(label, "SALAIRE", "PAIE", "REMUNERATION")) return "SALARY";
        if (containsAny(label, "LOYER", "IMMO", "CHARGE IMMOBILIERE")) return "HOUSING";
        if (containsAny(label, "AUCHAN", "CARREFOUR", "MONOPRIX", "INTERMARCHE", "LECLERC", "CRF", "SUPERMAR")) return "GROCERIES";
        if (containsAny(label, "RESTAUR", "GOURMANDISE", "BOULANGER", "PATISSERIE", "CAFE", "MC DONALD", "KFC")) return "RESTAURANT";
        if (containsAny(label, "AMAZON", "CASTORAMA", "FNAC", "DACIA", "DECATHLON", "COMMERCE ELECTRONIQUE")) return "SHOPPING";
        if (containsAny(label, "EDF", "ENGIE", "ELECTRIC", "EAU ")) return "UTILITIES";
        if (containsAny(label, "ORANGE", "FREE", "SFR", "BOUYGUES", "TELECOM")) return "TELECOM";
        if (containsAny(label, "TOTAL", "ESSO", "SHELL", "CARBURANT", "BP ")) return "TRANSPORT";
        if (containsAny(label, "SNCF", "RATP", "NAVIGO", "UBER", "BOLT", "PAYBYPHONE", "PARKING")) return "TRANSPORT";
        if (containsAny(label, "IMPOT", "TRESOR", "TAX")) return "TAXES";
        if (containsAny(label, "ASSURANCE", "MAIF", "AXA", "MACIF")) return "INSURANCE";
        if (containsAny(label, "INTERETS", "LIVRET", "EPARGNE")) return "SAVINGS";
        if (containsAny(label, "CREDIT", "PRET", "MENSUALITE")) return "LOAN";
        if (label.contains("VIR RECU")) return "TRANSFER_IN";
        if (label.contains("VIR EMIS") || label.contains("VIREMENT")) return "TRANSFER_OUT";
        if (containsAny(label, "PHARMACIE", "MEDECIN", "DOCTEUR", "SANTE")) return "HEALTH";
        return "OTHER";
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
