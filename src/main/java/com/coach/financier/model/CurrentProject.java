package com.coach.financier.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projet courant du client, conservé dans la session de conversation.
 * Il est mis à jour par le backend à partir de la classification (et non par le coach).
 */
public class CurrentProject {

    private ProjectType type = ProjectType.UNKNOWN;
    private String object;
    private BigDecimal amount;
    private String currency = "EUR";
    private Map<String, Object> attributes = new LinkedHashMap<>();
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();

    public CurrentProject() {
    }

    /** Applique les informations fournies par la classification (seules celles non nulles). */
    public void apply(IntentClassification classification) {
        if (classification.getProjectType() != null && classification.getProjectType() != ProjectType.UNKNOWN) {
            this.type = classification.getProjectType();
        }
        if (classification.getProjectObject() != null && !classification.getProjectObject().isBlank()) {
            this.object = classification.getProjectObject().trim();
        }
        if (classification.getAmount() != null) {
            this.amount = classification.getAmount();
        }
        if (classification.getCurrency() != null && !classification.getCurrency().isBlank()) {
            this.currency = classification.getCurrency();
        }
        this.lastUpdatedAt = LocalDateTime.now();
    }

    public ProjectType getType() { return type; }
    public void setType(ProjectType type) { this.type = type == null ? ProjectType.UNKNOWN : type; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes == null ? new LinkedHashMap<>() : attributes; }

    public LocalDateTime getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(LocalDateTime lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
