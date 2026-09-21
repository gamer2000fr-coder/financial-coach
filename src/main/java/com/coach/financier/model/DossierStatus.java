package com.coach.financier.model;

import java.util.List;
import java.util.Locale;

/**
 * STATUT D'AVANCEMENT d'un dossier de conversation, du point de vue du centre d'appels : le fil de travail
 * du conseiller, du dossier qui vient d'arriver à sa conclusion.
 * <p>
 * Il est <b>saisi par le conseiller</b> (page « Centre d'appels ») et <b>conservé avec son historique</b> :
 * un dossier change de statut au fur et à mesure de l'avancement, et chaque changement est tracé (qui a
 * modifié quoi, quand, avec un commentaire facultatif).
 * <p>
 * Aucun statut n'est jamais déduit d'une supposition : à la clôture de la conversation, le dossier est
 * {@link #NOUVEAU} — c'est le conseiller qui fait avancer le dossier.
 */
public enum DossierStatus {
    NOUVEAU("Nouveau", 0),
    CONTACTE("Contacté", 1),
    QUALIFIE("Qualifié", 2),
    RDV("RDV planifié", 3),
    CONCLU("Conclu", 4),
    PERDU("Sans suite", 4),
    CLOTURE("Clôturé", 5);

    private final String label;
    private final int rank;

    DossierStatus(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    /** Libellé affiché (colonne, filtre, badge). */
    public String label() {
        return label;
    }

    /** Position dans le cycle de vie (sert au tri et au classement des filtres). */
    public int rank() {
        return rank;
    }

    /** Code technique (clé de filtre, valeur persistée). */
    public String code() {
        return name();
    }

    /** Statut terminal : le dossier est sorti du fil de travail. */
    public boolean terminal() {
        return this == CLOTURE || this == CONCLU || this == PERDU;
    }

    /** Statuts proposés à la saisie, dans l'ordre d'avancement. */
    public static List<DossierStatus> progression() {
        return List.of(values());
    }

    /**
     * Lecture tolérante d'un code ({@code null}/inconnu/vide → {@code null}) : un code inconnu ne doit JAMAIS
     * être transformé silencieusement en un autre statut.
     */
    public static DossierStatus parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = code.trim().toUpperCase(Locale.ROOT);
        for (DossierStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        return null;
    }

    /** Libellé d'un code quelconque, avec repli sur le code brut (jamais d'exception). */
    public static String labelOf(String code) {
        DossierStatus status = parse(code);
        return status == null ? (code == null ? "" : code) : status.label();
    }
}
