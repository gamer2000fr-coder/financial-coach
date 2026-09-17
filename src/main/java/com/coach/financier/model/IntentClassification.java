package com.coach.financier.model;

import java.math.BigDecimal;

/**
 * Résultat de l'IA de compréhension (premier appel). L'IA ne sélectionne aucun
 * produit : elle comprend la demande et renvoie cette structure ; le backend applique
 * ensuite les règles métier de compatibilité produit.
 */
public class IntentClassification {

    private boolean inScope = true;
    private FinancialIntent intent = FinancialIntent.OTHER;
    private ProjectType projectType = ProjectType.UNKNOWN;
    private String projectObject;
    private BigDecimal amount;
    private String currency = "EUR";
    private boolean refersToCurrentProject;
    private boolean projectChanged;
    private ConfidenceLevel confidence = ConfidenceLevel.LOW;
    private String reason;

    public IntentClassification() {
    }

    public static IntentClassification outOfScope(String reason) {
        IntentClassification c = new IntentClassification();
        c.inScope = false;
        c.intent = FinancialIntent.OUT_OF_SCOPE;
        c.projectType = ProjectType.UNKNOWN;
        c.confidence = ConfidenceLevel.HIGH;
        c.reason = reason;
        return c;
    }

    public boolean isOutOfScope() {
        return !inScope;
    }

    /** Faut-il demander une précision (projet inconnu ou confiance faible) avant de proposer des produits ? */
    public boolean needsClarification() {
        return inScope && (projectType == ProjectType.UNKNOWN || confidence == ConfidenceLevel.LOW);
    }

    public boolean inScope() { return inScope; }
    public void setInScope(boolean inScope) { this.inScope = inScope; }
    public boolean getInScope() { return inScope; }

    public FinancialIntent getIntent() { return intent; }
    public void setIntent(FinancialIntent intent) { this.intent = intent == null ? FinancialIntent.OTHER : intent; }

    public ProjectType getProjectType() { return projectType; }
    public void setProjectType(ProjectType projectType) { this.projectType = projectType == null ? ProjectType.UNKNOWN : projectType; }

    public String getProjectObject() { return projectObject; }
    public void setProjectObject(String projectObject) { this.projectObject = projectObject; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public boolean isRefersToCurrentProject() { return refersToCurrentProject; }
    public void setRefersToCurrentProject(boolean refersToCurrentProject) { this.refersToCurrentProject = refersToCurrentProject; }

    public boolean isProjectChanged() { return projectChanged; }
    public void setProjectChanged(boolean projectChanged) { this.projectChanged = projectChanged; }

    public ConfidenceLevel getConfidence() { return confidence; }
    public void setConfidence(ConfidenceLevel confidence) { this.confidence = confidence == null ? ConfidenceLevel.LOW : confidence; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    /**
     * Catégorie « legacy » (AIModels.RequestCategory) dérivée pour la réponse HTTP
     * et l'affichage, afin de préserver l'API existante du frontend.
     */
    public AIModels.RequestCategory toLegacyCategory() {
        if (!inScope) {
            return AIModels.RequestCategory.OUT_OF_SCOPE;
        }
        boolean financing = intent == FinancialIntent.FINANCING_REQUEST
                || intent == FinancialIntent.CREDIT_INFORMATION
                || intent == FinancialIntent.PRODUCT_INFORMATION;
        return switch (projectType) {
            case SAVINGS -> AIModels.RequestCategory.SAVINGS;
            case BUDGET -> AIModels.RequestCategory.BUDGET;
            // Un placement (PEA, assurance-vie, PER) reste une question d'ÉPARGNE : l'intention
            // PRODUCT_INFORMATION ne doit pas le faire passer pour un crédit.
            case INVESTMENT -> AIModels.RequestCategory.SAVINGS;
            case INSURANCE -> AIModels.RequestCategory.BANK_PRODUCT;
            // Un rachat/regroupement de crédits est une opération de CRÉDIT, même si l'intention a été
            // dégradée (intent hors liste → OTHER) : la catégorie legacy suit donc le projet.
            case DEBT_RESTRUCTURING -> AIModels.RequestCategory.CREDIT;
            case CASH_NEED -> financing ? AIModels.RequestCategory.CREDIT : AIModels.RequestCategory.CASHFLOW;
            case VEHICLE, REAL_ESTATE_PURCHASE, HOME_WORK, ELECTRONICS, FURNITURE, TRAVEL,
                 EDUCATION, WEDDING, HEALTH_EXPENSE ->
                    financing ? AIModels.RequestCategory.CREDIT : AIModels.RequestCategory.PURCHASE_PROJECT;
            default -> AIModels.RequestCategory.OTHER_FINANCIAL;
        };
    }
}
