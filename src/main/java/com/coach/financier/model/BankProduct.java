package com.coach.financier.model;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produit bancaire du catalogue machine-readable (products.json).
 * Certains champs peuvent être {@code null} ; on n'invente jamais une valeur manquante.
 */
public class BankProduct {

    private String id;
    private String name;
    private ProductFamily family;
    private Set<ProjectType> allowedProjectTypes = new LinkedHashSet<>();
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer minDurationMonths;
    private Integer maxDurationMonths;
    private BigDecimal taeg;
    private String description;
    private List<String> eligibilityNotes = List.of();
    /**
     * Délai de mise à disposition des fonds TEL QU'IL EST RENSEIGNÉ (ex. {@code {"minDays": 8}} ou
     * {@code {"transferHours": 48}}). Jamais déduit ni complété : absent ⇒ le délai n'est pas documenté et
     * le Coach doit le dire, sans en inventer un.
     */
    private java.util.Map<String, Object> fundAvailabilityDelay;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ProductFamily getFamily() { return family; }
    public void setFamily(ProductFamily family) { this.family = family; }

    public Set<ProjectType> getAllowedProjectTypes() { return allowedProjectTypes; }
    public void setAllowedProjectTypes(Set<ProjectType> allowedProjectTypes) {
        this.allowedProjectTypes = allowedProjectTypes == null ? new LinkedHashSet<>() : allowedProjectTypes;
    }

    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }

    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }

    public Integer getMinDurationMonths() { return minDurationMonths; }
    public void setMinDurationMonths(Integer minDurationMonths) { this.minDurationMonths = minDurationMonths; }

    public Integer getMaxDurationMonths() { return maxDurationMonths; }
    public void setMaxDurationMonths(Integer maxDurationMonths) { this.maxDurationMonths = maxDurationMonths; }

    public BigDecimal getTaeg() { return taeg; }
    public void setTaeg(BigDecimal taeg) { this.taeg = taeg; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getEligibilityNotes() { return eligibilityNotes; }
    public void setEligibilityNotes(List<String> eligibilityNotes) {
        this.eligibilityNotes = eligibilityNotes == null ? List.of() : eligibilityNotes;
    }

    /** Délai de mise à disposition des fonds documenté, ou {@code null} si le catalogue ne le renseigne pas. */
    public java.util.Map<String, Object> getFundAvailabilityDelay() { return fundAvailabilityDelay; }
    public void setFundAvailabilityDelay(java.util.Map<String, Object> fundAvailabilityDelay) {
        this.fundAvailabilityDelay = fundAvailabilityDelay == null || fundAvailabilityDelay.isEmpty()
                ? null : fundAvailabilityDelay;
    }

    /** Réduction au strict nécessaire pour le contexte coach (compact). */
    public java.util.Map<String, Object> toCompactMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("family", family == null ? null : family.name());
        map.put("minAmount", minAmount);
        map.put("maxAmount", maxAmount);
        map.put("minDurationMonths", minDurationMonths);
        map.put("maxDurationMonths", maxDurationMonths);
        map.put("taeg", taeg);
        // Délai de mise à disposition : présent UNIQUEMENT s'il est renseigné (jamais de valeur inventée).
        if (fundAvailabilityDelay != null) {
            map.put("fundAvailabilityDelay", fundAvailabilityDelay);
        }
        return map;
    }
}
