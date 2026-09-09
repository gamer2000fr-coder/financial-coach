package com.coach.financier.model;

/**
 * Type de projet du client, détecté par l'IA de compréhension puis utilisé par le
 * moteur métier Java pour filtrer les familles de produits autorisées.
 */
public enum ProjectType {
    VEHICLE,
    REAL_ESTATE_PURCHASE,
    HOME_WORK,
    ELECTRONICS,
    FURNITURE,
    TRAVEL,
    EDUCATION,
    WEDDING,
    HEALTH_EXPENSE,
    CASH_NEED,
    DEBT_RESTRUCTURING,
    SAVINGS,
    BUDGET,
    INVESTMENT,
    INSURANCE,
    OTHER_FINANCIAL,
    UNKNOWN
}
