Tu dois implémenter dans notre application bancaire POC un module complet de **Marketing Intelligence basé sur les conversations du Coach Financier IA**, ainsi qu'une page dédiée permettant au service Marketing d'exploiter les résultats.

# CONTRAINTE MAJEURE

Il s'agit d'un POC.

**Aucune base de données ne doit être utilisée pour ce module.**

Les données doivent être stockées localement dans des fichiers.

Formats recommandés :

* `.jsonl` pour les événements unitaires si pratique ;
* `.parquet` pour les volumes, statistiques et agrégations ;
* `.json` pour les configurations et éventuellement les rapports IA ;
* `.csv` uniquement si utile pour export ou debug.

Privilégier Parquet pour les données analytiques lorsque cela reste simple avec la stack existante.

Ne pas introduire PostgreSQL, MySQL, MongoDB, Redis, Elasticsearch ou autre stockage serveur.

---

# 1. OBJECTIF GÉNÉRAL

Transformer les conversations entre les clients et le Coach Financier IA en signaux Marketing structurés permettant de comprendre :

* quels projets intéressent les clients ;
* quels produits/offres sont recommandés ;
* quels produits/offres génèrent réellement de l'intérêt ;
* quels produits sont souvent recommandés mais peu suivis ;
* quels produits sont refusés ;
* pourquoi ils sont refusés ;
* quels produits sont fréquemment associés ;
* quels besoins clients restent non couverts ;
* quelles informations produit sont souvent manquantes ;
* quelles tendances apparaissent ;
* quelles opportunités Marketing se dégagent ;
* quelles anomalies ou signaux faibles doivent être surveillés.

Créer également une page dédiée :

`/marketing`

ou une route cohérente avec l'application existante.

---

# 2. ARCHITECTURE CIBLE

Respecter cette architecture :

```text
Conversations clients
        ↓
IA Extracteur Marketing
        ↓
Événements structurés
        ↓
Fichiers JSONL / Parquet
        ↓
Batch Java / Python / service analytique existant
        ↓
Agrégats Parquet / JSON
        ↓
IA Analyste Marketing
        ↓
Rapport Marketing JSON / Markdown
        ↓
API Spring Boot
        ↓
Page React /marketing
```

Séparation des responsabilités :

```text
IA
→ comprend
→ classe
→ extrait les signaux
→ interprète les statistiques

Code déterministe
→ stocke
→ déduplique
→ compte
→ calcule
→ compare
→ agrège

Frontend
→ affiche
→ filtre
→ explore
```

Le LLM ne doit pas calculer les statistiques.

---

# 3. RÉUTILISER LA FIN DE CONVERSATION EXISTANTE

Nous disposons déjà d'un traitement de fin de conversation qui produit notamment :

* synthèse conseiller ;
* email client préparé ;
* produits d'intérêt.

Étudier en priorité la possibilité d'ajouter au même appel IA :

```json
"marketingEvents": []
```

Exemple :

```json
{
  "conversationSummary": {},
  "productsOfInterest": [],
  "advisorEmail": {},
  "preparedCustomerEmail": {},
  "marketingEvents": []
}
```

Objectif :

```text
1 conversation clôturée
      ↓
1 appel IA
      ↓
Récap conseiller
+ brouillon client
+ signaux Marketing
```

Éviter les appels IA supplémentaires inutiles.

---

# 4. STOCKAGE DES ÉVÉNEMENTS

Créer un stockage local, par exemple :

```text
data/
  marketing/
    events/
      marketing_events_2026-09-11.jsonl
```

ou :

```text
data/
  marketing/
    events/
      2026/
        09/
          marketing_events_2026-09-11.parquet
```

Choisir l'organisation la plus simple et robuste.

Chaque ligne / enregistrement représente un événement.

Exemple :

```json
{
  "eventId": "uuid",
  "eventType": "PRODUCT_INTEREST",
  "timestamp": "2026-09-11T09:00:00",
  "sessionId": "session_xxx",
  "anonymousCustomerId": "customer_hash_xxx",

  "projectType": "VEHICLE",
  "projectAmountRange": "10000_15000",

  "productId": "sg_pret_auto",
  "productFamily": "CONSUMER_CREDIT",

  "interestLevel": "HIGH",
  "reasonCategory": "DETAIL_REQUEST",
  "reason": "Le client a demandé les conditions de l'offre.",

  "advisorFollowUpRecommended": true,

  "confidence": 0.96,
  "extractorVersion": "marketing-events-v1",
  "promptVersion": "marketing-extractor-v1",
  "model": "...",
  "createdAt": "..."
}
```

---

# 5. CONFIDENTIALITÉ

Les fichiers Marketing doivent contenir uniquement des données anonymisées ou pseudonymisées.

Ne pas stocker :

* nom ;
* prénom ;
* email ;
* téléphone ;
* adresse ;
* IBAN ;
* numéro de compte ;
* détail complet des transactions ;
* historique conversationnel brut identifiable.

Si un identifiant client est nécessaire :

utiliser un identifiant pseudonymisé/hashé.

---

# 6. TYPES D'ÉVÉNEMENTS

Prévoir au minimum :

```text
PROJECT_DETECTED
PRODUCT_RECOMMENDED
PRODUCT_INTEREST
PRODUCT_REJECTED
PRODUCT_COMPARISON
SUBSCRIPTION_INTEREST
APPOINTMENT_INTEREST
ADVISOR_HANDOFF
UNMET_NEED
MISSING_PRODUCT_INFORMATION
```

Éventuellement :

```text
COACH_DATA_MISSING
COACH_PRODUCT_MISMATCH
COACH_UNANSWERED_REQUEST
```

Ces événements QA doivent rester distincts des événements d'intérêt client.

---

# 7. PROJECT_DETECTED

Valeurs possibles :

```text
VEHICLE
REAL_ESTATE
HOME_WORK
ELECTRONICS
FURNITURE
TRAVEL
EDUCATION
CASH_NEED
SAVINGS
BUDGET
OTHER
UNKNOWN
```

Pour le Marketing, stocker de préférence une tranche de montant plutôt qu'un montant précis.

Exemple :

```text
0_2000
2000_5000
5000_10000
10000_15000
15000_30000
30000_PLUS
```

Les tranches doivent être configurables.

---

# 8. DISTINGUER RECOMMANDATION ET INTÉRÊT

Règle fondamentale :

```text
PRODUCT_RECOMMENDED != PRODUCT_INTEREST
```

`PRODUCT_RECOMMENDED` signifie que le Coach a proposé le produit.

`PRODUCT_INTEREST` signifie que le client a réellement montré de l'intérêt.

---

# 9. NIVEAUX D'INTÉRÊT

Utiliser :

```text
LOW
MEDIUM
HIGH
```

## LOW

Produit simplement évoqué.

## MEDIUM

Le client pose des questions ou manifeste un intérêt modéré.

## HIGH

Le client :

* demande des détails précis ;
* revient sur le produit ;
* demande un devis ;
* demande comment souscrire ;
* demande un rendez-vous ;
* manifeste explicitement son intérêt.

---

# 10. RAISONS D'INTÉRÊT

Prévoir par exemple :

```text
DETAIL_REQUEST
PRICE_REQUEST
RATE_REQUEST
COVERAGE_REQUEST
COMPARISON
SUBSCRIPTION_REQUEST
QUOTE_REQUEST
ADVISOR_REQUEST
OTHER
```

---

# 11. RAISONS DE REFUS

Prévoir :

```text
PRICE
RATE
PREFERS_CASH
DOES_NOT_WANT_CREDIT
INSUFFICIENT_COVERAGE
DURATION
CONDITIONS
COMPETITOR
NOT_NEEDED
TOO_COMPLEX
OTHER
```

---

# 12. UNMET_NEED

Créer lorsque le client exprime un besoin pour lequel aucune offre réellement adaptée n'est disponible.

Exemple :

```json
{
  "eventType": "UNMET_NEED",
  "projectType": "ELECTRONICS",
  "reasonCategory": "NO_SUITABLE_PRODUCT",
  "reason": "Besoin de financement très court pour achat informatique."
}
```

---

# 13. MISSING_PRODUCT_INFORMATION

Créer lorsque le client pose une question utile mais que le catalogue ne fournit pas la réponse.

Exemple :

```json
{
  "eventType": "MISSING_PRODUCT_INFORMATION",
  "productId": "sg_assurance_auto",
  "reasonCategory": "MISSING_COVERAGE_INFORMATION",
  "reason": "Information véhicule de remplacement absente."
}
```

---

# 14. ÉCRITURE FICHIER ROBUSTE

L'écriture des événements doit être fiable.

Prévoir :

* encodage UTF-8 ;
* création automatique des répertoires ;
* identifiant unique d'événement ;
* pas de duplication lors d'une relance ;
* écriture atomique si possible ;
* gestion des fichiers absents ;
* verrouillage simple si plusieurs écritures simultanées sont possibles.

Pour le POC, rester simple.

---

# 15. JSONL OU PARQUET

Recommandation :

## Événements bruts

Utiliser JSONL si l'écriture incrémentale est beaucoup plus simple :

```text
marketing_events_2026-09-11.jsonl
```

Chaque événement = une ligne JSON.

## Analytique

Convertir / agréger ensuite vers Parquet :

```text
marketing_events_2026-09.parquet
```

ou produire directement :

```text
marketing_daily_product_metrics.parquet
marketing_daily_project_metrics.parquet
marketing_daily_rejections.parquet
marketing_daily_cross_sell.parquet
```

Si la librairie Parquet est déjà facilement disponible côté projet, il est également acceptable d'utiliser Parquet dès les événements bruts.

Ne complexifie pas inutilement le POC.

---

# 16. DÉDUPLICATION

Une conversation peut générer plusieurs événements sur un même produit.

Il faut pouvoir distinguer :

* événements ;
* sessions uniques ;
* clients pseudonymisés uniques.

Exemple :

```text
"Parlez-moi du Prêt Auto"
"Quel taux ?"
"Quelles conditions ?"
"Comment souscrire ?"
```

Peut produire plusieurs signaux.

Mais :

```text
uniqueInterestedSessions = 1
```

pour cette session.

---

# 17. SCORE D'INTÉRÊT

Si un score est utilisé, il doit être calculé par code.

Exemple configurable :

```text
PRODUCT_RECOMMENDED       +1
PRODUCT_INTEREST MEDIUM   +2
PRODUCT_INTEREST HIGH     +3
PRODUCT_COMPARISON        +2
SUBSCRIPTION_INTEREST     +4
APPOINTMENT_INTEREST      +5
PRODUCT_REJECTED          -5
```

Le LLM ne calcule jamais ce score.

---

# 18. BATCH QUOTIDIEN

Créer un traitement quotidien produisant les statistiques à partir des fichiers d'événements.

Exemple :

```text
MarketingDailyBatch
```

Entrée :

```text
data/marketing/events/
```

Sortie :

```text
data/marketing/aggregates/
```

Par exemple :

```text
marketing_overview_2026-09-10.json
marketing_product_metrics_2026-09-10.parquet
marketing_project_metrics_2026-09-10.parquet
marketing_rejections_2026-09-10.parquet
marketing_cross_sell_2026-09-10.parquet
marketing_unmet_needs_2026-09-10.parquet
marketing_missing_info_2026-09-10.parquet
```

Le batch doit être idempotent.

Une relance ne doit pas doubler les résultats.

---

# 19. KPI GLOBAUX

Calculer par code :

* nombre de conversations analysées ;
* sessions avec intérêt produit ;
* clients pseudonymisés uniques si disponible ;
* nombre d'intérêts HIGH ;
* intentions de souscription ;
* demandes de rendez-vous ;
* besoins non couverts ;
* informations produit manquantes.

---

# 20. KPI PRODUIT

Pour chaque produit :

* recommandations ;
* sessions intéressées ;
* clients uniques intéressés ;
* HIGH ;
* MEDIUM ;
* refus ;
* intentions de souscription ;
* demandes de rendez-vous ;
* taux intérêt / recommandation ;
* score total éventuel ;
* évolution.

---

# 21. KPI PROJET

Par projet :

* volume ;
* part relative ;
* évolution ;
* tranches de montant ;
* produits les plus associés ;
* intérêts ;
* refus ;
* besoins non couverts.

---

# 22. ANALYSE DES REFUS

Produire les volumes et pourcentages par raison.

Exemple :

```text
PRICE                     34 %
PREFERS_CASH              27 %
DOES_NOT_WANT_CREDIT      16 %
DURATION                  12 %
CONDITIONS                 7 %
OTHER                      4 %
```

Tous les calculs doivent être faits par code.

---

# 23. CROSS-SELL

Identifier les produits fréquemment présents dans une même session intéressée.

Exemple :

```text
Prêt Auto
→ Assurance Auto

620 sessions communes
62 %
```

Calcul effectué par code.

Ne pas utiliser le LLM pour calculer les associations.

---

# 24. PRODUITS RECOMMANDÉS MAIS PEU SUIVIS

Calculer :

```text
recommendedCount
interestedSessionCount
interestRate
```

Exemple :

```text
Prêt Personnel

Recommandé : 850
Intérêt : 42
Taux : 4,9 %
```

Ces données seront interprétées ensuite par l'IA Marketing.

---

# 25. TENDANCES

Calculer par code :

* variation vs jour précédent ;
* variation vs période précédente ;
* moyenne 7 jours ;
* moyenne 30 jours si suffisamment de données.

Gérer proprement les divisions par zéro et données insuffisantes.

---

# 26. RAPPORT IA QUOTIDIEN

Après calcul des agrégats, appeler une IA Analyste Marketing.

Ne lui envoyer PAS les conversations brutes.

Envoyer uniquement les statistiques et informations agrégées nécessaires.

Exemple :

```json
{
  "date": "2026-09-10",
  "conversationCount": 2843,
  "overview": {},
  "projects": [],
  "productMetrics": [],
  "rejectionMetrics": [],
  "productAssociations": [],
  "unmetNeeds": [],
  "missingProductInformation": [],
  "trends": {}
}
```

L'IA :

* interprète ;
* explique ;
* synthétise ;
* détecte les signaux importants ;
* propose des pistes d'analyse.

Elle ne calcule aucun chiffre.

---

# 27. STOCKAGE DU RAPPORT

Stocker le rapport localement.

Exemple :

```text
data/marketing/reports/
  marketing_report_2026-09-10.json
```

Éventuellement :

```text
marketing_report_2026-09-10.md
```

Mais privilégier JSON si le frontend doit l'exploiter structurellement.

---

# 28. PAGE MARKETING

Créer une page :

`/marketing`

Nom :

`Marketing Intelligence`

Sous-titre possible :

`Analyse des besoins, intérêts et comportements issus des conversations avec le Coach Financier IA`

La page doit utiliser uniquement les fichiers agrégés exposés via le backend.

Le frontend ne doit pas lire directement les fichiers disque.

---

# 29. API BACKEND

Créer des endpoints Spring Boot lisant les fichiers agrégés.

Exemples :

```text
GET /api/marketing/overview
GET /api/marketing/products
GET /api/marketing/products/{productId}
GET /api/marketing/projects
GET /api/marketing/trends
GET /api/marketing/rejections
GET /api/marketing/cross-sell
GET /api/marketing/unmet-needs
GET /api/marketing/missing-information
GET /api/marketing/reports/daily
```

Les endpoints lisent les fichiers JSON / Parquet générés.

Pas de base de données.

---

# 30. FILTRES

Prévoir au minimum :

```text
Aujourd'hui
Hier
7 jours
30 jours
Personnalisé
```

ainsi que :

* produit ;
* famille produit ;
* type de projet ;
* niveau d'intérêt ;
* type d'événement.

Si les fichiers agrégés quotidiens permettent facilement la reconstruction de la période, agréger côté backend.

---

# 31. KPI CARDS

Afficher en haut :

* conversations analysées ;
* sessions intéressées ;
* intérêts HIGH ;
* intentions de souscription ;
* demandes de rendez-vous ;
* besoins non couverts.

Afficher les variations lorsque disponibles.

---

# 32. TOP PRODUITS

Créer un bloc :

`Produits les plus intéressants`

Afficher :

* produit ;
* famille ;
* nombre de sessions intéressées ;
* HIGH ;
* MEDIUM ;
* évolution ;
* score éventuel.

Ajouter un graphique en barres simple.

---

# 33. PERFORMANCE DES RECOMMANDATIONS

Créer un bloc très visible :

`Recommandation vs intérêt réel`

Afficher :

```text
Produit
Recommandations
Sessions intéressées
HIGH
Taux intérêt / recommandation
```

Cette section doit permettre d'évaluer la pertinence des recommandations du Coach.

---

# 34. PROJETS CLIENTS

Afficher :

* projet ;
* volume ;
* pourcentage ;
* évolution.

Permettre de cliquer sur un projet pour filtrer ou ouvrir un détail.

---

# 35. TENDANCES

Ajouter un graphique temporel permettant de visualiser :

* volume de conversations ;
* intérêts ;
* HIGH ;
* intentions de souscription ;
* RDV.

---

# 36. PRODUITS EN PROGRESSION / BAISSE

Créer :

`Produits en progression`

et :

`Produits en baisse`

Afficher clairement la période de comparaison.

---

# 37. REFUS

Créer :

`Pourquoi les clients refusent-ils ?`

Vue globale et filtre par produit.

Utiliser un bar chart.

---

# 38. CROSS-SELL

Créer :

`Produits souvent associés`

Exemple :

```text
Prêt Auto → Assurance Auto : 62 %
```

Afficher également le volume de sessions communes.

---

# 39. BESOINS NON COUVERTS

Créer :

`Besoins non couverts`

Afficher :

* description ;
* type de projet ;
* nombre ;
* évolution ;
* confiance IA si disponible.

---

# 40. INFORMATIONS PRODUIT MANQUANTES

Créer :

`Questions auxquelles notre catalogue ne répond pas encore`

Exemple :

```text
Assurance Auto

Véhicule de remplacement : 46
Conducteur secondaire : 31
Franchise : 28
```

---

# 41. ANALYSE IA DU JOUR

Créer une section importante :

`Analyse IA du jour`

Afficher :

* résumé exécutif ;
* tendances principales ;
* opportunités ;
* points d'attention ;
* anomalies ;
* besoins non couverts ;
* pistes d'analyse.

Indiquer :

`Analyse générée à partir de statistiques agrégées. Les chiffres sont calculés par le moteur analytique et non par l'IA.`

---

# 42. SIGNALS À SURVEILLER

Afficher au maximum 5 à 8 signaux prioritaires.

Exemples :

```text
↑ Assurance Auto +21 %
! 87 besoins non couverts
! 46 questions produit sans réponse
↓ Prêt Personnel -8 %
```

---

# 43. DRILL-DOWN PRODUIT

Au clic sur un produit, afficher :

* recommandations ;
* intérêt ;
* HIGH ;
* MEDIUM ;
* refus ;
* intentions de souscription ;
* RDV ;
* évolution ;
* raisons d'intérêt ;
* raisons de refus ;
* produits associés ;
* projets associés.

---

# 44. DRILL-DOWN PROJET

Afficher :

* volume ;
* évolution ;
* tranches de montant ;
* produits recommandés ;
* produits intéressants ;
* refus ;
* besoins non couverts ;
* associations.

---

# 45. TABLEAU COMPLET

Créer une vue :

```text
Produit
Famille
Recommandations
Intérêts
HIGH
MEDIUM
Refus
Souscription
RDV
Taux intérêt
Évolution
```

Prévoir :

* tri ;
* recherche ;
* pagination ou limitation raisonnable.

---

# 46. EXPORT

Prévoir au minimum :

`Exporter CSV`

à partir des données agrégées.

Ne jamais exporter de données personnelles.

---

# 47. DESIGN

Réutiliser le design system existant.

Page bancaire professionnelle :

* sobre ;
* moderne ;
* lisible ;
* desktop-first ;
* responsive ;
* sans surcharge graphique.

Éviter les graphiques gadgets.

---

# 48. STRUCTURE FRONTEND SUGGÉRÉE

```text
MarketingPage
 ├── MarketingHeader
 ├── MarketingFilters
 ├── MarketingKpiCards
 ├── ProductInterestRanking
 ├── ProjectBreakdown
 ├── MarketingTrendChart
 ├── RecommendationPerformance
 ├── RejectionAnalysis
 ├── CrossSellInsights
 ├── UnmetNeeds
 ├── MissingProductInformation
 ├── AiMarketingReport
 └── MarketingAlerts
```

Adapter aux conventions du projet.

---

# 49. DONNÉES DE DÉMONSTRATION

Si le POC ne contient pas encore suffisamment d'événements réels, prévoir éventuellement :

```text
demoMode=true
```

Les données de démo doivent être clairement identifiées.

Ne jamais mélanger silencieusement données réelles et données fictives.

---

# 50. PERFORMANCE

Ne pas charger tous les fichiers d'événements bruts pour chaque affichage.

Le batch doit pré-calculer les données nécessaires au dashboard.

Le frontend travaille sur les agrégats.

Architecture :

```text
events JSONL
   ↓ batch
aggregates Parquet/JSON
   ↓ API
React
```

---

# 51. TESTS

Tester au minimum :

* aucun fichier présent ;
* fichier vide ;
* JSONL invalide sur une ligne ;
* relance batch ;
* absence de rapport IA ;
* produit sans recommandation ;
* division par zéro ;
* périodes multiples ;
* filtre produit ;
* filtre projet ;
* événements répétés ;
* événement faible confiance ;
* données de plusieurs jours ;
* export CSV.

---

# 52. PRIORITÉ POC

## P0

* stockage événements fichier ;
* agrégations quotidiennes ;
* page `/marketing` ;
* filtres période ;
* KPI principaux ;
* top produits ;
* projets ;
* recommandé vs intérêt ;
* rapport IA.

## P1

* refus ;
* tendances ;
* cross-sell ;
* besoins non couverts ;
* informations produit manquantes.

## P2

* drill-down ;
* export ;
* alertes avancées.

---

# 53. LIVRABLES

À la fin, fournir :

* fichiers créés ;
* fichiers modifiés ;
* structure des répertoires de données ;
* format des fichiers ;
* schéma événement ;
* endpoints ;
* composants React ;
* batchs ;
* exemple de fichiers générés ;
* exemple de rapport IA ;
* tests ;
* commandes d'exécution.

---

# PRINCIPE FINAL

Le POC doit fonctionner sans base de données.

La chaîne cible est :

```text
Conversation
      ↓
Signaux IA
      ↓
JSONL / Parquet
      ↓
Agrégations déterministes
      ↓
Parquet / JSON
      ↓
Analyse IA
      ↓
Dashboard Marketing
      ↓
Décision humaine
```

L'IA transforme le langage en signaux et interprète les résultats.

Le code calcule les chiffres.

Le service Marketing prend les décisions.
