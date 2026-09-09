package com.coach.financier.model;

/**
 * Famille de produits bancaires. Le mapping {@code ProjectType -> familles autorisées}
 * est une règle métier implémentée côté Java (voir ProjectProductMappingService).
 */
public enum ProductFamily {
    // Crédit à la consommation
    AUTO_LOAN,
    PERSONAL_LOAN,
    CONSUMER_CREDIT,
    INSTALLMENT_PAYMENT,
    HOME_IMPROVEMENT_LOAN,
    REVOLVING_CREDIT,
    STUDENT_LOAN,
    YOUNG_ACTIVE_LOAN,
    DRIVER_LICENSE_LOAN,
    DEBT_CONSOLIDATION,
    // Crédit immobilier
    MORTGAGE,
    HOME_SAVINGS,
    // Épargne / placements
    SAVINGS_PRODUCT,
    TERM_DEPOSIT,
    LIFE_INSURANCE,
    RETIREMENT_SAVINGS,
    EQUITY_INVESTMENT,
    // Assurances
    INSURANCE_AUTO,
    INSURANCE_HOME,
    INSURANCE_BORROWER
}
