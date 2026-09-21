package com.coach.financier.model;

import java.util.Locale;

/**
 * Catégorie MÉTIER d'une conversation, utilisée par l'annuaire du centre d'appels (colonne + filtre
 * « catégorie ») et par le score commercial.
 * <p>
 * Elle est déduite de façon DÉTERMINISTE : d'abord des familles de produits réellement présentées
 * pendant l'échange (source la plus fiable), sinon du type de projet, sinon « Autre ». Aucune valeur
 * n'est inventée : une conversation sans produit ni projet identifiable reste « Autre ».
 */
public enum ConversationCategory {
    CREDIT_CONSO("Crédit conso"),
    CREDIT_IMMO("Crédit immobilier"),
    EPARGNE("Épargne"),
    ASSURANCE("Assurance"),
    AUTRE("Autre");

    private final String label;

    ConversationCategory(String label) {
        this.label = label;
    }

    /** Libellé affiché (colonne, filtre, badge). */
    public String label() {
        return label;
    }

    /** Code technique (clé de filtre, valeur persistée). */
    public String code() {
        return name();
    }

    /** Catégorie déduite d'une famille de produits du catalogue. */
    public static ConversationCategory fromFamily(ProductFamily family) {
        if (family == null) {
            return AUTRE;
        }
        return switch (family) {
            case AUTO_LOAN, PERSONAL_LOAN, CONSUMER_CREDIT, INSTALLMENT_PAYMENT, HOME_IMPROVEMENT_LOAN,
                 REVOLVING_CREDIT, STUDENT_LOAN, YOUNG_ACTIVE_LOAN, DRIVER_LICENSE_LOAN,
                 DEBT_CONSOLIDATION -> CREDIT_CONSO;
            case MORTGAGE -> CREDIT_IMMO;
            case HOME_SAVINGS, SAVINGS_PRODUCT, TERM_DEPOSIT, RETIREMENT_SAVINGS, EQUITY_INVESTMENT,
                 LIFE_INSURANCE -> EPARGNE;
            case INSURANCE_AUTO, INSURANCE_HOME, INSURANCE_BORROWER -> ASSURANCE;
        };
    }

    /** Catégorie déduite d'un type de projet (repli quand aucun produit n'a été présenté). */
    public static ConversationCategory fromProjectType(ProjectType type) {
        if (type == null) {
            return AUTRE;
        }
        return switch (type) {
            case VEHICLE, HOME_WORK, ELECTRONICS, FURNITURE, TRAVEL, EDUCATION, WEDDING, HEALTH_EXPENSE,
                 CASH_NEED, DEBT_RESTRUCTURING -> CREDIT_CONSO;
            case REAL_ESTATE_PURCHASE -> CREDIT_IMMO;
            case SAVINGS, BUDGET, INVESTMENT -> EPARGNE;
            case INSURANCE -> ASSURANCE;
            case OTHER_FINANCIAL, UNKNOWN -> AUTRE;
        };
    }

    /** Lecture tolérante d'un code de filtre ({@code null}/inconnu/vide → {@code null} = pas de filtre). */
    public static ConversationCategory parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = code.trim().toUpperCase(Locale.ROOT);
        for (ConversationCategory category : values()) {
            if (category.name().equals(value)) {
                return category;
            }
        }
        return null;
    }
}
