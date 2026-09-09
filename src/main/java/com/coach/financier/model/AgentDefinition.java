package com.coach.financier.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Définition d'un agent (spécialisé par thème ou générique), lue depuis
 * {@code ./agent/agents.json}. Le champ {@code prompt} désigne le fichier
 * {@code ./agent/<prompt>} contenant le prompt système de l'agent ; le champ
 * {@code data} liste les chemins du catalogue à injecter d'office à l'agent.
 */
public class AgentDefinition {
    private String id;
    private String libelle;
    private String theme;
    private String prompt;
    private List<String> data = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getLibelle() { return libelle; }
    public void setLibelle(String libelle) { this.libelle = libelle; }

    /** Clé de routage du thème (ex. "credit_conso"), alignée sur la classification. */
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    /** Nom du fichier prompt de l'agent sous /agent (ex. "credit-conso.txt"). */
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    /** Chemins /data/... à inclure d'office dans les données envoyées à l'agent. */
    public List<String> getData() { return data; }
    public void setData(List<String> data) { this.data = data == null ? new ArrayList<>() : data; }
}
