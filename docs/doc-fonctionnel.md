# Coach financier — Document fonctionnel

> POC « coach financier bancaire » — démonstrateur conversationnel
> Version document : 2026-09-09 (aligné sur les évolutions « agents par thème », « classification du projet + filtrage métier des produits » et « UI avancée / audio »)

---

## 1. Présentation générale

### 1.1 Le produit
Le POC est un **coach financier conversationnel** : le client discute en langage naturel de son budget, de ses projets (achat, travaux, crédit, épargne…) et reçoit des **recommandations pédagogiques** basées sur **des données bancaires de démonstration**.

Il repose sur deux IA :
- une **IA de compréhension** (classe la demande : périmètre, intention, type de projet, montant) ;
- une **IA coach** (analyse, compare, explique), déclinée en **agents par thème** : un agent générique par défaut, un « agent principal » et **6 agents spécialisés** (crédit conso, crédit immo, épargne, assurances auto / habitation / emprunteur). Chaque agent possède son propre prompt système et ses données dédiées (fiche produit + arbre de décision).

Et sur un **moteur métier Java déterministe** qui applique les règles de filtrage des produits — principe clé :

> **LLM = comprendre · JAVA = décider des règles métier et calculer · LLM = expliquer**

### 1.2 Objectif principal de l'évolution
Empêcher structurellement l'IA de recommander ou de mentionner un produit bancaire **incompatible avec le projet du client** (ex. crédit immobilier pour l'achat d'une voiture).

---

## 2. Fonctionnalités principales

| # | Fonctionnalité | Description |
|---|---|---|
| F1 | Chat coach | Dialogue libre en français, réponses pédagogiques |
| F2 | Compréhension du besoin | Détection intention + type de projet + montant + objet |
| F3 | Projet courant | Mémorisation du projet dans la conversation (« mon cas », « en 3 fois »…) |
| F4 | Filtrage métier produits | Seuls les produits compatibles avec le projet sont présentés |
| F5 | Séparation engagements / offres | Crédits existants (charges) ≠ produits proposés (solutions) |
| F6 | Clarification | Si le type de projet est inconnu / confiance faible, l'app demande une précision |
| F7 | Synthèse financière | Indicateurs agrégés (revenus, dépenses, épargne, crédits) |
| F8 | Logs des appels IA | Observabilité : statut, données envoyées, prompt, filtrage |
| F9 | Agents IA éditables | Page « Agents » : éditer les prompts (générique, agent principal, agents spécialisés) sans redémarrage |
| F10 | Modes de réponse | GPT / DeepSeek (LLM réel) ou « Mode démo » (Mock, sans clé) |
| F11 | Cascade produit | Règles de recommandation produit (« arbres de décision ») jointes au contexte |
| F12 | Sélection d'agent | Routage du message vers l'agent spécialisé du thème (produit, épargne, assurance…) |
| F13 | Réglages avancés | Interrupteur « Avancé » : fournisseur IA, audio, garde-fou hors-sujet, accès Logs/Agents |
| F14 | Audio | Micro 🎤 (dictée, Web Speech API) et lecture vocale 🔊 / synthèse des réponses |
| F15 | Rendu Markdown | Gras / italique / code des réponses IA affichés proprement |
| F16 | Fin de conversation | Clôture → **un seul email automatique : au conseiller**, avec le **brouillon d'email client en pièce jointe** (jamais envoyé au client) |
| F17 | Marketing Intelligence | Analyse des conversations (intérêts, refus, cross-sell, besoins non couverts) + rapport IA, sans base de données |
| F18 | Pop-in de satisfaction | À la fin d'une conversation : note 1 à 5, motifs si note ≤ 3, commentaire facultatif, bouton **Passer** — avis toujours facultatif |
| F19 | Qualité & Satisfaction du Coach | Indicateurs de satisfaction client **et** contrôles de conformité du Coach, croisement des deux, rapport IA quotidien |

### 2.1 Pages / écrans (frontend React, routage par hash, pas de react-router)

| Route | Page | Contenu |
|---|---|---|
| `#/` | **Chat coach** | Conversation + panneau « Vue d'ensemble » (solde, revenus, dépenses, crédits, taux). Interrupteur « Avancé » : fournisseur IA (GPT/DeepSeek/Mock), Audio, garde-fou hors-sujet, case **« Suivi conseiller »**, accès Logs / Agents / Marketing (nouveaux onglets). Bouton d'en-tête : « Terminer et envoyer au conseiller » (suivi activé) ou « Nouvelle conversation » (suivi désactivé) |
| `#/logs` | **Logs des appels IA** | Traces : statut, session, agent utilisé, message client, caractères, données envoyées/demandées, boutons « Voir le prompt », « Voir le filtrage », « Voir la réponse », « Historique » |
| `#/agents` | **Agents IA** | Édition des prompts par agent : générique (défaut), agent principal, agent de suivi, agent analyste marketing, 6 agents spécialisés. Injecté à chaque appel |
| `#/marketing` | **Marketing Intelligence** | KPI, top produits, projets, « recommandé vs intérêt », refus, cross-sell, besoins non couverts, infos manquantes, rapport IA du jour, export CSV |
| `#/quality` | **Qualité & Satisfaction** | Note moyenne, taux de participation, avis positifs/négatifs, distribution des notes, motifs d'insatisfaction, contrôles du Coach, croisement satisfaction × conformité, analyse IA, export CSV |

---

## 3. Parcours utilisateur — flux fonctionnels

### 3.1 Flux global du chat

```mermaid
flowchart TD
    A[Client écrit un message] --> B[IA de compréhension]
    B --> C{Périmètre ?}
    C -- Hors sujet --> Z[Refus pédagogique]
    C -- Dans le sujet --> D[Backend met à jour le projet courant]
    D --> E[Synthèse financière]
    E --> F{Besoin de produits ?<br/>financement / info produit}
    F -- Oui, projet connu --> G[Filtrage métier Java<br/>produits compatibles]
    F -- Non / projet inconnu --> H[Pas de produit]
    G --> I[Contexte compact<br/>currentProject + compatibleProducts + existingCredits]
    H --> I
    I --> J[IA coach génère la réponse]
    J --> K{Réponse finale ?}
    K -- Besoin d'un fichier précis --> L[Le backend fournit le fichier autorisé]
    L --> J
    K -- Réponse client --> M[Message affiché au client]
```

### 3.2 Scénario « achat d'une voiture »

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant B as Backend (Java)
    participant I1 as IA compréhension
    participant F as Filtre métier (Java)
    participant I2 as IA coach

    C->>B: « Je veux acheter une Clio 5 à 8 700 € »
    B->>I1: classifyIntent (message)
    I1-->>B: PURCHASE / VEHICLE / Clio 5 / 8 700 / confidence HIGH
    B->>B: currentProject = VEHICLE / Clio 5 / 8 700 €
    B->>I2: contexte (projet + synthèse)
    I2-->>B: réponse capacité financière

    C->>B: « Quel crédit pour mon cas ? »
    B->>I1: classifyIntent (message + CURRENT_PROJECT)
    I1-->>B: FINANCING_REQUEST / VEHICLE / refersToCurrentProject=true
    B->>F: familles autorisées VEHICLE
    F-->>B: AUTO_LOAN, PERSONAL_LOAN, CONSUMER_CREDIT
    B->>F: filtrage catalogue produits (montant 8 700)
    F-->>B: Prêt Auto, Crédit Auto Expresso, Prêt Personnel
    Note over B: Crédit immobilier & Crédit travaux<br/>masqués du catalogue
    B->>I2: compatibleProducts + existingCredits (immobilier 956 €/mois)
    I2-->>B: recommande seulement Prêt Auto / Prêt Personnel
```

**Résultat attendu** : le crédit immobilier **n'apparaît jamais** dans la réponse.

### 3.3 Cas « projet inconnu » (clarification)

```mermaid
flowchart LR
    A[« J'ai besoin de 20 000 € pour ma maison »] --> B[IA de compréhension]
    B --> C{type fiable ?}
    C -- Ambigu --> D[projectType = UNKNOWN / confiance LOW]
    D --> E[L'app demande une précision]
    E --> F[« Pour acheter une voiture »]
    F --> G[VEHICLE -> filtrage produits]
```

### 3.4 Page Logs (observabilité)

```mermaid
flowchart LR
    A[Appel IA] --> B[Backend journalise une trace]
    B --> C[Polling 2 s]
    C --> D[Logs #id]
    D --> E[Bouton Voir le prompt]
    D --> F[Bouton Voir le filtrage]
    D --> G[Bouton Voir la réponse]
    D --> H[Bouton Historique]
```

Pour chaque trace, la page affiche : statut (`ANSWER`/`NEED_DATA`), session, message client, **agent utilisé**, **caractères envoyés**, données envoyées (pastilles), données demandées par l'IA (pastilles), et plusieurs vues dépliables :
- **« Voir le prompt »** : prompt système + payload utilisateur, **sans le contenu des données jointes** ;
- **« Voir le filtrage »** : bloc `[INTENT]` / `[PRODUCT_FILTER]` / `[COACH]` ;
- **« Voir la réponse »** : réponse brute renvoyée par l'IA pour cette trace ;
- **« Historique »** : historique complet de la conversation (messages client/coach).

### 3.5 Sélection de l'agent par thème

```mermaid
flowchart TD
    A[Message + projet courant] --> B{Intention routable ?<br/>FINANCING / PRODUCT_INFO / CREDIT_INFO}
    B -- Non --> G[Agent générique]
    B -- Oui --> C{Thème explicite<br/>dans le message ?}
    C -- assurance --> D[Agent assurance<br/>auto / habitation / emprunteur]
    C -- épargne / livret / PEA --> E[Agent épargne]
    C -- crédit immo --> F[Agent crédit immobilier]
    C -- crédit conso --> H[Agent crédit conso]
    C -- aucun --> I{Type de projet}
    I -- VEHICLE / conso / travaux --> H
    I -- immobilier --> F
    I -- épargne / placement --> E
    I -- assurance --> D
    I -- autre --> G
```

Principes :
- **Agent générique par défaut** pour les questions factuelles (budget, dépenses, synthèse) tant que le besoin n'est pas rattaché à un produit ;
- dès que la demande est **routable** (financement, information produit/crédit) et **rattachée à un thème**, on bascule vers l'agent spécialisé du thème ;
- le **sous-thème assurance** (auto / habitation / emprunteur) est déduit du message + du projet courant ;
- le prompt système de l'agent actif = **gabarit générique** (`generic.txt`) + contenu de l'**agent principal** (balise `[agent_principal]`) + prompt de l'agent spécialisé (balise `[agent]`).

### 3.6 Fin de conversation — dossier de suivi

Principe : **« l'IA prépare → le conseiller contrôle → le conseiller décide → le conseiller envoie »**.

```mermaid
flowchart TD
    A[Bouton du chat<br/>« Terminer et envoyer au conseiller »] --> B{Suivi activé ?}
    B -- Non --> Z[Bouton = « Nouvelle conversation »<br/>simple vidage du chat]
    B -- Oui --> C{≥ 2 échanges client ?}
    C -- Non --> Z
    C -- Oui --> D[POST /api/conversations/{id}/close<br/>fire-and-forget]
    D --> E[Agent de suivi suivi.txt<br/>synthèse + produits + brouillon client]
    E --> F[Validation backend<br/>produits/URLs réels, refus retirés]
    F --> G[Email au CONSEILLER<br/>+ brouillon client en pièce jointe]
    F --> H[Événements marketing<br/>persistés en JSONL]
```

- **Un seul email automatique** : au conseiller. Le brouillon destiné au client est **joint** (`.eml`/`.html`/`.txt`), jamais envoyé.
- Déclenchement **sans attente** (l'IHM n'affiche ni chargement ni bannière de résultat) et **une seule fois par session**.
- Cas particuliers : suivi désactivé, moins de 2 échanges, ou session inconnue (backend redémarré) → **aucun envoi** ; l'échec d'envoi est tracé dans l'écran **Logs** (bloc `[SUIVI]` : `mailStatus`, `mailSent`, `mailTarget`, `mailError`).

### 3.7 Page Marketing Intelligence (`#/marketing`)

Objectif : comprendre, à partir des **conversations**, ce que les clients cherchent, ce qui bloque et ce qui pourrait être proposé — sans base de données et **sans laisser l'IA calculer les chiffres**.

- Le **code** calcule toutes les statistiques et tous les scores ; l'**IA** (agent `agent/marketing.txt`) ne fait qu'**interpréter** les agrégats déjà calculés.
- Ce qui est mesuré : intérêt par produit, produits « recommandés mais non intéressants » vs « intéressants », projets, refus (motif), associations de produits (cross-sell), besoins non couverts, informations manquantes, demandes de RDV.
- **Aucune donnée personnelle** : l'identifiant client est pseudonymisé (hash), les emails/téléphones présents dans les motifs sont masqués.
- Filtres proposés : période (aujourd'hui, hier, 7 j, 30 j, personnalisée), produit, famille, projet, niveau d'intérêt.
- Boutons d'administration : export **CSV**, régénération du **rapport IA** du jour, **données de démonstration** (30 jours d'événements synthétiques marqués `demo=true`).

---

### 3.8 Pop-in de satisfaction et page Qualité (`#/quality`)

Deux notions **à ne jamais confondre** :

| | Question posée | Origine |
|---|---|---|
| **Satisfaction client** | « Le client a-t-il apprécié son expérience ? » | Note 1 à 5, motifs, commentaire |
| **Qualité / conformité du Coach** | « Le Coach a-t-il correctement répondu et respecté les règles ? » | Contrôles automatiques |

> Règle absolue : **une mauvaise note n'est jamais convertie automatiquement en anomalie du Coach.**
> Exemple : un client peut être mécontent parce que le Coach refuse de calculer une mensualité — comportement pourtant **conforme** (redirection vers le simulateur officiel).

```mermaid
flowchart TD
    A[Client clique<br/>« Terminer et envoyer au conseiller »] --> B{Suivi actif<br/>et ≥ 2 échanges ?}
    B -- Non --> Z[Clôture / nouvelle conversation<br/>sans question]
    B -- Oui --> C[Pop-in : comment s'est passée<br/>votre conversation avec le Coach ?]
    C --> D{Note + motifs + commentaire}
    D --> E[« Envoyer mon avis »]
    D --> F[« Passer »]
    E --> G[Enregistrement anonymisé<br/>JSONL - jamais bloquant]
    F --> H[Clôture]
    G --> H[Clôture de conversation<br/>dossier conseiller]
    H --> I[Contrôles automatiques du Coach]
    I --> J[Agrégations du jour - batch]
    J --> K[Agent IA Qualité<br/>agent/qualite_coach_client.txt]
    K --> L[Rapport qualité quotidien]
    L --> M[Page Qualité - #/quality]
```

- **Pop-in** : 5 étoiles, motifs proposés uniquement à partir de 3 étoiles ou moins, commentaire toujours facultatif, « Passer » clôture normalement. Un échec d'enregistrement n'empêche **jamais** la clôture.
- **Anonymat** : identifiant client pseudonymisé, commentaire nettoyé (emails/téléphones masqués), aucun libellé bancaire.
- **Page Qualité** : filtres de période (aujourd'hui, hier, 7 j, 30 j, personnalisée), note, sévérité ; indicateurs de satisfaction et de conformité **séparés** ; croisement A/B/C/D (B = anomalie invisible pour le client, C = règle correctement appliquée mais frustrante, D = cas prioritaire) ; analyse IA ; export CSV des agrégats (jamais des commentaires bruts).
- **Contrôles affichés** = uniquement ceux **réellement implémentés** ; les autres sont listés à part et ne sont jamais comptés comme un « 0 ».

---

## 4. Règles de filtrage métier (règles produit)

### 4.1 Mapping type de projet → familles de produits autorisées

| Type de projet | Familles autorisées |
|---|---|
| `VEHICLE` | AUTO_LOAN, PERSONAL_LOAN, CONSUMER_CREDIT, YOUNG_ACTIVE_LOAN, REVOLVING_CREDIT |
| `HOME_WORK` | HOME_IMPROVEMENT_LOAN, PERSONAL_LOAN, CONSUMER_CREDIT, YOUNG_ACTIVE_LOAN, REVOLVING_CREDIT |
| `ELECTRONICS` / `FURNITURE` | INSTALLMENT_PAYMENT, PERSONAL_LOAN, CONSUMER_CREDIT, YOUNG_ACTIVE_LOAN, REVOLVING_CREDIT |
| `TRAVEL` / `WEDDING` / `HEALTH_EXPENSE` | PERSONAL_LOAN, CONSUMER_CREDIT, REVOLVING_CREDIT |
| `EDUCATION` | PERSONAL_LOAN, CONSUMER_CREDIT, STUDENT_LOAN, DRIVER_LICENSE_LOAN, YOUNG_ACTIVE_LOAN, REVOLVING_CREDIT |
| `CASH_NEED` | PERSONAL_LOAN, CONSUMER_CREDIT, REVOLVING_CREDIT, YOUNG_ACTIVE_LOAN |
| `DEBT_RESTRUCTURING` | PERSONAL_LOAN, CONSUMER_CREDIT, DEBT_CONSOLIDATION |
| `REAL_ESTATE_PURCHASE` | MORTGAGE, HOME_SAVINGS |
| `SAVINGS` | SAVINGS_PRODUCT, TERM_DEPOSIT, HOME_SAVINGS, LIFE_INSURANCE |
| `INVESTMENT` | SAVINGS_PRODUCT, TERM_DEPOSIT, LIFE_INSURANCE, RETIREMENT_SAVINGS, EQUITY_INVESTMENT |
| `INSURANCE` | INSURANCE_AUTO, INSURANCE_HOME, INSURANCE_BORROWER |
| `BUDGET` / `OTHER_FINANCIAL` / `UNKNOWN` | *(aucun produit automatique)* |

### 4.2 Restriction du catalogue visible (contexte financement)
Quand le message relève d'un **besoin de financement** et que le projet est connu :
- les fichiers **hors `/data/catalogue/`** restent visibles (transactions, synthèse) ;
- parmi les fichiers `/data/catalogue/*.json`, seuls ceux dont les familles documentées croisent les familles autorisées sont visibles.

Exemple concret (projet VEHICLE) : `credit_conso.json` est **visible** ; `credit_immo.json`, `epargne.json`, `assurance_*.json` sont **cachés** — à la fois dans le catalogue présenté à l'IA **et** dans la whitelist de récupération (l'IA ne peut pas les obtenir, même en les demandant).

### 4.3 Produits vs engagements existants
- `existingCredits` : crédits **déjà souscrits** (ex. crédit immobilier 956 €/mois) → utilisés pour l'endettement/le reste à vivre, **jamais** proposés comme solution.
- `compatibleProducts` : produits **compatibles** avec le projet → seuls ceux-ci peuvent être recommandés.

---

## 5. Synthèse financière affichée

Panneau « Vue d'ensemble » :
- **Solde du compte courant** (ex. 1 516,89 €) ;
- **Épargne** totale (Livret A + LDDS, ex. 14 695,18 €) ;
- **Revenu mensuel moyen**, **dépenses mensuelles**, **crédits/mois** ;
- **Santé financière** : taux d'endettement, **taux d'épargne sur les 3 derniers mois complets** (avec libellé, ex. « 3 derniers mois · juin – août 2026 ») ;
- **Contexte** : période analysée, nombre de transactions.

Règles de calcul importantes :
- les **virements internes d'épargne** (vers épargne / retours depuis épargne) sont **exclus** des revenus/dépenses de consommation ;
- le taux d'épargne affiché = Σ épargne / Σ revenus sur les **3 derniers mois entiers** (le mois civil courant partiel est ignoré).

---

## 6. Données de démonstration

Le jeu de données simule **14 mois (07/2025 → 08/2026)** d'un compte courant avec :
- revenus (salaire…) ;
- dépenses de consommation ;
- crédit immobilier existant (956 €/mois, fin 2035) ;
- comptes d'épargne (Livret A, LDDS) ;
- un catalogue de produits et de règles (« cascade »).

Toutes les données sont **fictives** et servent uniquement la démonstration.

**Données de démonstration Marketing** : la page `#/marketing` propose un bouton **« Données de démo »** qui génère un historique synthétique (période et volume réglables, valeurs reproductibles à l'identique). Ces événements sont marqués `demo=true` — le bandeau de la page et le champ `demo` des agrégats permettent de ne pas les confondre avec de vraies conversations. Pour repartir d'une base propre, supprimer le répertoire `data/marketing`.

---

## 7. Critères d'acceptation (rappel)

1. Le premier appel IA retourne un type de projet structuré ;
2. Le projet courant est conservé dans la session ;
3. Les références (« mon cas », « en trois fois », « celui-ci ») utilisent le projet courant ;
4. Le backend possède un mapping `ProjectType → ProductFamily` ;
5. Le catalogue est filtré côté Java ;
6. Seuls les produits compatibles sont envoyés au coach ;
7. Les crédits existants sont séparés des produits proposés ;
8. Un crédit immobilier existant influence l'analyse mais n'est jamais proposé pour une voiture ;
9. `UNKNOWN` / confiance faible → demande de précision ;
10. Aucune mensualité/taux inventé ;
11. Les calculs déterministes sont côté Java ;
12. Les produits ne sont chargés que si l'intention le nécessite ;
13. Le contexte envoyé au LLM reste compact ;
14. Scénarios voiture / travaux / immobilier / ambigu passent.

### 7.1 Suivi de fin de conversation

15. À la clôture, **un seul** email est envoyé : au **conseiller** ;
16. Le brouillon destiné au client est **en pièce jointe** et n'est jamais envoyé par le système ;
17. Aucun produit refusé, aucune URL inventée ne figure dans le dossier ni dans le brouillon ;
18. Suivi désactivé, moins de 2 échanges ou session inconnue → **aucun envoi** (sans erreur visible pour le client) ;
19. L'issue de l'envoi (envoyé / non envoyé + origine de l'erreur) est lisible dans l'écran **Logs**.

### 7.2 Module Marketing

20. Toutes les statistiques sont calculées par le backend, jamais par l'IA ;
21. Aucune base de données : le stockage est fait de fichiers (JSONL + JSON) ;
22. Aucune donnée personnelle sur disque (identifiant client pseudonymisé, emails/téléphones masqués) ;
23. La page `#/marketing` restitue KPI, top produits, projets, « recommandé vs intérêt », refus, cross-sell, besoins non couverts, informations manquantes et rapport IA ;
24. Les filtres de période (aujourd'hui / hier / 7 j / 30 j / personnalisée) et les filtres produit/projet recalculent l'ensemble de la page ;
25. Un rapport IA par jour est généré à partir des agrégats déjà calculés ;
26. Le batch quotidien est **réexécutable** sans créer de doublons (événements comme fichiers).

### 7.3 Qualité & Satisfaction

27. L'avis de fin de conversation est **facultatif** : « Passer » clôture normalement ;
28. Un double clic, un retry ou un refresh ne créent **qu'un seul** avis par conversation ;
29. Un échec d'enregistrement de l'avis n'empêche jamais la clôture de la conversation ;
30. Une **mauvaise note ne crée jamais** d'anomalie de qualité : les deux dimensions restent séparées ;
31. Un refus de simulation de crédit suivi d'une redirection vers le simulateur officiel est **conforme** ;
32. Un chiffrage de crédit réellement produit par le Coach est signalé (sévérité HIGH) ;
33. La page Qualité n'affiche que les contrôles **réellement exécutés** (aucun faux « 0 ») ;
34. Aucun avis ne contient de donnée personnelle (identifiant pseudonymisé, commentaire nettoyé).

---

## 8. Limites connues (POC)
- Fournisseur par défaut **côté backend** : Mode démo (MOCK) — classification enrichie réelle uniquement avec GPT/DeepSeek configuré ; l'écran choisit DeepSeek par défaut ;
- Clés API **externalisées** via variables d'environnement (`OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, …) — aucune clé en dur ;
- Logs et conversations **en mémoire** (perdus au redémarrage) → une clôture après redémarrage ne produit aucun dossier (`NO_CONVERSATION`) ;
- Un seul « projet courant » géré (le remplacement est accepté pour le POC) ;
- Marketing : les événements ne sont produits qu'à la **clôture** d'une conversation ; les jeux de démonstration (`demo=true`) et les données réelles cohabitent dans `data/marketing` (le bandeau de la page le signale) ;
- Marketing : pas de ventilation par agence/segment ni d'export Excel — export **CSV** uniquement.
- Qualité : seuls **6 contrôles automatiques** sont implémentés (simulation de crédit, produit incompatible, URL inventée, demande non résolue, donnée non récupérée, répétition excessive) ; les contrôles `UNNECESSARY_ADVISOR_REDIRECT`, `UNSUPPORTED_PRODUCT_CLAIM`, `INVENTED_DATA` et `CONVERSATION_CONTEXT_LOST` sont décrits mais **non implémentés** et affichés comme tels ;
- Qualité : l'analyse IA **des commentaires** (classification automatique) n'est pas activée — les thèmes affichés proviennent d'une heuristique locale déterministe ;
- Qualité : le taux de participation repose sur les conversations pour lesquelles des contrôles ont été exécutés (une conversation non clôturée n'est pas comptée) ;
- Qualité : en dessous du seuil d'échantillon (`app.quality.sufficient-sample-size`), le rapport IA reste prudent et le signale.
