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
| F10 | Modes de réponse | GPT / DeepSeek (LLM distant) · **Local (LM Studio…)** — modèle servi sur la machine, sans clé API — ou « Mode démo » (Mock, sans réseau) |
| F11 | Cascade produit | Règles de recommandation produit (« arbres de décision ») jointes au contexte |
| F12 | Sélection d'agent | Routage du message vers l'agent spécialisé du thème (produit, épargne, assurance…) |
| F13 | Réglages avancés | Interrupteur « Avancé » : fournisseur IA, audio, garde-fou hors-sujet, accès Logs/Agents |
| F14 | Audio | Micro 🎤 (dictée, Web Speech API) et lecture vocale 🔊 / synthèse des réponses. **Mode mains libres** : en veille, on dit le mot-clé (« Chloé » par défaut) puis sa question ; un **carillon** (façon Siri) signale que le mot-clé est reconnu et que l'écoute commence, et un **décompte visible** (5, 4, 3…) indique les secondes restantes avant l'envoi automatique de la question — il repart à chaque parole, donc seul le **silence** fait partir la question |
| F15 | Rendu Markdown | Gras / italique / code des réponses IA affichés proprement |
| F16 | Fin de conversation | Clôture → **un seul email automatique : au conseiller**, avec le **brouillon d'email client en pièce jointe** (jamais envoyé au client) |
| F17 | Marketing Intelligence | Analyse des conversations (intérêts, refus, cross-sell, besoins non couverts) + rapport IA, sans base de données |
| F18 | Pop-in de satisfaction | À la fin d'une conversation : note 1 à 5, motifs si note ≤ 3, commentaire facultatif, bouton **Passer** — avis toujours facultatif |
| F19 | Qualité & Satisfaction du Coach | Indicateurs de satisfaction client **et** contrôles de conformité du Coach, croisement des deux, rapport IA quotidien |
| F20 | Feedback Conseiller | Le conseiller évalue en quelques secondes la pertinence du travail du Coach (résumé, besoin, produits, intérêts, suivi, email) ; KPI, pertinence produit, qualité des emails, analyse IA |
| F21 | Atelier d'optimisation des prompts | Boucle contrôlée **Agent A (éditeur) / Agent B (contrôleur)** qui améliore **une seule zone** du prompt d'un agent métier, sur une question **figée** : snapshot de référence, avis humain prioritaire, STOP/reprise, historique complet et **promotion en production par un humain uniquement** ; les cycles **s'enchaînent dans une conversation** (la réponse de chaque version promue devient la mémoire du cycle suivant, que l'Agent B **lit sans la juger**) ; un **Agent C (client simulé)** peut mener la conversation à la place de l'humain, avec le brief du client, **trois chiffres** seulement, une **profondeur** limitée et **STOP / CONTINUER** ; à la fin de la conversation, un **bilan** compare le **prompt initial** et le **prompt final** |

### 2.1 Pages / écrans (frontend React, routage par hash, pas de react-router)

| Route | Page | Contenu |
|---|---|---|
| `#/` | **Chat coach** | Conversation + panneau « Vue d'ensemble » (solde, revenus, dépenses, crédits, taux). Interrupteur « Avancé » : fournisseur IA (**GPT / DeepSeek / Local (LM Studio) / Mock**), Audio, garde-fou hors-sujet, case **« Suivi conseiller »** (**cochée par défaut**, un décochage volontaire est mémorisé), accès Logs / Agents / Marketing (nouveaux onglets). Bouton d'en-tête : « Terminer la conversation » (suivi activé) ou « Nouvelle conversation » (suivi désactivé) |
| `#/logs` | **Logs des appels IA** | Traces : statut, session, agent utilisé, message client, caractères, données envoyées/demandées, boutons « Voir le prompt », « Voir le filtrage », « Voir la réponse », « Historique » |
| `#/agents` | **Agents IA** | Édition des prompts par agent : générique (défaut), agent principal, agent de suivi, agent analyste marketing, 6 agents spécialisés. Injecté à chaque appel |
| `#/marketing` | **Marketing Intelligence** | KPI, top produits, projets, « recommandé vs intérêt », refus, cross-sell, besoins non couverts, infos manquantes, rapport IA du jour, export CSV |
| `#/quality` | **Qualité & Satisfaction** | Note moyenne, taux de participation, avis positifs/négatifs, distribution des notes, motifs d'insatisfaction, contrôles du Coach, croisement satisfaction × conformité, analyse IA, export CSV |
| `#/advisor-feedback` | **Feedback Conseillers** | Saisie rapide d'un avis conseiller par dossier, KPI de pertinence, zones corrigées, pertinence produit, corrections d'intérêt, qualité des emails préparés, analyse IA, export CSV |
| `#/advisor-feedback/session/<sessionId>` | **Évaluation d'un dossier** | Vue ciblée ouverte par le **lien du mail conseiller** : projet, synthèse du Coach, produits et niveaux d'intérêt, suivi conseillé, email préparé, puis formulaire d'évaluation |
| `#/conversation/<sessionId>` | **Historique d'une conversation** | Vue ciblée **en lecture seule** donnant la synthèse et la relecture des échanges client ↔ Coach, avec accès direct à l'évaluation du dossier. Le mail conseiller ne la lie plus (l'historique se relit dans la pop-in du Centre d'appels) : la page reste disponible pour un accès direct par URL. L'URL ne contient que le `sessionId` |
| `#/centre-appels` | **Centre d'appels** | Annuaire des conversations clôturées pour l'équipe commerciale : tableau filtrable (période 5/10/30 jours, catégorie, **statut**, recherche) et triable (client, catégorie, titre, **score de sens commercial**, date), avec la pop-in de détail (score expliqué, **statut d'avancement modifiable + historique**, prochaines actions, offres d'intérêt, **synthèse envoyée au conseiller**, **pièce jointe — email client préparé** et **conversation complète** dans des blocs repliables) |
| `#/centre-appels/<sessionId>` | **Dossier d'un client** | Même page, ouverte **directement sur la pop-in du dossier** : c'est la cible du lien « Ouvrir le dossier du client » du mail conseiller. L'URL ne contient que le `sessionId` |
| `#/prompt-lab` | **Atelier d'optimisation des prompts** | Choix de l'agent (zone optimisée **figée** au prompt de l'agent spécialisé), question de test, nombre d'itérations, fournisseur IA, puis : progression, arrêt/reprise, avis humain, comparaison des versions, diff de la zone, promotion explicite en production |

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
    A[Bouton du chat<br/>« Terminer la conversation »] --> B{Suivi activé ?}
    B -- Non --> Z[Bouton = « Nouvelle conversation »<br/>simple vidage du chat]
    B -- Oui --> C{≥ 2 échanges client ?}
    C -- Non --> Z
    C -- Oui --> D[POST /api/conversations/{id}/close<br/>fire-and-forget]
    D --> E[Agent de suivi suivi.txt<br/>synthèse + produits + brouillon client]
    E --> F[Validation backend<br/>produits/URLs réels, refus retirés]
    F --> G[Email au CONSEILLER<br/>+ brouillon client en pièce jointe]
    F --> H[Événements marketing<br/>persistés en JSONL]
```

- le **suivi conseillé** = **un seul email automatique**, au **conseiller** ;
- ce mail contient un **lien direct « Évaluer le suivi du Coach »** vers le dossier de la conversation : le conseiller passe du mail à l'écran d'évaluation en un clic (§41/§42) ;
- il contient un **lien « Ouvrir le dossier du client »** qui ouvre **directement la pop-in du dossier** dans
  la page **`#/centre-appels/<sessionId>`** (score de sens commercial, suivi, synthèse conseiller et
  conversation complète) : le conseiller passe du mail au dossier en un clic. L'URL ne contient que le
  `sessionId` ; le lien vers l'historique de la conversation a été retiré du mail (la page `#/conversation/<id>`
  reste disponible et la conversation se relit depuis la pop-in, bloc **« Conversation complète »** pilable, comme
  la synthèse conseiller) ;
- il commence par le **score de sens commercial** (0 à 100 + libellé de priorité) avec son **explication courte** (« Pourquoi ce score ») et un **lien d'appel du client** (`tel:`, numéro de démonstration `0644910925`) : le conseiller sait **par quoi commencer**. Le score n'est **jamais** montré au client ;
- la **conversation est archivée** avec son dossier (client, catégorie, titre, score, transcript) : elle reste consultable dans la page **`#/centre-appels`** même après un redémarrage du backend ;
- le brouillon destiné au client est **joint** (`.eml`/`.html`/`.txt`), jamais envoyé et ne contient **jamais** ces liens internes.
- Déclenchement **sans attente** (l'IHM n'affiche ni chargement ni bannière de résultat) et **une seule fois par session**.
- Cas particuliers : suivi désactivé, moins de 2 échanges, ou session inconnue (backend redémarré) → **aucun envoi** ; l'échec d'envoi est tracé dans l'écran **Logs** (bloc `[SUIVI]` : `mailStatus`, `mailSent`, `mailTarget`, `mailError`).

### 3.7 Page Marketing Intelligence (`#/marketing`)

Objectif : comprendre, à partir des **conversations**, ce que les clients cherchent, ce qui bloque et ce qui pourrait être proposé — sans base de données et **sans laisser l'IA calculer les chiffres**.

- Le **code** calcule toutes les statistiques et tous les scores ; l'**IA** (agent `agent/marketing.txt`) ne fait qu'**interpréter** les agrégats déjà calculés.
- Ce qui est mesuré : intérêt par produit, produits « recommandés mais non intéressants » vs « intéressants », projets, refus (motif), associations de produits (cross-sell), besoins non couverts, informations manquantes, demandes de RDV.
- **Aucune donnée personnelle** : l'identifiant client est pseudonymisé (hash), les emails/téléphones présents dans les motifs sont masqués.
- Filtres proposés : période (aujourd'hui, hier, 7 j, 30 j, personnalisée), produit, famille, projet, niveau d'intérêt.
- **Lisibilité** : les blocs « Projets clients », « Pourquoi les clients refusent-ils ? », « Besoins non couverts » et « Questions sans réponse dans le catalogue » affichent des **libellés métier en français** (« Projet immobilier — Aucune offre adaptée au besoin », « Durée inadaptée »), jamais les codes techniques (`REAL_ESTATE`, `NO_SUITABLE_PRODUCT`) qui restent visibles uniquement en infobulle. Les niveaux d'intérêt sont affichés « Intérêt élevé / moyen / faible ». Un produit non identifiable est affiché « Produit non identifié ».
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
    A[Client clique<br/>« Terminer la conversation »] --> B{Suivi actif<br/>et ≥ 2 échanges ?}
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

### 3.9 Feedback Conseiller (`#/advisor-feedback`)

Troisième point de vue sur le Coach, **indépendant** des deux autres :

| Module | Question posée | Qui répond |
|---|---|---|
| Qualité | « Le client est-il satisfait ? Le Coach respecte-t-il les règles ? » | client + contrôles automatiques |
| **Feedback Conseiller** | « Le travail produit est-il **pertinent** du point de vue professionnel du conseiller ? » | conseiller bancaire |

- **Saisie en quelques secondes** : l'évaluation globale suffit (👍 Pertinente / ⚠ À améliorer / 👎 Incorrecte). Les détails (zones à améliorer, motifs, produits, niveaux d'intérêt, suivi, email) ne s'ouvrent que pour un avis négatif et restent facultatifs.
- **Produits** : le conseiller marque un produit « pertinent / non pertinent », peut **corriger le niveau d'intérêt** (la valeur IA **et** la valeur du conseiller sont conservées) et signaler un **produit oublié** choisi uniquement dans le catalogue réel.
- **Email client** : exploitabilité évaluée (prêt à l'emploi, modifications mineures, importantes, non utilisable) — c'est la mesure concrète du gain apporté au conseiller.
- **Boucle d'amélioration** : KPI, tendances, pertinence par produit, qualité des emails, puis analyse IA (« Générer l'analyse IA ») qui **propose** des pistes — **aucune** modification automatique du prompt, des règles, des seuils, du catalogue ou du code.
- **Robustesse** : un échec d'enregistrement ne casse jamais le dossier conseiller ; un double clic ne crée pas de doublon ; une révision crée une **version suivante** (historique conservé).
- **Parcours depuis le mail** (§41 à §47) :

```mermaid
flowchart TD
    MAIL[Mail de suivi reçu par le conseiller] --> LINK[Bouton<br/>« Évaluer le suivi du Coach »]
    LINK --> DOSSIER[Page #/advisor-feedback/session/&lt;sessionId&gt;<br/>dossier déjà chargé]
    DOSSIER --> FORM[Note globale puis détails<br/>facultatifs]
    FORM --> MERCI[« Merci, votre retour<br/>a bien été enregistré. »]
    MERCI --> RETOUR[Retour au tableau de suivi]
```

  - l'URL ne contient **que** le `sessionId` : jamais de nom, email, compte, montant ni commentaire (§45) ;
  - le **backend** vérifie l'existence du dossier (§46) : session inconnue → « Ce dossier n'est plus disponible. » (sans erreur technique) ;
  - un dossier déjà évalué affiche le feedback existant et permet sa **révision** (nouvelle version).

### 3.10 Centre d'appels (`#/centre-appels`)

Objectif : donner à l'équipe commerciale / au centre d'appels **une file de travail priorisée** plutôt qu'une
liste de conversations à parcourir.

- **Source unique** : les dossiers de suivi écrits à chaque clôture — donc exactement ce qui a été envoyé au
  conseiller, **sa pièce jointe comprise** (le brouillon d'email préparé pour le client). Aucune donnée n'est
  recalculée pour l'affichage.
- **Score de sens commercial** : attribué à la clôture (proposé par l'IA de synthèse, borné et complété par le
  code) à partir de critères explicites : **maturité du projet**, **urgence exprimée par le client**,
  **intérêt réel** (demandes de précision, comparaison, refus), **capacité de financement** d'après les
  indicateurs disponibles, **engagement** dans l'échange. Il donne une priorité (très haute / haute / moyenne /
  faible) et **2 à 3 raisons courtes** qui l'expliquent.
- **Tableau** : client, catégorie, titre de la conversation, score (badge + libellé), **statut d'avancement**,
  date de clôture, offres concernées et bouton « Voir le détail ».
- **Statut d'avancement du dossier** (fil de travail du centre d'appels) : `Nouveau` à la clôture, puis
  `Contacté`, `Qualifié`, `RDV planifié`, `Conclu`, `Sans suite`, `Clôturé`. Il est **saisi dans la pop-in**
  (liste déroulante des statuts possibles) et **chaque changement est conservé**. Un code inconnu est refusé et
  un statut inchangé n'écrit rien. Aucun statut n'est **déduit** d'une supposition : c'est le conseiller qui
  fait avancer le dossier.
- **Message laissé sur le dossier** : un champ libre (compte rendu d'appel, objection du client, prochaine
  action…) peut être enregistré **seul** (le statut ne bouge pas) ou **avec** un changement de statut. Chaque
  message entre dans le **journal de suivi** affiché dans la pop-in (date, statut inchangé ou
  `ancien → nouveau`, message) et le tableau marque d'un **compteur** les dossiers qui portent des messages.
- **Filtres** : période (**5 / 10 / 30 derniers jours** ou tout l'historique), **catégorie** (crédit conso,
  crédit immobilier, épargne, assurance, autre), **statut**, **recherche libre** (client, titre, projet,
  produit).
- **Tri** en cliquant sur un en-tête de colonne (client, catégorie, titre, score, date) — croissant/décroissant.
- **Pop-in de détail** : score + raisons + critères mesurés (repliables), **prochaines actions de suivi**,
  offres d'intérêt, **synthèse envoyée au conseiller**, **pièce jointe — email client préparé** (le brouillon
  transmis au conseiller, avec son objet et ses liens client) et **conversation complète** — chacune dans un
  **bloc repliable** (tous **repliés par défaut**) afin de garder la fiche lisible. Boutons : **appeler le
  client** (lien `tel:`) et ouvrir le dossier d'évaluation.
- **Aucune donnée inventée** : un dossier ancien (écrit avant l'apparition du score ou de l'identité client)
  reste affiché avec des valeurs vides.

### 3.11 Atelier d'amélioration itérative des prompts (`#/prompt-lab`)

Améliorer un prompt « à la main » ne prouve rien : on ne sait pas **ce qui** a été amélioré, ni si le gain vient du prompt ou d'une autre conversation. L'atelier transforme cette intuition en **expérience reproductible** : _une question figée, un contexte figé, une seule zone de prompt modifiable, et deux agents IA qui débattent_.

| Rôle | Qui | Ce qu'il fait |
|---|---|---|
| **Agent A — éditeur** | `agent/prompt_editor.txt` | Réécrit **uniquement** la zone éditable, en tenant compte de l'avis humain, du diagnostic de l'Agent B et des parties protégées |
| **Agent B — contrôleur** | `agent/prompt_controller.txt` | Analyse la réponse du Coach **sans rien réécrire** : points satisfaisants, points à améliorer (type, sévérité, **origine** : prompt / données / règle backend / variabilité du modèle), comportements à préserver, recommandation |
| **Humain** | IHM | Décide : avis prioritaire, arrêt, reprise, et surtout **promotion** d'une version en production |

Si le Coach réclame des données (`NEED_DATA` — ce n'est **pas** une réponse client), l'atelier fait **comme en production** : les fichiers autorisés du catalogue lui sont fournis, le **contexte de référence est enrichi** (et tracé sur l'itération), puis le Coach produit la réponse destinée au client — **c'est seulement à ce moment qu'Agent B intervient**. Si ces données **ne peuvent pas** être fournies, l'itération n'est **pas bloquée** : elle est **dégradée comme en production** (réponse de repli enregistrée, motif affiché), et la campagne continue — c'est une information utile (« le prompt réclame des données que le contexte ne contient pas »), jamais un point de blocage de la conversation.

```mermaid
flowchart TD
    Q[Question de test figée] --> SNAP[Snapshot de référence<br/>données + classification + prompt hors zone + HISTORIQUE : FIGÉS]
    SNAP --> COACH[Le Coach répond avec la version courante du prompt]
    COACH --> B[Agent B : diagnostic du DERNIER échange<br/>aucune réécriture]
    B --> A[Agent A : nouvelle zone éditable<br/>+ avis humain prioritaire si présent]
    A --> VAL{Validation BACKEND<br/>marqueurs, longueur, non vide}
    VAL -- refusée --> KEEP[La version est CONSERVÉE<br/>l'échec est enregistré et expliqué]
    VAL -- acceptée --> NEW[Version Vn+1 : prompt recomposé<br/>parties protégées IDENTIQUES]
    NEW -->|itérations restantes| COACH
    NEW --> CMP[Comparer · refuser]
    CMP -->|décision humaine explicite| PROD[PROMOTION<br/>le prompt actuel est sauvegardé]
    PROD --> MEM[La réponse de la version promue<br/>entre dans la CONVERSATION]
    MEM -->|question suivante, tout l'historique| SNAP
    HUMAN[Avis humain] -.prioritaire.-> A
```

#### La conversation de l'atelier (enchaîner les cycles)

Tester un prompt sur **une seule** question isolée ne dit rien de sa tenue dans un **vrai échange**. L'atelier
conserve donc un **fil de conversation** :

- chaque campagne reste **une question** (contexte figé, comparaison honnête) ;
- **promouvoir une version fait entrer la réponse de l'IA dans la conversation** : c'est la réponse **produite par
  la version promue** (celle que le client recevrait avec le prompt mis en production) — réutilisée si une itération
  a déjà répondu avec elle, sinon **générée** avec ce prompt. Elle est affichée dans un bloc identique à la page
  coach, et **corrigeable** par l'humain ;
- la **question suivante** relance un cycle complet (itérations, Agent B, Agent A, promotion) **avec tout
  l'historique** : le Coach, l'Agent B et l'Agent A reçoivent les échanges déjà validés, exactement comme dans le
  chat ; une question de suivi (« et si j'allongeais la durée à 60 mois ? ») reste donc dans le projet en cours ;
- l'**Agent B lit tout l'historique pour comprendre le contexte, mais ne juge que le dernier échange** : les
  réponses déjà validées ne sont jamais réévaluées ; les contrôles de continuité (information déjà donnée, réponse
  qui ignore l'échange précédent, redite inutile) portent sur l'échange courant ;
- l'humain garde la main : « **Nouvelle conversation** » repart sans mémoire (**même après un rafraîchissement de la
  page** : l'abandon est mémorisé), et le fil le plus récent de l'agent
  est rechargé à l'ouverture de la page (le rechargement du navigateur ne fait plus perdre l'échange en cours).

Ce que l'humain voit dans la page :
- **Configuration** : agent à optimiser (l'agent **Générique** n'est pas proposé : il n'a pas de zone propre), **zone optimisée FIGÉE** au prompt de l'agent spécialisé (la zone transverse « agent principal » n'est plus proposée : impossible de réécrire les règles communes par inadvertance), question de test, nombre d'itérations, **trois fournisseurs IA indépendants** — IA coach (celui qui répond au client), Agent B (celui qui contrôle), Agent A (celui qui réécrit la zone) ;
- **Snapshot de référence** : la liste de ce qui est **figé** (question, données financières, classification d'intention, projet, prompt hors zone) — c'est ce qui rend la comparaison honnête ;
- **Production vs candidat** : le prompt **actuellement en production** reste distingué de toutes les versions de la campagne ; aucune version candidate n'est utilisée par les conversations tant qu'un humain ne l'a pas promue ;
- **Progression** : état, itération _n / N_, réalisées / restantes, appels IA, caractères envoyés, durée ;
- **Par itération** : la réponse du Coach, l'analyse de l'Agent B (points à améliorer, sévérité, origine), les changements demandés à l'Agent A, le prompt de la version, un **diff de la seule zone éditable** — les boutons « Voir le prompt produit / Changements » n'apparaissent que si l'Agent A a réellement modifié la zone (sinon un repère « sans modification → aucune nouvelle version » l'explique) — et un bouton **`Promouvoir`** qui valide **le prompt dont la réponse vient d'être lue** (la version proposée, jamais utilisée, se promeut depuis le tableau des versions : sa réponse est alors générée) ;
- **Actions** : `GO`, `STOP` (arrêt gracieux), `REPRENDRE`, « Ajouter mon avis », « Ajouter mon avis et continuer (+n) », **« Continuer sans avis (+n) »** (prolonger un cycle terminé sans écrire d'avis), `COMPARER`, « Promouvoir » (avec confirmation explicite), **« ACCEPTER SANS CHANGEMENT »** (visible quand l'Agent A n'a rien proposé : action **directe**, sans confirmation, qui accepte la campagne **sans réécrire le prompt** et fait entrer la réponse de l'IA dans la conversation), « Refuser la campagne » ;
- **Avis humains** : visuellement distincts du diagnostic automatique, avec leur statut (appliqué / en attente) ;
- **Bilan de la conversation** : **ouvert automatiquement à la fin d'un scénario Agent C en mode automatique**, ou
  demandé par le bouton **« COMPARER LE PROMPT INITIAL ET LE PROMPT FINAL »**, il montre ce que **tout le scénario**
  a changé au prompt — le prompt du **premier** échange face à celui **en vigueur à la fin** (dernière version
  acceptée), les deux zones avec leur cycle d'origine, le **diff** de la zone, les deux prompts complets, et le
  rappel des cycles / itérations / versions retenues. Il porte le bouton **PROMOUVOIR … EN PRODUCTION** qui vise la
  **version retenue qui n'est pas encore en production** — y compris quand les derniers cycles n'ont rien proposé
  (c'est alors celle d'un cycle antérieur, explicité à l'écran) — et il est remplacé par « ✓ Rien à promouvoir » si
  tout ce que la conversation a retenu est déjà en production. Quand aucune version n'a été retenue, le bilan est un
  prompt **identique** — l'IHM le dit explicitement au lieu de laisser croire à un échec ; c'est le complément de
  **`COMPARER`**, qui ne montre qu'une campagne (une question).
- **Conversation de l'atelier** : les échanges déjà validés (comme dans la page coach), la question en cours
  (« en attente de promotion »), la réponse de l'IA de chaque version promue (corrigeable) et un champ
  « question suivante » qui relance un cycle **avec tout l'historique** — plus « Nouvelle conversation » pour
  repartir sans mémoire.

#### Agent C — le client simulé (laisser l'IA mener la conversation)

Écrire soi-même chaque question biaise l'exercice : on n'interroge le prompt que sur ce qu'on a en tête. En cochant
**« Agent C — client simulé »**, on décrit **un client** (son profil, son projet, ce qu'il veut savoir) et l'IA
**joue son rôle** : elle pose la première question, lit la réponse du Coach, puis pose la question suivante — comme
un vrai client qui poursuit l'échange.

- **Brief du client** : le champ « question de test » devient le brief (ex. « tu as un projet de rénovation de la
  cuisine, les travaux coûtent environ 15 000 €, tu as besoin de savoir si ta situation financière le permet et
  quelle solution est la plus adaptée »). Il est **figé** pour tout le scénario : le client ne sort jamais de son
  cadre.- **Bouton « GÉNÉRER PROJET »** : au lieu d'écrire le brief soi-même, l'Agent C l'invente — il propose **qui est le
  client** et **pourquoi il vient** (véhicule en panne, travaux, impôt à honorer…), en lien avec l'agent
  sélectionné (crédit conso, épargne, assurance…). Chaque nouvel appui propose un **autre** projet. Le texte est
  écrit dans le champ « Brief du client » : on le relit, on le corrige si besoin, ou on l'ignore. Le brief décrit
  le **projet seul** (2 à 3 phrases) : ni le client (le Coach connaît son client et son dossier), ni les
  **modalités de financement** (durée, mensualité cible, apport), ni la **synthèse du dossier** (solde, épargne,
  mensualité de crédit en cours) : il dit ce qui est arrivé, le projet, son objet, le montant et ce que le client
  veut savoir. Le projet reste
  **cohérent avec le dossier réel** : le modèle reçoit le solde du compte courant, l'épargne et la mensualité de
  crédit en cours, plus un **plafond de montant** calculé par la banque — une proposition hors de portée du
  client (un montant à six chiffres avec quelques milliers d'euros d'épargne) est **refusée** et expliquée.- **Trois chiffres, pas un de plus** : l'Agent C ne connaît que le **solde du compte courant**, l'**épargne
  disponible** et la **mensualité de crédit en cours**. Il ne donne **jamais** de conseil, ne cite aucun autre
  chiffre et, si le Coach lui réclame une donnée qu'il n'a pas (revenus, charges, apport…), il répond simplement
  qu'il ne l'a pas — il n'invente rien.
- **Profondeur** : le nombre **maximum** de questions que le client posera (ex. 10). La borne est tenue par
  l'interface : le scénario ne la dépasse jamais, la **dernière question autorisée** (numéro = profondeur) est
  **posée normalement**, et le client peut décider lui-même de conclure **plus tôt** s'il a obtenu ce qu'il
  voulait savoir (il affiche alors sa phrase de clôture).
- **Qui promeut ?** Les **deux** possibilités restent disponibles, au choix par **case à cocher** : **décochée**
  (défaut) = vous validez chaque cycle (promouvoir une version, ou « accepter sans changement ») puis vous cliquez
  **CONTINUER** ; **cochée (« Enchaînement automatique »)** = la dernière version du cycle est **acceptée pour la
  conversation** — sa réponse entre dans la mémoire du client, qui enchaîne seul sa question suivante — **sans
  écrire le prompt de production**. À la fin du scénario, l'interface ouvre **automatiquement** le bilan
  « Comparaison de la conversation — début ↔ fin », qui porte le bouton **PROMOUVOIR** : vous comparez le prompt du
  début et celui de la fin, puis vous décidez ce qui part en production. La promotion reste donc, dans tous les
  cas, **une décision humaine**. Les cycles **s'enchaînent sur la version retenue** (le cycle 2 teste le prompt
  enrichi du cycle 1, etc.) : le bilan est **cumulatif** et **un seul clic** adopte **tout** le travail du
  scénario ; l'atelier signale « **zone héritée** » quand la zone testée vient d'un cycle précédent.
- **Modèle dédié** : une liste déroulante propre à l'Agent C (DeepSeek / OpenAI) — le client peut être joué par un
  autre modèle que le Coach.
- **STOP / CONTINUER** : `STOP` arrête le scénario **sans rien perdre** (le cycle en cours se termine, la campagne
  passe en pause) ; `CONTINUER` demande la question suivante du client.
- La question du client **apparaît dans la conversation** (« Question du client (Agent C) — n°2 / profondeur 10 »)
  et reste **corrigeable** avant de lancer le cycle : l'humain garde la main sur ce qui sera testé.
- **Rien de plus n'est enregistré** : ni fiche d'évaluation, ni question intermédiaire — seuls la **conversation**
  (les échanges acceptés) et le **prompt promu** sont écrits.

Règles fonctionnelles fortes :

1. **Une seule zone est optimisable** par campagne ; tout ce qui est hors zone est **recomposé par le backend** — jamais par l'IA ;
2. L'Agent A **ne peut pas** sortir de la zone et ne peut pas modifier les parties protégées : le backend rejette et **conserve** la version précédente ;
3. Un agent **sans zone** (prompt non marqué) n'est **pas** optimisable : l'atelier ne devine jamais la zone ;
4. `STOP` n'interrompt **jamais** brutalement un appel IA : la réponse en cours est enregistrée, puis la campagne passe en pause ;
5. **Aucune écriture de prompt sans décision humaine** : le passage en production est une action humaine, confirmée, et le prompt précédent est sauvegardé (retour arrière possible). Le mode **Agent C** peut enchaîner les cycles seul — par une case à cocher **explicite** (« Enchaînement automatique », décochée par défaut) — mais il **accepte** les versions *pour la conversation* **sans toucher au prompt de production** : à la fin, le bilan début ↔ fin et son bouton **PROMOUVOIR** laissent la décision à l'humain ;
6. **On n'est jamais bloqué** : si l'Agent A n'a proposé **aucune** modification, aucune version nouvelle n'existe — « **ACCEPTER SANS CHANGEMENT** » accepte alors la campagne **sans réécrire le prompt** (le backend vérifie que le contenu est identique : ni sauvegarde, ni écriture) et **sans demander de confirmation** (il n'y a rien à écraser) : la réponse de l'IA entre dans la conversation et l'échange peut continuer ;
7. Aucun code ni règle métier n'est modifié par l'atelier : il ne change **qu'un texte de prompt**.

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

### 4.2 Restriction du catalogue MONTRÉ (contexte financement)
Quand le message relève d'un **besoin de financement** et que le projet est connu :
- les fichiers **hors `/data/catalogue/`** restent visibles (transactions, synthèse) ;
- parmi les fichiers `/data/catalogue/*.json`, seuls ceux dont les familles documentées croisent les familles autorisées sont **montrés** à l'IA.

Exemple concret (projet VEHICLE) : `credit_conso.json` est **montré** ; `credit_immo.json`, `epargne.json`,
`assurance_*.json` ne le sont **pas** — l'IA ne peut donc pas les découvrir spontanément. En revanche, **s'il
demande explicitement un fichier déclaré au catalogue, il lui est fourni** (« s'il le demande, on l'autorise ») :
c'est le comportement du chat, qui répond une phrase de repli quand la donnée manque. La barrière reste double :
seuls les chemins du **catalogue** sont lisibles (aucun fichier inventé), et seuls les produits de
`compatibleProducts` sont **recommandables**.

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
31. Une simulation de crédit **adossée à la grille de taux** fournie, annoncée comme indicative et non contractuelle (la souscription fait foi), est **conforme** ; le refus de chiffrer suivi d'une redirection vers le simulateur officiel reste conforme lorsque la grille n'est pas disponible ;
31bis. Une simulation produite détaille **montant, durée, mensualité, taux débiteur annuel fixe, TAEG fixe, frais de dossier, coût total et montant total dû**, passe en **tableau** — rendu comme un vrai tableau dans l'interface — dès que plusieurs durées ou mensualités sont chiffrées, et se termine par le **lien de souscription officiel** du produit (`url_souscription` de la fiche) — le simulateur n'étant proposé que si aucun chiffrage n'est possible ;
    ⚠️ *Référence métier* : le prompt de l'agent crédit conso est aujourd'hui une **version allégée** qui ne demande ni ce détail en 8 informations, ni le tableau, ni le lien de souscription. L'affichage en vrai tableau côté IHM et le contrôle qualité sur les chiffres hors grille restent, eux, en place ;
    ⚠️ *Critère de référence métier* : dans l'état actuel du POC, le prompt de l'agent crédit conso est une **version allégée** qui ne demande ni ce détail en 8 informations, ni le tableau, ni le lien de souscription (l'affichage IHM en vrai tableau et le contrôle qualité sur les chiffres restent, eux, en place) ;
32. Un chiffrage de crédit produit **sans grille de taux**, ou présenté comme un engagement ferme, est signalé (sévérité HIGH) ;
33. La page Qualité n'affiche que les contrôles **réellement exécutés** (aucun faux « 0 ») ;
34. Aucun avis ne contient de donnée personnelle (identifiant pseudonymisé, commentaire nettoyé).

### 7.4 Feedback Conseiller

35. Un feedback positif s'enregistre **sans aucun détail** (évaluation globale seule) ;
36. Un feedback négatif peut préciser zones, motifs, produits, intérêts, suivi et email — tout reste facultatif ;
37. Un double clic ou un retry ne crée **qu'un seul** événement ; une révision crée une **version suivante** sans écraser l'historique ;
38. Un échec d'enregistrement ne bloque jamais le dossier conseiller ;
39. La correction d'un niveau d'intérêt conserve **les deux valeurs** (IA et conseiller) ;
40. Un produit oublié ne peut être choisi que dans le **catalogue réel** ;
41. Le nom du conseiller n'est jamais stocké (identifiant technique uniquement) ;
42. Aucun feedback ne modifie automatiquement le Coach : le rapport IA ne produit que des recommandations.

### 7.5 Atelier d'optimisation des prompts

43. Le prompt de production reste **strictement identique** à ce qu'il était avant l'introduction des marqueurs de zone (les lignes `[[[` / `]]]` ne sont **jamais** envoyées au LLM) ;
44. Une campagne optimise **une seule** zone : les parties hors zone sont recomposées par le backend et sont identiques dans toutes les versions ;
45. Un prompt **sans** zone éditable est refusé avec un message explicite (aucune zone devinée) ;
46. Toutes les itérations d'une campagne utilisent la **même** question, les **mêmes** données, la **même** classification d'intention et le **même** projet ;
47. Une proposition d'Agent A qui sort de la zone, contient un marqueur, est vide ou dépasse la taille maximale est **rejetée** : la version précédente est conservée et l'échec est lisible ;
48. L'avis humain est **prioritaire** : il est appliqué par l'Agent A avant le prochain appel au Coach et son application est tracée ;
49. `STOP` n'interrompt pas un appel IA en cours : la réponse est enregistrée puis la campagne passe en pause ; la reprise continue la campagne sans repartir de zéro ;
50. Rien n'est supprimé : itérations, versions, diagnostics, avis et décisions restent consultables après la fin de la campagne ;
51. La **promotion** est une action humaine confirmée ; le prompt précédent est **sauvegardé** dans un historique et un retour arrière explicite est possible ;
52. Le mode automatique de l'Agent C **n'écrit jamais** le prompt de production : il **accepte** les versions pour la conversation (la mémoire du client en dépend) et c'est le bilan début ↔ fin, à la fin du scénario, qui propose la **promotion** — décision humaine ; une version acceptée mais non écrite est signalée comme telle (« ★ acceptée (à promouvoir) ») ;
53. L'atelier nécessite un fournisseur IA **réel** : en mode MOCK il refuse de démarrer (message explicite nommant l'étape) ;
54. Aucun code, seuil, règle métier ou catalogue n'est modifié par l'atelier : seul un fichier de **prompt** peut changer, et uniquement après promotion ;
55. Le **routage des modèles** (coach / Agent B / Agent A) est figé avec la campagne et visible dans l'IHM : une reprise rejoue les mêmes modèles ;
56. Une erreur d'un fournisseur est affichée **avec l'étape concernée** et laisse la campagne reprenable — elle n'est jamais masquée.

### 7.6 Score commercial et annuaire des conversations

57. Le score de sens commercial figure dans le **mail conseiller** (score, libellé de priorité, explication
    courte) avec un **lien d'appel** du client ; il **n'est jamais** envoyé au client (ni dans le brouillon,
    ni dans la réponse du Coach) ;
58. Un score **proposé par l'IA** est conservé mais **borné à 0..100**, et les critères mesurés (maturité,
    intérêt, urgence, capacité, engagement) restent **traçables** — sans proposition de l'IA, le score est
    calculé de façon déterministe ;
59. Le score **n'introduit aucun seuil bancaire** : le critère « capacité » ne fait que décrire les indicateurs
    réellement disponibles (et reste neutre s'ils manquent) ;
60. L'annuaire du centre d'appels **filtre** (période, catégorie, recherche) et **trie** (dont le score) côté
    serveur, et le détail affiche la synthèse **et sa pièce jointe** (brouillon d'email client) ;
61. Un dossier ancien, sans score ni identité client, reste **lisible** avec des valeurs vides (aucune valeur
    inventée) ;
62. Le numéro de téléphone vient **exclusivement de la configuration** (`app.suivi.customer-phone`) : un lien
    d'appel proposé par l'IA est **neutralisé** par le contrôle d'anti-invention d'URL ;
63. Chaque dossier porte un **statut d'avancement** (« Nouveau » à la clôture) que le centre d'appels fait
    évoluer depuis la pop-in (liste déroulante) ; **chaque changement est conservé** (statut précédent, nouveau
    statut, commentaire, horodatage) et un statut inchangé **sans message** n'écrit rien ;
64. Un **message** peut être laissé **seul** sur un dossier (statut inchangé) : il est journalisé, compté dans
    la colonne Statut du tableau et visible dans le journal de suivi ;
65. Un **code de statut inconnu** est refusé (HTTP 400) : aucun statut n'est deviné ni normalisé
    silencieusement.

---

## 8. Limites connues (POC)
- Fournisseur par défaut **côté backend** : Mode démo (MOCK) — classification enrichie réelle uniquement avec GPT/DeepSeek (ou un modèle **local**) configuré ; l'écran choisit DeepSeek par défaut ;
- Clés API **externalisées** via variables d'environnement (`OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, …) — aucune clé en dur ;
- Logs et conversations **en mémoire** (perdus au redémarrage) → une clôture après redémarrage ne produit aucun dossier (`NO_CONVERSATION`) ;
- Un seul « projet courant » géré (le remplacement est accepté pour le POC) ;
- Marketing : les événements ne sont produits qu'à la **clôture** d'une conversation ; les jeux de démonstration (`demo=true`) et les données réelles cohabitent dans `data/marketing` (le bandeau de la page le signale) ;
- Marketing : pas de ventilation par agence/segment ni d'export Excel — export **CSV** uniquement.
- Qualité : seuls **6 contrôles automatiques** sont implémentés (simulation de crédit, produit incompatible, URL inventée, demande non résolue, donnée non récupérée, répétition excessive) ; les contrôles `UNNECESSARY_ADVISOR_REDIRECT`, `UNSUPPORTED_PRODUCT_CLAIM`, `INVENTED_DATA` et `CONVERSATION_CONTEXT_LOST` sont décrits mais **non implémentés** et affichés comme tels ;
- Qualité : l'analyse IA **des commentaires** (classification automatique) n'est pas activée — les thèmes affichés proviennent d'une heuristique locale déterministe ;
- Qualité : le taux de participation repose sur les conversations pour lesquelles des contrôles ont été exécutés (une conversation non clôturée n'est pas comptée) ;
- Qualité : en dessous du seuil d'échantillon (`app.quality.sufficient-sample-size`), le rapport IA reste prudent et le signale.
- Feedback Conseiller : la génération de l'analyse IA est **manuelle** dans le POC (bouton) ; un batch quotidien est disponible via l'API (`POST /api/advisor-feedback/batch`) mais n'est pas planifié ;
- Feedback Conseiller : la comparaison automatique « version IA / version conseiller » de l'email (P2) n'est pas implémentée — seul le niveau déclaré par le conseiller est enregistré ;
- Feedback Conseiller : la vue 360° (client + Quality + conseiller) n'est pas encore agrégée — les structures partagent le `sessionId` pour la préparer.
- Atelier d'optimisation : il n'attribue **pas** de note automatique de qualité ; il compare des réponses et fournit un diagnostic, la décision finale reste humaine ;
- Atelier d'optimisation : la boucle est pilotée par l'IHM (une requête HTTP = une itération) — il n'y a ni file de tâches ni exécution en arrière-plan : l'onglet doit rester ouvert pendant la campagne ;
- Atelier d'optimisation : les missions commencent avec GPT/DeepSeek réellement configurés ; en mode MOCK l'atelier refuse de démarrer (les autres modules continuent de fonctionner en mode démo) ;
- Atelier d'optimisation : l'IHM **ne permet pas de reprendre une campagne passée** (pas d'historique de campagnes à l'écran) : démarrer une nouvelle campagne clôt automatiquement la précédente (statut *Annulée*), ses fichiers restant sur disque — en revanche la **conversation** (fil de l'atelier) est reprise automatiquement, et la question suivante ouvre une nouvelle campagne avec la mémoire des échanges validés ;
- Atelier d'optimisation : si le Coach réclame un fichier que le contexte ne peut pas fournir, l'itération est **dégradée comme en production** (réponse de repli + motif affiché) au lieu d'échouer : la campagne et la conversation ne sont jamais bloquées.
