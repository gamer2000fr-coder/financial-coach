# Flux complet du coach financier (POC)

> Version : 2026-09-08 · Repose sur le modèle « agents spécialisés par thème » + filtrage métier Java.

## 1. Principe général : 3 couches

| Couche | Qui | Rôle |
|---|---|---|
| **Comprendre** | IA (LLM) : `classifyIntent` | périmètre, intention, type de projet, montant |
| **Décider** | Java : agents, mapping, `products.json`, `DataRequestService` | quel agent, quels produits possibles, quelles données fournir |
| **Expliquer / recommander** | IA (agent actif) | réponse pédagogique, choix final dans le périmètre autorisé |

Règle d'or : **le LLM comprend et explique, Java décide/filtre/calcule.**

---

## 2. Vue d'ensemble (schéma)

```mermaid
flowchart TD
    U["Message utilisateur (texte ou dictée 🎤)"]
    U --> G1{"Contrôle hors-sujet activé ?"}
    G1 -- "non" --> FORCED["Classification forcée : OTHER / OTHER_FINANCIAL"]
    G1 -- "oui" --> CLS["classifyIntent (LLM) : inScope, intent, projectType, amount, refers, projectChanged"]
    FORCED --> OUT2
    CLS --> OOS{"inScope ?"}
    OOS -- "false" --> R_OOS["Réponse 'hors sujet financier' (générique)"]
    OOS -- "true" --> PRJ["updateCurrentProject + analyse financière (FinancialSummary)"]
    PRJ --> REQ{"Intention produit ?<br/>(FINANCING_REQUEST / PRODUCT_INFORMATION)"}
    REQ -- "oui, projet inconnu" --> CLARIF["Question de clarification (pas de produits)"]
    REQ --> AGENT["Sélection de l'agent : thème explicite du message, sinon mapping projectType"]
    AGENT --> PROD["Backend : findCompatible(products.json) → compatibleProducts + restriction du catalogue"]
    PROD --> DATA["providedData : synthèse financière + data de l'agent actif (fiche + arbre de décision)"]
    DATA --> COACH["Appel coach (agent actif) :<br/>generic.txt + [agent_principal]→principal.txt + [agent]→prompt agent"]
    COACH --> LOOP{"status = NEED_DATA ?"}
    LOOP -- "oui (max 3)" --> FETCH["fetch des fichiers demandés (whitelist catalogue)"]
    FETCH --> DATA
    LOOP -- "non" --> LOG["Réponse finale + logs (prompt, agent, filtrage, réponse, historique)"]
```

---

## 3. Séquence détaillée d'un échange (`POST /api/chat`)

```mermaid
sequenceDiagram
    participant FE as Frontend (chat)
    participant CC as ChatController
    participant AI as AIService (Remote/Mock)
    participant MS as ProjectProductMappingService
    participant PC as ProductCatalogueService (products.json)
    participant AF as AgentFiles (agents.json)
    participant DR as DataRequestService (data.json)
    participant LLM as LLM coach (agent actif)

    FE->>CC: message + session + fournisseur
    CC->>AI: classifyIntent(message, projet courant)
    AI-->>CC: IntentClassification (inScope, intent, projectType, amount…)
    alt inScope = false (ou garde désactivée)
        CC-->>FE: réponse hors-sujet / OTHER
    else dans le périmètre
        CC->>CC: updateCurrentProject (type/objet/montant)
        CC->>CC: analyse financière → FinancialSummary
        Note over CC: Si intention produit et projet inconnu → question de clarification
        CC->>CC: selectAgentTheme → theme (generique / credit_conso / epargne / assurance…)
        CC->>PC: findCompatible(projectType, montant)
        PC->>MS: familles autorisées pour le type de projet
        PC-->>CC: compatibleProducts (menu autorisé)
        CC->>DR: lecture des data de l'agent actif (fiche json + cascade txt)
        CC->>AF: systemPromptFor(theme) = generic.txt + principal.txt ([agent_principal]) + prompt agent ([agent])
        loop jusqu'à 3 NEED_DATA
            CC->>LLM: prompt système + payload (summary, catalogue, providedData, compatibleProducts, currentProject)
            LLM-->>CC: AIAnswer (ANSWER / NEED_DATA + paths)
            alt NEED_DATA
                CC->>DR: fetch(paths, whitelist) → providedData += fichiers
            end
        end
        CC-->>FE: ChatResponse (statut, answer, agent utilisé)
        CC->>Logs: trace (prompt, agent, debug, réponse, historique session)
    end
```

---

## 4. Qui décide quoi (détail)

| Étape | Décision | Composant / fichier |
|---|---|---|
| Hors sujet | `inScope = false` si aucun lien financier | `classifieur.txt` (LLM) — repli `MockAIService` |
| Intention + type de projet | `intent`, `projectType`, montant, `refersToCurrentProject`, `projectChanged` | `classifyIntent` → `IntentClassification` |
| Projet courant | mise à jour de la session (remplacement si nouveau thème) | `ChatController.updateCurrentProject` |
| Besoin de produits | intention ∈ {FINANCING_REQUEST, PRODUCT_INFORMATION} | `requiresProducts` (Java) |
| Clarification | projet inconnu/confiance basse en demande produit | `ChatController` (pas de produits) |
| **Agent actif** | thème explicite du message (assurance, épargne, crédit immo…) sinon mapping `projectType → thème` | `selectAgentTheme` + `agents.json` |
| **Menu produits autorisé** | famille autorisée (par type de projet) + type autorisé + bornes montant | `ProjectProductMappingService` + `products.json` (`findCompatible`) |
| Restriction catalogue | fichiers visibles = hors `/catalogue` + fiches des familles autorisées | `ChatController.isCatalogueEntryAllowed` / `catalogueDocFamilies` |
| **Données de l'agent** | fiches + arbres de décision injectés d'office | `AgentFiles` (`agent.data`) + `DataRequestService.readEntry` |
| Prompt système | `generic.txt` (gabarit) + `principal.txt` ([agent_principal]) + prompt de l'agent ([agent]) | `AgentFiles.systemPromptFor` |
| Choix de l'offre finale | l'agent choisit parmi le menu en appliquant les règles des fiches | LLM (fiches + cascade + compatibleProducts) |
| Calcul mensualité | annuité constante si montant+durée+TAEG connus (sinon rien) | `CreditSimulationService` |
| Logs / historique | prompt, agent, debug `[AGENT]`, réponse, conversation | `AILogService`, `LogsController`, `ConversationController` |

---

## 5. Les agents (prompts) et leurs données

`agents.json` déclare chaque agent : `{id, libelle, theme, prompt, data[]}`.
- **Générique** (theme `generic`) : questions factuelles, budget, dépenses… `data` = derniers mois de transactions.
- **Spécialisés** : Crédit conso, Crédit immo, Épargne, Assurance auto / habitation / emprunteur → `data` = fiche `catalogue/*.json` + arbre `cascade/*.txt` du thème.

**Gabarit `generic.txt`** (toujours chargé) :
```
[contenu commun coach]

[agent_principal]   ← remplacé par principal.txt (agent principal)
[agent]             ← remplacé par le prompt de l'agent spécialisé concerné (vide si générique)
```

Les prompts/agents sont éditables dans la page **Agents** (`#/agents`) et relus à chaque appel (sauvegarde immédiate).

---

## 6. Les deux catalogues produits (pourquoi deux fichiers ?)

| Fichier | Usage | Consommé par |
|---|---|---|
| `products.json` | **Catalogue machine** : id/name/family/min-max montant/durées/TAEG → sert au **filtrage Java** (`compatibleProducts`) | `ProductCatalogueService`, chargé au **démarrage** (redémarrer le backend après modification) |
| `catalogue/*.json` (fiches) + `cascade/*.txt` | **Connaissances** : offres détaillées, tarifs, règles d'éligibilité → servent à l'**IA** (recommandation/pédagogie) | agent actif (`providedData`) + catalogue data.json |

Ce n'est **pas un doublon** : `products.json` = « ce qui est présentable » (Java décide), les fiches = « que recommander et pourquoi » (IA choisit dans le menu). Les deux partagent les mêmes `id` pour rester alignés.
