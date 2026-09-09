package com.coach.financier.service;

import com.coach.financier.model.BankingModels;
import com.coach.financier.model.FinancialSummary;
import com.coach.financier.repository.BankingDataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FinancialAnalysisService {
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM/yyyy");

    private final BankingDataRepository repository;

    public FinancialAnalysisService(BankingDataRepository repository) {
        this.repository = repository;
    }

    public FinancialSummary analyze() {
        List<BankingModels.Transaction> tx = repository.allTransactions();
        if (tx.isEmpty()) {
            return emptySummary();
        }

        LocalDate start = tx.stream().map(BankingModels.Transaction::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = tx.stream().map(BankingModels.Transaction::date).max(LocalDate::compareTo).orElseThrow();
        YearMonth firstMonth = YearMonth.from(start);
        YearMonth lastMonth = YearMonth.from(end);
        int periodMonths = (int) ChronoUnit.MONTHS.between(firstMonth, lastMonth) + 1;

        Map<YearMonth, Double> monthlyIncome = new LinkedHashMap<>();
        Map<YearMonth, Double> monthlyExpenses = new LinkedHashMap<>();
        Map<YearMonth, Double> monthlySavings = new LinkedHashMap<>();
        Map<String, Double> totalExpensesByCategory = new LinkedHashMap<>();

        for (long i = 0; i < periodMonths; i++) {
            YearMonth month = firstMonth.plusMonths(i);
            monthlyIncome.put(month, 0d);
            monthlyExpenses.put(month, 0d);
            monthlySavings.put(month, 0d);
        }

        for (BankingModels.Transaction t : tx) {
            YearMonth month = YearMonth.from(t.date());
            // On exclut les mouvements INTERNES d'épargne (virements vers l'épargne, retours
            // depuis l'épargne) : ce ne sont ni des revenus ni des dépenses de consommation,
            // sinon le taux d'épargne serait ~0 (compte courant plafonné).
            if (t.credit() != null && !isSavingsIn(t.label())) {
                monthlyIncome.merge(month, t.credit(), Double::sum);
            }
            if (t.debit() != null && !isSavingsOut(t.label())) {
                monthlyExpenses.merge(month, t.debit(), Double::sum);
                totalExpensesByCategory.merge(t.category(), t.debit(), Double::sum);
            }
        }

        for (YearMonth month : monthlySavings.keySet()) {
            monthlySavings.put(month, monthlyIncome.get(month) - monthlyExpenses.get(month));
        }

        double averageIncome = average(monthlyIncome.values().stream().toList());
        double averageExpenses = average(monthlyExpenses.values().stream().toList());
        double averageSavings = average(monthlySavings.values().stream().toList());
        double medianIncome = median(monthlyIncome.values().stream().toList());

        double fixedExpenses = averageOfCategories(totalExpensesByCategory,
                "HOUSING", "UTILITIES", "TELECOM", "TAXES", "INSURANCE", "LOAN");
        fixedExpenses /= periodMonths;
        double variableExpenses = Math.max(0d, averageExpenses - fixedExpenses);

        double currentBalance = latestClosingBalance();
        double savingsBalance = repository.savingsAccounts().stream()
                .map(BankingModels.SavingsAccount::solde)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        double monthlyLoanPayments = repository.credits().stream()
                .map(BankingModels.Credit::mensualite)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        double minBalance = computeObservedMinimum(start, end);
        int overdrafts = (int) tx.stream().filter(t -> false).count(); // kept at 0 unless statement balances are available.
        double largestExpense = tx.stream()
                .filter(t -> t.debit() != null)
                .max(java.util.Comparator.comparing(BankingModels.Transaction::debit))
                .map(BankingModels.Transaction::debit).orElse(0d);
        String largestExpenseLabel = tx.stream()
                .filter(t -> t.debit() != null)
                .max(java.util.Comparator.comparing(BankingModels.Transaction::debit))
                .map(BankingModels.Transaction::label).orElse("");

        Map<String, Double> averageMonthlyExpensesByCategory = new LinkedHashMap<>();
        totalExpensesByCategory.forEach((key, value) -> averageMonthlyExpensesByCategory.put(key, value / periodMonths));

        // Taux d'épargne MOYEN des 3 derniers mois ENTIERS : on ignore le mois civil courant
        // (partiel), donc si le dernier mois de données est le mois en cours, on recule d'un mois.
        YearMonth nowMonth = YearMonth.now();
        YearMonth lastFullMonth = lastMonth; // lastMonth déjà calculé plus haut (YearMonth.from(end))
        if (!lastFullMonth.isBefore(nowMonth) && lastFullMonth.isAfter(firstMonth)) {
            lastFullMonth = lastFullMonth.minusMonths(1);
        }
        YearMonth windowStart = lastFullMonth.minusMonths(2);
        if (windowStart.isBefore(firstMonth)) {
            windowStart = firstMonth; // pas assez d'historique : moyenne sur les mois disponibles.
        }
        double periodSavings = 0d;
        double periodIncome = 0d;
        for (YearMonth m = windowStart; !m.isAfter(lastFullMonth); m = m.plusMonths(1)) {
            periodSavings += monthlySavings.getOrDefault(m, 0d);
            periodIncome += monthlyIncome.getOrDefault(m, 0d);
        }
        double savingsToIncomeRatio3Months = ratioPrecise(periodSavings, periodIncome);
        String savingsRatePeriodLabel = formatPeriodLabel(windowStart, lastFullMonth);

        return new FinancialSummary(
                periodMonths,
                start,
                end,
                round(averageIncome),
                round(medianIncome),
                round(averageExpenses),
                round(fixedExpenses),
                round(variableExpenses),
                round(averageSavings),
                round(averageSavings),
                round(currentBalance),
                round(savingsBalance),
                round(monthlyLoanPayments),
                ratio(monthlyLoanPayments, averageIncome),
                ratio(averageSavings, averageIncome),
                overdrafts,
                round(minBalance),
                round(largestExpense),
                largestExpenseLabel,
                tx.size(),
                Map.copyOf(averageMonthlyExpensesByCategory),
                savingsToIncomeRatio3Months,
                savingsRatePeriodLabel
        );
    }

    /** Libellé français de la fenêtre des 3 derniers mois (ex. « juin – août 2026 »). */
    private static String formatPeriodLabel(YearMonth from, YearMonth to) {
        DateTimeFormatter monthOnly = DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH);
        DateTimeFormatter fullMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);
        if (from.getYear() == to.getYear()) {
            return from.format(monthOnly) + " – " + to.format(fullMonth);
        }
        return from.format(fullMonth) + " – " + to.format(fullMonth);
    }

    /** Virement DEBIT vers l'épargne (Logitel existant OU virement ajouté de plafonnement). */
    private static boolean isSavingsOut(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.toUpperCase(Locale.ROOT);
        return label.contains("LOGITEL") || label.contains("VERS COMPTE EPARGNE");
    }

    /** Crédit = retour DEPUIS l'épargne (virement reçu de soi / de la famille MARTIN). */
    private static boolean isSavingsIn(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.toUpperCase(Locale.ROOT);
        return label.contains("VIR RECU") && label.contains("MARTIN");
    }

    private double latestClosingBalance() {
        var root = repository.loadSnapshot().rawData();
        var months = root.path("accounts").path(0).path("transactions").path("months");
        if (!months.isArray() || months.isEmpty()) return 0d;
        var latest = months.get(months.size() - 1);
        var fields = latest.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.startsWith("nouveau_solde_")) return latest.path(field).asDouble();
        }
        return 0d;
    }

    private double computeObservedMinimum(LocalDate start, LocalDate end) {
        var root = repository.loadSnapshot().rawData();
        double min = Double.POSITIVE_INFINITY;
        for (var account : root.path("accounts")) {
            for (var month : account.path("transactions").path("months")) {
                double previous = firstNumericField(month, "solde_precedent_");
                if (!Double.isNaN(previous)) min = Math.min(min, previous);
                for (var tx : month.path("operations")) {
                    Double debit = nullableDouble(tx.get("debit"));
                    Double credit = nullableDouble(tx.get("credit"));
                    // Statement balances are authoritative in the source; this is a conservative intra-month estimate.
                    if (!Double.isNaN(previous)) {
                        previous += (credit == null ? 0d : credit) - (debit == null ? 0d : debit);
                        min = Math.min(min, previous);
                    }
                }
            }
        }
        return min == Double.POSITIVE_INFINITY ? 0d : min;
    }

    private static double firstNumericField(com.fasterxml.jackson.databind.JsonNode node, String prefix) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.startsWith(prefix) && node.path(field).isNumber()) return node.path(field).asDouble();
        }
        return Double.NaN;
    }

    private static Double nullableDouble(com.fasterxml.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? null : node.asDouble();
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0d;
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(mid - 1) + sorted.get(mid)) / 2d : sorted.get(mid);
    }

    private static double averageOfCategories(Map<String, Double> map, String... keys) {
        double sum = 0d;
        for (String key : keys) sum += map.getOrDefault(key, 0d);
        return sum;
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0d ? 0d : round(numerator / denominator);
    }

    /** Ratio non arrondi à 2 décimales (pour un affichage précis du taux du mois). */
    private static double ratioPrecise(double numerator, double denominator) {
        return denominator == 0d ? 0d : numerator / denominator;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static FinancialSummary emptySummary() {
        return new FinancialSummary(
                0, null, null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, "", 0L, Map.of(), 0d, ""
        );
    }
}
