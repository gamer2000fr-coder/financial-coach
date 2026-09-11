# Coach financier — Document technique

> Architecture détaillée, flux, données et API du POC
> Version : 2026-09-11

---

## 1. Vue d'ensemble

```mermaid
flowchart TB
    subgraph Frontend [Frontend React + Vite (port 9898)]
        UI[App.tsx · Logs.tsx · Agents.tsx · Marketing.tsx · Quality.tsx · FeedbackPopup.tsx]
        API[api.ts]
    end
    subgraph Backend [Backend Spring Boot (port 9797)]
        CTRL[Controllers /api/*]
        ORCH[ChatController]
        AI[AIServiceFactory → RemoteAIService / MockAIService]
        AGENTS[Agents: générique + principal + 6 spécialisés<br/>+ agents suivi et analyste marketing]
        METIER[Services métier Java]
        CLOSE[ConversationClosureService + MailService]
        MKT[Marketing: extraction, store, analyse, batch, rapport]
        QLT[Qualité: feedback, contrôles, analyse, batch, rapport]
        LOG[AILogService]
    end
    subgraph Data [Système de fichiers ./data]
        DATAJSON[data.json · products.json · synthese_financier.json]
        BANK[banking_demo_normalized.json]
        CAT[catalogue/*.json + cascade/*.txt]
        TX[transaction/*.json]
        MKTFS[marketing/events/*.jsonl · aggregates/*.json · reports/*.json]
        QLTFS[quality/feedback/*.jsonl · checks/*.jsonl · aggregates/*.json · reports/*.json]
    end
    UI --> API
    API --> CTRL
    CTRL --> ORCH
    CTRL --> CLOSE
    CTRL --> MKT
    ORCH --> AGENTS
    AGENTS --> AI
    AI --> METIER
    METIER --> DATA
    CLOSE --> AI
    CLOSE --> MKT
    CLOSE --> QLT
    MKT --> MKTFS
    QLT --> QLTFS
    ORCH --> LOG
```

**Stack** : Java 17+, Spring Boot (web, validation, RestClient), Jackson ; React 18 + Vite + TypeScript + lucide-react ; pas de base de données (état en mémoire, données en fichiers).

---

## 2. Structure du code

### 2.1 Backend (`src/main/java/com/coach/financier`)
```
controller/
  ChatController          # orchestration d'un message (POST /api/chat)
  FinancialController     # GET /api/financial-summary, /api/banking-data
  LogsController          # GET /api/logs, GET /api/logs/{id}/prompt, /{id}/answer, /stats, DELETE /api/logs
  AgentPromptController   # GET /api/agents, GET/PUT /api/agents/{key}/prompt
  ConversationController  # GET /api/conversations/{sessionId}, POST /{sessionId}/close, POST /{sessionId}/feedback
  MailController          # GET /api/mail/status
  MarketingController     # GET/POST /api/marketing/**
  QualityController       # GET/POST /api/quality/**
  HealthController        # /api/health
service/
  ConversationService          # sessions en mémoire (sessionId → Conversation)
  FinancialAnalysisService     # calcul de la synthèse financière (analyze())
  FinancialSynthesisStore      # lecture de synthese_financier.json (FS puis classpath)
  DataRequestService           # catalogue data.json + fetch des fichiers + cascade + whitelist restreinte
  ProductCatalogueService      # lecture products.json + filtrage produits compatible
  ProductUrlIndex              # index id → URL officielle des produits (whitelist anti-invention)
  ProjectProductMappingService # mapping déterministe ProjectType → ProductFamily
  CreditSimulationService      # calcul déterministe de mensualité (TAEG)
  AILogService                 # tampon en mémoire des traces (500 max)
  AgentPromptStore             # édition prompts agents (./agent/<file> + copie classpath)
  ConversationClosureService   # FIN DE CONVERSATION : dossier de suivi conseiller (+ événements marketing)
  EmailAttachmentBuilder       # brouillon d'email client → pièce jointe (.eml/.html/.txt)
  UrlLinkRenderer              # rendu/litage des liens [URL|nom|url] (texte + HTML)
  MailService                  # envoi SMTP (multipart) + diagnostic d'indisponibilité
  AnonymousIdService           # pseudonymisation client (SHA-256 salé)
  MarketingEventStore          # événements JSONL par jour (append + dédoublonnage)
  MarketingExtractionService   # brouillons IA → événements enrichis (PII masquées)
  MarketingAnalyticsService    # agrégats DÉTERMINISTES (KPI, scores, tendances, cross-sell)
  MarketingBatchService        # batch quotidien (fichiers d'agrégats + rapport), idempotent
  MarketingReportStore         # lecture/écriture des rapports JSON
  MarketingReportService       # génération du rapport via l'agent analyste marketing
  MarketingDemoDataService     # jeu de démonstration déterministe (demo=true)
  JsonlFiles                   # utilitaires JSONL partagés (un fichier par jour, lignes invalides comptées)
  QualityFeedbackService       # pop-in : enregistrement du feedback (facultatif, idempotent, jamais bloquant)
  QualityFeedbackStore         # feedbacks JSONL + déduplication par feedbackId ET par session
  QualityCheckStore            # contrôles JSONL (un événement par contrôle exécuté, ids déterministes)
  CoachQualityCheckService     # contrôles AUTOMATIQUES de conformité (6 implémentés)
  QualityAnalyticsService      # agrégats DÉTERMINISTES satisfaction + conformité + croisement
  QualityBatchService          # batch quotidien (agrégat consolidé + fichiers par section + rapport)
  QualityReportService/Store   # rapport IA qualité (agent qualite_coach_client.txt)
  QualityDemoDataService       # avis + contrôles de démonstration (source=DEMO)
repository/
  BankingDataRepository        # charge banking_demo_normalized.json (FS puis classpath)
ai/
  AIService (interface)        # classifyUserRequest, classifyIntent, answer, summarizeConversation, analyzeMarketing
  RemoteAIService (abstrait)   # implémentation LLM réelle (OpenAI/DeepSeek)
  OpenAIService / DeepSeekService
  MockAIService                # mode démo (déterministe, sans réseau)
  AIServiceFactory             # sélection GPT / DEEPSEEK / MOCK
  AgentFiles                   # agents.json + prompts système par agent (./agent puis classpath)
config/
  JacksonConfig  WebConfig  MarketingProperties  QualityProperties
model/
  AIModels, ChatModels, FinancialSummary, BankingModels, ConversationModels
  IntentClassification, CurrentProject, ProjectType, FinancialIntent, AgentDefinition,
  ProductFamily, ConfidenceLevel, BankProduct, CreditSimulation(Request), LogEntry
  SuiviModels, MarketingModels, QualityModels
```

### 2.2 Rôles des services

| Service | Rôle |
|---|---|
| `ChatController` | Orchestrateur : comprend → sélectionne l'agent → filtre → fait répondre → journalise |
| `ConversationService` | Sessions en mémoire (création, messages, résumé, projet courant) |
| `FinancialAnalysisService` | Calcule les agrégats (revenus, dépenses, soldes, taux 3 mois) |
| `DataRequestService` | Catalogue + accès fichiers (whitelist, cascade, restriction) |
| `ProjectProductMappingService` | **Règle métier** type de projet → familles autorisées |
| `ProductCatalogueService` | Charge les produits (`products.json`) et filtre (famille + montant) |
| `CreditSimulationService` | Mensualité déterministe si montant/durée/TAEG fournis |
| `AILogService` | Journal des appels IA (consultable par l'UI) |
| `AgentPromptStore` | Édition des prompts d'agents (page « Agents ») + copie classpath |
| `AgentFiles` | Lecture de `agents.json` et du prompt système de l'agent actif |
| `ConversationClosureService` | **Fin de conversation** : construit le dossier de suivi (agent `suivi.txt`), valide les produits/URLs, envoie **un seul** email au conseiller avec le brouillon client en pièce jointe, journalise la tentative, puis persiste les événements marketing |
| `EmailAttachmentBuilder` / `UrlLinkRenderer` | Pièce jointe (`.eml`/`.html`/`.txt`) et rendu des liens `[URL|nom|url]` |
| `MailService` | Envoi SMTP multipart + `unavailabilityReason()` / `describeTarget()` (origine des erreurs) |
| `MarketingAnalyticsService` | **Tous les calculs** du module marketing (KPI, scores, taux, tendances, cross-sell) |
| `MarketingEventStore` | Stockage file : JSONL par jour, dédoublonné, tolérant aux lignes invalides |
| `MarketingBatchService` | Agrégats quotidiens en fichiers + rapport (réexécutable sans doublon) |
| `MarketingReportService` | Rapport IA du jour (`agent/marketing.txt`) à partir des agrégats déjà calculés |
| `MarketingDemoDataService` | Jeu de démonstration déterministe (`demo=true`), pour la page Marketing |
| `QualityFeedbackService` / `QualityFeedbackStore` | Feedback de la pop-in : facultatif, idempotent (une seule réponse par conversation), stockage anonymisé |
| `CoachQualityCheckService` | Contrôles **automatiques** de conformité : exécutés à chaque clôture, indépendants de la satisfaction |
| `QualityAnalyticsService` | **Tous les calculs** du module Qualité (satisfaction, conformité, croisement) |
| `QualityBatchService` / `QualityReportService` | Batch quotidien + rapport IA à partir des agrégats calculés |

---

## 3. Données (fichiers)

Tous les fichiers sont lus **depuis le système de fichiers `./data`** (racine du projet), avec repli classpath.

| Fichier | Contenu |
|---|---|
| `data.json` | Catalogue `[{path, description}]` envoyé à l'IA (hors cascade, auto-gérée) |
| `banking_demo_normalized.json` | Comptes, mois 07/2025→08/2026, épargne, crédits |
| `catalogue/products.json` | **52 produits** machine : id, name, family, allowedProjectTypes, min/max, durées, taeg |
| `catalogue/*.json` | Fiches produits pédagogiques (credit_conso, credit_immo, epargne, assurances…) |
| `catalogue/cascade/*.txt` | Arbres de décision produit (attachés à la volée à la fiche parente) |
| `synthese_financier.json` | Synthèse mensuelle + globale |
| `transaction/transactions_YYYY_MM.json` | Transactions mensuelles |
| `agent/agents.json` | Déclaration des agents `[{id, libelle, theme, prompt, data[]}]` |
| `agent/*.txt` | Prompts : `generic.txt` (gabarit), `principal.txt` (agent principal), `classifieur.txt`, `suivi.txt`, `marketing.txt`, 6 prompts spécialisés (+ copies dans `src/main/resources/agent`) |
| `marketing/events/marketing_events_<date>.jsonl` | Événements marketing (1 par ligne, append-only, dédoublonnés par `eventId`) |
| `marketing/aggregates/*.json` | Agrégats figés du jour (batch) |
| `marketing/reports/marketing_report_<date>.json` | Rapport IA du jour |
| `quality/feedback/coach_feedback_<date>.jsonl` | Avis clients (note, motifs, commentaire nettoyé) |
| `quality/checks/coach_quality_checks_<date>.jsonl` | Un événement par contrôle exécuté (détecté ou non) |
| `quality/aggregates/coach_quality_daily_<date>.json` | Agrégat consolidé du jour (+ fichiers par section) |
| `quality/reports/coach_quality_report_<date>.json` | Rapport IA qualité du jour |

> Les **fiches produits** (`catalogue/*.json`) documentent des familles via la table `catalogueDocFamilies` (ex. `credit_immo` → MORTGAGE/HOME_IMPROVEMENT_LOAN). C'est ce qui permet la **restriction du catalogue** en contexte financement.

---

## 4. Configuration (`application.yml`)

```yaml
server.port: ${SERVER_PORT:9797}
app.data.dir: ${DATA_DIR:./data}
app.data.banking-file: ${BANKING_FILE:./data/banking_demo_normalized.json}
app.ai.openai.model: ${OPENAI_MODEL:gpt-4o-mini}
app.ai.deepseek.base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
app.ai.deepseek.api-key: ${DEEPSEEK_API_KEY:}
app.ai.deepseek.model: ${DEEPSEEK_MODEL:deepseek-chat}
app.ai.synthesis-file: ${SYNTHESIS_FILE:./data/synthese_financier.json}
cascade: true          # activation de la jointure des fichiers cascade
# --- Fin de conversation (dossier de suivi) ---
app.advisor.name: ${ADVISOR_NAME:Votre conseiller}
app.advisor.email: ${ADVISOR_EMAIL:<MAIL_USERNAME>}   # SEUL destinataire automatique
app.customer.name: ${CUSTOMER_NAME:}
app.suivi.attachment-format: ${SUIVI_ATTACHMENT_FORMAT:eml}      # txt | html | eml
app.suivi.advisor-appointment-url: ${ADVISOR_APPOINTMENT_URL:…}  # lien de RDV du brouillon client
app.suivi.advisor-mail-html: ${SUIVI_ADVISOR_MAIL_HTML:true}
app.mail.enabled: ${MAIL_ENABLED:true}
app.mail.from: ${MAIL_FROM:${MAIL_USERNAME:}}
spring.mail.host: ${MAIL_HOST:smtp.gmail.com}
spring.mail.port: ${MAIL_PORT:587}
spring.mail.username: ${MAIL_USERNAME:…}
spring.mail.password: ${CLE_GOOGLE_COACH_FINANCIER:}             # mot de passe d'application Gmail
# --- Module Marketing Intelligence (fichiers, sans base de données) ---
app.marketing.enabled: ${MARKETING_ENABLED:true}
app.marketing.demo-mode: ${MARKETING_DEMO_MODE:false}
app.marketing.dir: ${MARKETING_DIR:./data/marketing}
app.marketing.hash-salt: ${MARKETING_HASH_SALT:…}
app.marketing.amount-bounds: ${MARKETING_AMOUNT_BOUNDS:2000,5000,10000,15000,30000}
app.marketing.extractor-version / prompt-version: ${…}
app.marketing.score.*: ${…}                                      # poids du score d'intérêt
# --- Module Qualité & Satisfaction (fichiers, sans base de données) ---
app.quality.enabled: ${QUALITY_ENABLED:true}
app.quality.demo-mode: ${QUALITY_DEMO_MODE:false}
app.quality.dir: ${QUALITY_DIR:./data/quality}
app.quality.hash-salt: ${QUALITY_HASH_SALT:…}
app.quality.comment-max-length: ${QUALITY_COMMENT_MAX_LENGTH:1000}
app.quality.max-comments-to-analyze: ${QUALITY_MAX_COMMENTS:30}
app.quality.sufficient-sample-size: ${QUALITY_SAMPLE_SIZE:10}
app.quality.prompt-version: ${QUALITY_PROMPT_VERSION:quality-report-v1}
app.quality.checks: ${QUALITY_CHECKS:}                            # vide = les 6 contrôles implémentés
app.quality.severity.*: ${…}                                      # sévérité par contrôle (§21)
```

- Clés API : `OPENAI_API_KEY`, `DEEPSEEK_API_KEY` — **aucune clé en dur** (variables d'environnement).
- CORS (`WebConfig`) : `/api/**` avec `allowedOriginPatterns("*")` (dev : localhost + IP LAN / accès réseau), méthodes GET/POST/PUT/DELETE/OPTIONS, headers autorisés.

---

## 5. API REST

| Méthode | URL | Rôle |
|---|---|---|
| POST | `/api/chat` | Envoyer un message (voir §6) |
| GET | `/api/financial-summary` | Synthèse financière (carte « Vue d'ensemble ») |
| GET | `/api/banking-data` | Données bancaires brutes (non utilisé par l'UI) |
| GET | `/api/logs` | Liste des traces IA (polling 2 s) |
| GET | `/api/logs/{id}/prompt` | Prompt envoyé (sans données jointes) |
| GET | `/api/logs/{id}/answer` | Réponse brute de l'IA pour une trace |
| GET | `/api/logs/stats` | Compteur de traces |
| DELETE | `/api/logs` | Vider les logs |
| GET | `/api/conversations/{sessionId}` | Historique complet d'une conversation `{sessionId, summary, messages[]}` |
| POST | `/api/conversations/{sessionId}/close` | **Fin de conversation** : dossier de suivi + email au conseiller (body optionnel `{advisorEmail, advisorName, attachmentFormat, send, provider}` ; `send=false` = dry-run) |
| GET | `/api/mail/status` | État de l'envoi mail `{enabled, available, from, target, reason}` |
| GET | `/api/agents` | Liste des agents éditables `[{key, libelle, file}]` (dont `suivi` et `marketing`) |
| GET/PUT | `/api/agents/{key}/prompt` | Lire / écrire le prompt d'un agent |
| GET | `/api/marketing/status`, `/overview`, `/products`, `/products/{id}`, `/projects`, `/trends`, `/rejections`, `/cross-sell`, `/unmet-needs`, `/missing-information`, `/reports/daily`, `/export/products.csv` | **Module Marketing** : lecture (paramètres `period=today\|yesterday\|7d\|30d\|custom`, `from`, `to`, filtres produit/famille/projet/événement) |
| POST | `/api/marketing/reports/daily/regenerate`, `/batch`, `/demo-data` | Génération du rapport IA, batch quotidien (agrégats + rapport), jeu de démonstration |
| POST | `/api/conversations/{sessionId}/feedback` | **Pop-in de satisfaction** : `{rating, selectedReasons[], comment}` — facultatif, idempotent par session, jamais bloquant |
| GET | `/api/quality/status`, `/overview`, `/ratings`, `/issues`, `/feedback-categories`, `/trends`, `/report`, `/export/satisfaction.csv` | **Module Qualité** : satisfaction et conformité **séparées** (paramètres `period=today\|yesterday\|7d\|30d\|custom`, `from`, `to`, `rating`, `checkType`, `severity`) |
| POST | `/api/quality/report/regenerate`, `/batch`, `/demo-data` | Rapport IA, batch quotidien, jeu de démonstration |
| GET | `/api/health` | Healthcheck (expose le fournisseur par défaut) |

### Exemple — POST /api/chat
```json
// Requête
{ "sessionId": "web-1725...", "message": "Quel crédit pour mon cas ?",
  "provider": "DEEPSEEK", "disableOutOfScopeGuard": false }

// Réponse
{ "sessionId": "...", "provider": "DEEPSEEK",
  "category": "CREDIT", "inScope": true,
  "status": "ANSWER",
  "answer": "Pour l'achat de votre Clio…",
  "financialSummary": { "...": "..." },
  "conversationSummary": "...",
  "agent": "Crédit à la consommation" }
```

---

## 6. Flux détaillé d'un message (ChatController)

### 6.1 Diagramme de séquence backend

```mermaid
sequenceDiagram
    autonumber
    participant UI
    participant CC as ChatController
    participant CS as ConversationService
    participant AI as AIService (classifyIntent)
    participant FA as FinancialAnalysisService
    participant FM as ProjectProductMappingService
    participant PC as ProductCatalogueService
    participant AF as AgentFiles (agents.json + prompts)
    participant DRS as DataRequestService
    participant AI2 as AIService (answer)
    participant LG as AILogService

    UI->>CC: POST /chat {message}
    CC->>CS: getOrCreate(sessionId)
    CC->>CS: addMessage("user", message)
    alt guard activé
        CC->>AI: classifyIntent(message, currentProjectText)
        AI-->>CC: IntentClassification
    else guard désactivé
        CC->>CC: IntentClassification par défaut (inScope=true)
    end

    alt out-of-scope
        CC-->>UI: réponse hors-sujet (status=ANSWER, inScope=false)
    else
        CC->>CC: updateCurrentProject(session)  // projet courant
        CC->>FA: analyze()
        CC->>CC: buildExistingCredits()          // engagements actuels
        CC->>CC: requiresProducts(intent)?
        alt financement && projet inconnu/LOW
            CC-->>UI: question de clarification (pas de produit)
        else
            CC->>FM: getAllowedFamilies(projectType)
            CC->>PC: findCompatible(type, montant) → compatibleProducts
            CC->>AF: selectAgentTheme → AgentFiles.agentFor(theme)
            AF-->>CC: agent + libellé + données dédiées (fiche + cascade)
            CC->>CC: restreint le catalogue visible + allowedPaths
            CC->>CC: injecte données agent dans providedData
            CC->>CC: construit debug ([INTENT]/[PRODUCT_FILTER]/[COACH])
            CC->>AI2: answer(... agent/prompt spécialisé, currentProject/existingCredits/compatibleProducts ...)
            loop tant que NEED_DATA (max 3)
                CC->>DRS: fetch(paths, allowedPaths)   // whitelist restreinte
                DRS-->>CC: {description, data} (+ cascade éventuelle)
                CC->>AI2: answer(... avec données ajoutées ...)
            end
            CC->>LG: log(id, dataSent, charCount, status, agent, requestedData, prompt, debug)
            CC->>CS: addMessage("assistant", answer)
            CC-->>UI: ChatResponse (avec agent)
        end
    end
```

### 6.2 Contenu du payload envoyé au coach

`RemoteAIService.answer` construit un payload dont les clés utiles sont :
- `customerMessage` ; `classification` ;
- `financialSummary` : agrégats (synthèse) ;
- `bankingData` : **catalogue de données restreint** (contexte financement) ;
- `additionalData` :
  - `providedData` : fichiers déjà fournis `[{description, data}]` (synthèse + **données dédiées de l'agent actif**) ;
  - `currentProject` : `{type, object, amount, currency}` (projet courant) ;
  - `existingCredits` : engagements actuels `[{type, label, monthlyPayment}]` ;
  - `compatibleProducts` : produits filtrés (compact) `[{id, name, family, min/max, durées, taeg}]` ;
  - `agent` / `agentLibelle` : agent actif (pour le prompt système et les logs) ;
- `conversationHistory`.

> Le **prompt système** utilisé est celui de l'agent actif (gabarit générique + principal + spécialisé), choisi par `ChatController` (`selectAgentTheme`) puis chargé par `AgentFiles.systemPromptFor(theme)` — et non plus un prompt unique fixe.

### 6.3 Boucle NEED_DATA
- L'IA répond `ANSWER`, ou `NEED_DATA` + `dataRequest.paths` (chemins EXACTS du catalogue).
- Le backend lit les fichiers demandés **si et seulement s'ils sont dans la whitelist autorisée** (`allowedCatalogPaths`), puis re-appelle le coach. **Maximum 3 itérations consécutives** ; sinon réponse de repli.

### 6.4 Restriction du catalogue (structurelle)
En contexte financement (projet connu) :
1. `allowedFamilies = mappingService.getAllowedFamilies(projectType)` ;
2. pour chaque entrée du catalogue : hors `/data/catalogue/` → **visible** ; sinon visible si `familles documentées du fichier ∩ allowedFamilies ≠ ∅` ;
3. les chemins retenus constituent `allowedCatalogPaths` (whitelist de `fetch`).

Ainsi `credit_immo.json` est **structurellement inaccessible** pour un projet véhicule : il est absent du catalogue présenté **et** de la whitelist.

---

## 7. Flux de classification d'intention

```mermaid
flowchart LR
    A[Message + CURRENT_PROJECT] --> B[Prompt classifieur.txt]
    B --> C[LLM / MOCK]
    C --> D[IntentClassification JSON]
    D --> E[intent, projectType, object, amount, currency,<br/>refersToCurrentProject, projectChanged, confidence, reason]
```

- **RemoteAIService.classifyIntent** : appelle le LLM avec `agent/classifieur.txt` et parse le JSON dans `IntentClassification` (tolérant aux champs inconnus).
- **MockAIService.classifyIntent** : heuristique par mots-clés (sans réseau) pour le mode démo.
- **Règles backend** : `needsClarification()` quand `projectType == UNKNOWN` ou `confidence == LOW` ; `requiresProducts()` vrai pour `FINANCING_REQUEST` et `PRODUCT_INFORMATION`.
- **Sélection d'agent** (déterministe, `ChatController`) : pour les intentions routables (`FINANCING_REQUEST`, `PRODUCT_INFORMATION`, `CREDIT_INFORMATION`), on détecte un thème explicite (assurance, épargne/livret/PEA, crédit immo/conso) dans le message ; sinon on déduit l'agent du **type de projet** (immo → `credit_immo`, épargne/placement → `epargne`, assurance → sous-thème auto/habitation/emprunteur, conso/travaux/véhicule → `credit_conso`) ; défaut : agent générique.

---

## 8. Calculs déterministes

### 8.1 Synthèse financière (`FinancialAnalysisService`)
- Regroupe transactions par mois (07/2025→08/2026) ;
- **Exclut** des revenus/dépenses les virements internes d'épargne :
  - dépenses exclues : `LOGITEL` / `VERS COMPTE EPARGNE` ;
  - revenus exclus : `VIR RECU` + `MARTIN` ;
- Indicateurs : revenus/dépenses moyens, épargne, solde courant (dernier `nouveau_solde`), taux d'endettement, **taux d'épargne sur les 3 derniers mois entiers** (Σ épargne / Σ revenus ; si le dernier mois de données est le mois courant partiel, on recule d'un mois) ;
- `emptySummary()` si aucune transaction.

### 8.2 Simulation de crédit (`CreditSimulationService`)
- Annuité constante : `M = C·r / (1 − (1+r)^−n)` avec `r = TAEG/12` ;
- Retourne `null` si montant, durée ou TAEG **absent** (on n'invente jamais un taux) ;
- Pour un TAEG = 0 : `M = C/n`, coût total = 0.

---

## 9. Flux des prompts & agents

### 9.1 Modèle « agents »
- `agent/agents.json` déclare **7 agents** : générique (thème `generic`), crédit conso, crédit immo, épargne, assurance auto, assurance habitation, assurance emprunteur. Chaque agent : `{id, libelle, theme, prompt, data[]}`.
- `agent/generic.txt` : **gabarit** du prompt système (règles du coach, clés du payload, format de réponse).
- `agent/principal.txt` : contenu de l'**agent principal**.
- 6 prompts spécialisés (`credit-conso.txt`, `credit-immo.txt`, `epargne.txt`, `assurance-auto.txt`, …) : expertise du thème + la fiche produit est fournie via `data`.

### 9.2 Construction du prompt système
À chaque appel, `AgentFiles.systemPromptFor(theme)` :
1. charge **toujours** `generic.txt` (gabarit) ;
2. remplace la balise `[agent_principal]` par le contenu de `principal.txt` ;
3. remplace la balise `[agent]` par le prompt de l'agent spécialisé actif (vide si l'agent actif est le générique lui-même, pour éviter une inclusion récursive).

`AgentFiles.mainSystemPrompt()` = `systemPromptFor(GENERIC_THEME)` (compatibilité).

### 9.3 Lecture / écriture
- Lecture : `./agent` d'abord, puis classpath (`src/main/resources/agent`), puis texte par défaut.
- Les fichiers sont **relus à chaque appel IA** → une sauvegarde est prise en compte immédiatement, sans redémarrage.
- Écriture (page Agents) : `AgentPromptStore.write` met à jour `agent/<file>` **et** la copie classpath.

### 9.4 Agents hors conversation (éditables mais non sélectionnables comme coach)

| Clé page Agents | Fichier | Utilisé par | Entrée / sortie |
|---|---|---|---|
| `suivi` | `suivi.txt` | `ConversationClosureService` | Contexte de la conversation → `SuiviResult` (résumé, produits d'intérêt, brouillon client, `marketingEvents`) |
| `marketing` | `marketing.txt` | `MarketingReportService` | Agrégats **déjà calculés** → `MarketingReport` (interprétation rédactionnelle) |
| `qualite` | `qualite_coach_client.txt` | `QualityReportService` | Agrégats de satisfaction **et** de conformité + commentaires anonymisés → `QualityReport` |

Ils ne figurent **pas** dans `agents.json` (donc jamais sélectionnables comme agent de coach) mais apparaissent dans `AgentPromptStore.entries()` après « Agent principal » (`SUIVI_KEY`, `MARKETING_KEY`, `QUALITY_KEY`), et sont éditables dans la page **Agents**.

### Cascade produit
Quand l'IA demande un JSON `/data/catalogue/*.json` et que `cascade=true`, `DataRequestService.fetch` joint automatiquement `/data/catalogue/cascade/<même_nom>.txt` (arbres de décision) en plus de la fiche. Les données de chaque agent (`data[]` = fiche + cascade) sont par ailleurs injectées **d'office** dans `providedData`.

---

## 10. Journalisation des appels IA (Logs)

- `LogEntry` : `id, timestamp, sessionId, clientMessage, dataSent[], historyCount, charCount, status, agent, requestedData[], prompt(@JsonIgnore), debug, answer(@JsonIgnore)`.
- `AILogService` : tampon **500** traces, en mémoire, `log(...)`, `latest()`, `promptOf(id)`, `answerOf(id)`, `clear()`.
- `charCount` = longueur du prompt système + payload utilisateur JSON (compté côté `ChatController`).
- `debug` = bloc `[INTENT] / [PRODUCT_FILTER] / [COACH]` généré par `buildDebugLog` (classification + familles autorisées + compteurs catalogue avant/après + nb produits compatibles + nb crédits existants + agent actif). Aucune donnée bancaire sensible.
- **Trace de clôture (`[SUIVI]`)** : `ConversationClosureService` écrit une trace par clôture — `clientMessage` = « Clôture de conversation — dossier de suivi », `agent` = « Agent de suivi (suivi.txt) », statut `ANSWER` (ou `ERROR` si l'appel IA a échoué, tracé **avant** de relancer l'erreur), `debug` = provider, compteurs HIGH/MEDIUM/LOW/REJECTED, format/nom de la pièce jointe, **`mailStatus`, `mailSent`, `mailTarget`, `mailError`**, conseiller, warnings, **`marketingEvents=N`**. C'est là qu'on lit l'**origine** d'un mail non envoyé (service indisponible, expéditeur/mot de passe manquant, ou exception SMTP + cause racine).
- Frontend : polling `GET /api/logs` toutes les 2 s ; boutons dépliables « Voir le prompt » (lazy `GET /api/logs/{id}/prompt`), « Voir le filtrage » (`debug` embarqué), « Voir la réponse » (lazy `GET /api/logs/{id}/answer`) et « Historique » (lazy `GET /api/conversations/{sessionId}`).

---

## 11. Frontend

### 11.1 Structure
```
frontend/src/
  main.tsx       # routage par hash : #/logs, #/agents, #/marketing, #/quality, sinon App
  App.tsx        # page coach (chat + vue d'ensemble + réglages avancés/audio + bouton de clôture + pop-in)
  FeedbackPopup.tsx # pop-in de satisfaction de fin de conversation (note, motifs, commentaire, Passer)
  Logs.tsx       # page logs (polling, prompt/filtrage/réponse/historique)
  Agents.tsx     # page édition des prompts d'agents (dont suivi, marketing et qualité)
  Marketing.tsx  # page marketing (KPI, produits, projets, refus, rapport IA, CSV)
  Quality.tsx    # page qualité & satisfaction (satisfaction, conformité, croisement, rapport IA, CSV)
  types.ts       # types partagés (chat, logs, agents, marketing)
  types.quality.ts # types du module Qualité
  api.ts         # client API (fetch, API_BASE_URL dynamique) + fonctions marketing et qualité
  styles.css     # classes préfixées (logs-*, agent-*, mkt-*, qlt-*)
  vite-env.d.ts  # référence vite/client
```

### 11.2 Flux API frontend

```mermaid
flowchart LR
    A[App] --> B[fetchFinancialSummary]
    A --> C[sendChat]
    A --> F[closeConversation]
    A --> Q[sendConversationFeedback]
    L[Logs] --> D[fetchLogs / clearLogs / fetchLogPrompt / fetchLogAnswer / fetchConversation]
    G[Agents] --> E[fetchAgents / fetchAgentPrompt / saveAgentPrompt]
    M[Marketing] --> N[fetchMarketingOverview / fetchMarketingProduct / fetchMarketingReport / regenerateMarketingReport / generateMarketingDemoData]
    S[Quality] --> T[fetchQualityOverview / fetchQualityReport / runQualityBatch / generateQualityDemoData / export CSV]
```

### 11.3 Points notables
- `API_BASE_URL` dynamique : `localhost` → `http://localhost:9797/api` ; accès par IP → `http://<hostname>:9797/api` (surchargeable par `VITE_API_BASE_URL`).
- Le type `AiLog` reflète `LogEntry` (avec `debug?`, `agent`) ; `ChatResponse` expose `agent`.
- `FinancialSummary` côté TS reflète le record Java (dont `savingsToIncomeRatio3Months`, `savingsRatePeriodLabel`) ;
- `formatPercent` (2 décimales fr-FR) pour le taux d'épargne ; `formatMoneyCents` pour le solde ;
- Rendu **Markdown léger** des réponses (gras `**`, italique `*`, code) via segmentation React ; texte nettoyé avant synthèse vocale ;
- **Audio** : micro 🎤 dictée et lecture vocale 🔊 (Web Speech API), activables via le réglage « Audio » du panneau « Avancé » ;
- Interrupteur « Avancé » : masque/affiche fournisseur IA (GPT/DeepSeek/Mock), garde-fou hors-sujet, réponses vocales, accès Logs & Agents (onglets séparés).
- Fournisseur IA par défaut côté UI : **DeepSeek** (préférence mémorisée en localStorage).

---

## 12. Mode démo (MOCK) vs fournisseurs réels

| Point | MOCK (défaut backend) | GPT / DeepSeek |
|---|---|---|
| Classification d'intention | Heuristique mots-clés | LLM via `classifieur.txt` |
| Réponse coach | Message générique (mode démo) | LLM via le prompt de l'agent actif (`generic.txt` + `principal.txt` + spécialisé) |
| Synthèse de fin de conversation | **Déterministe** : niveaux d'intérêt déduits des messages (HIGH si le client cite le produit, MEDIUM si le coach, LOW sinon, REJECTED si refus explicite) + brouillon client | LLM via `suivi.txt` |
| Rapport marketing | **Déterministe** : rapport construit à partir des agrégats | LLM via `marketing.txt` (interprétation) |
| Rapport qualité | **Déterministe** : sépare satisfaction et conformité, signale les règles conformes frustrantes | LLM via `qualite_coach_client.txt` |
| Réseau / clé API | Aucun | Requis (`OPENAI_API_KEY`, `DEEPSEEK_API_KEY`) |
| Simulation / calculs / statistiques | Identiques (Java) | Identiques (Java) |

> Le fournisseur est **choisi dans l'IHM** et transmis à chaque appel (`provider`) : échanges avec l'IA **et** clôture de conversation. Le backend ne le lit plus dans `application.yml` ; il ne retombe sur `MOCK` que si aucun fournisseur n'est transmis par l'appelant.

---

## 13. Démarrage & variables d'environnement

Backend :
```powershell
$env:DEEPSEEK_MODEL = "deepseek-chat"
$env:DEEPSEEK_API_KEY = "sk-..."  # clé DeepSeek (ou OPENAI_API_KEY pour GPT)
# Fin de conversation (mail) — mot de passe d'APPLICATION Gmail
$env:MAIL_USERNAME = "coach.financier.pay@gmail.com"
$env:CLE_GOOGLE_COACH_FINANCIER = "xxxx xxxx xxxx xxxx"
$env:ADVISOR_EMAIL = "conseiller@agence.fr"   # SEUL destinataire automatique
# Marketing (facultatif — valeurs par défaut dans application.yml)
$env:MARKETING_ENABLED = "true"
$env:MARKETING_DIR = "./data/marketing"
$env:MARKETING_HASH_SALT = "sel-de-poc"
# lancer l'app Spring Boot (IDE ou mvnw spring-boot:run) → http://localhost:9797
```
Frontend :
```powershell
cd frontend; npm install; npm run dev   # http://localhost:9898 (host 0.0.0.0 → IP LAN)
```
`VITE_API_BASE_URL` (défaut dynamique : `http://localhost:9797/api`, ou `http://<ip>:9797/api` quand on accède par IP) ; CORS ouvert à toutes origines en dev (localhost + IP).

---

## 14. État & limites techniques

- État **en mémoire** : sessions, logs, projet courant → perdus au redémarrage ; données en fichiers relues **au démarrage** (`BankingDataRepository`, `ProductCatalogueService`) → redémarrer le backend après une modification des JSON (sauf les prompts d'agents et `agents.json`, relus à chaque appel).
- Clés API **externalisées** via variables d'environnement (`OPENAI_API_KEY`, `DEEPSEEK_API_KEY`) — aucune clé en dur dans `application.yml`.
- L'écran exige un **contexte sécurisé** (HTTPS ou localhost) pour certaines API navigateur (micro/lecture vocale, `crypto.randomUUID`) ; un repli est prévu pour l'accès HTTP par IP.
- Un seul `currentProject` par session (le POC remplace plutôt que de gérer plusieurs projets).

---

## 15. Fin de conversation — dossier de suivi conseiller

### 15.1 Principe et contrat

**« L'IA prépare → le conseiller contrôle → le conseiller décide → le conseiller envoie. »**

- **Un seul email automatique** : au **conseiller** (`app.advisor.email`).
- Le **brouillon d'email destiné au client** est produit par l'IA puis **joint** au mail conseiller (`.eml` par défaut, sinon `.html`/`.txt`) — il n'est **jamais** envoyé au client par le système.
- Contrat : `POST /api/conversations/{sessionId}/close` → `CloseConversationResponse` (`status`, `advisor`, `attachment`, `summary`, `productsOfInterest`, `preparedCustomerEmail`, `warnings`).

### 15.2 Déclenchement côté IHM

- **Un seul bouton** dans l'en-tête du chat (pastille rouge SG), toujours visible, à deux états selon la case **« Suivi conseiller »** (état persisté `localStorage['financial-coach-suivi']`, **désactivé par défaut**) :
  - **activé** → « Terminer et envoyer au conseiller » : clôture puis vidage du chat ;
  - **désactivé** → « Nouvelle conversation » : simple vidage, **aucun appel réseau**.
- **Fire-and-forget** : l'IHM n'attend pas la réponse (ni chargement, ni bannière de résultat) ; les erreurs sont visibles dans la console et dans l'écran **Logs**.
- Conditions avant appel : **≥ 2 messages client** (`MIN_EXCHANGES_TO_CLOSE`) et **une seule clôture par session** (`closedSessionRef`).

### 15.3 Pipeline backend (`ConversationClosureService.close`)

1. **Session inconnue** (backend redémarré, sessions en mémoire) → **200 `status=NO_CONVERSATION`**, aucun dossier, aucun envoi (+ `log.warn`).
2. **Construction du contexte IA** : `conversationHistory` (rôle/contenu/horodatage), `customerContext` (nom, référence client, synthèse financière — jamais les transactions brutes, projets), `advisorContext`, `products` (candidats enrichis : id, nom, famille, URL officielle via `ProductUrlIndex`), `usefulUrls` (lien de RDV conseiller).
3. **Appel IA de synthèse** : `AIService.summarizeConversation(context, provider)` — prompt système `agent/suivi.txt` (Remote) ou synthèse déterministe (Mock). En sortie : `SuiviResult` (résumé, produits d'intérêt avec niveau HIGH/MEDIUM/LOW/REJECTED, `preparedCustomerEmail`, et **`marketingEvents`**).
4. **Validation Java** (`Validated`) : produits restreints aux candidats réellement présentés ; URLs limitées à la whitelist officielle (les autres neutralisées par `UrlLinkRenderer.sanitize`) ; produits **REJECTED retirés** ainsi que les lignes du brouillon client qui les mentionnent ; mention « pièce jointe » garantie.
5. **Pièce jointe** : `EmailAttachmentBuilder.build(format, preparedCustomerEmail, EmailAddresses(customer, advisor))` → `email_client_prepare_<yyyyMMdd>.<ext>`.
6. **Envoi** : `MailService.sendWithAttachments(...)` vers le **seul** conseiller (sujet + corps HTML/texte rappelant que le client n'est pas destinataire).
7. **Journalisation** (`[SUIVI]`) puis **persistance des événements marketing** (best effort, `marketingEvents=N`).

### 15.4 Un seul destinataire automatique

| Champ | Valeur |
|---|---|
| `To` du mail automatique | `app.advisor.email` (défaut = compte `MAIL_USERNAME`) |
| `From` / `To` du **brouillon joint** | `From` = adresse du conseiller, `To` = `customer.mail` du fichier bancaire (`data/banking_demo_normalized.json`) ; champ laissé **vide** si l'adresse est absente ou invalide |

### 15.5 Statuts renvoyés

| Statut | Signification |
|---|---|
| `SENT` | Dossier construit **et** mail conseiller envoyé |
| `PREPARED` | Dossier construit, envoi désactivé (`send=false`, dry-run) |
| `MAIL_UNAVAILABLE` | Service mail désactivé ou mal configuré (`MailService.unavailabilityReason()`) |
| `SEND_FAILED` | Exception SMTP (le dossier reste renvoyé et journalisé) |
| `NO_CONVERSATION` | Session inconnue (aucun contre-courrier, pas de 404) |

En cas d'**échec de l'appel IA**, la trace `[SUIVI]` est écrite **avant** l'erreur (`status=ERROR`, `mailStatus=AI_FAILED`) puis un **502** est renvoyé — sans quoi l'incident serait invisible.

### 15.6 Observabilité

Voir §10 : une trace par clôture contient `mailStatus`, `mailSent`, `mailTarget`, `mailError` (cause racine SMTP), `advisor`, `warnings` et `marketingEvents=N`, plus le prompt système `suivi.txt` et le `SuiviResult` complet (`answer`).

### 15.7 Configuration

`app.advisor.{name,email}`, `app.customer.name`, `app.suivi.{attachment-format, advisor-appointment-url, advisor-mail-html}`, `spring.mail.*` (`MAIL_USERNAME`, mot de passe d'application `CLE_GOOGLE_COACH_FINANCIER`), `app.mail.{enabled,from,from-name}`. Le fournisseur IA n'a **pas** de valeur par défaut en configuration : il est transmis par l'IHM à chaque appel (`AIServiceFactory.FALLBACK_PROVIDER = MOCK` uniquement en repli technique).

### 15.8 Limites assumées

- Conversations et produit courant **en mémoire** → après redémarrage, la clôture renvoie `NO_CONVERSATION` (comportement assumé, pas une erreur).
- La qualité du dossier repose sur le prompt `agent/suivi.txt` (éditable depuis la page **Agents**).
- Le POC n'envoie jamais en deux temps (relance du conseiller depuis l'IHM) : le conseiller ouvre son mail et transfère le brouillon.

---

## 16. Module Marketing Intelligence (POC, sans base de données)

### 16.1 Principe

« **Le code calcule, l'IA interprète.** » Aucun moteur analytique externe, aucun SGBD : le stockage est fait de **fichiers** (JSONL pour les événements, JSON pour les agrégats et rapports). Parquet a été écarté volontairement (dépendances Hadoop/parquet-mr inutiles à ce stade).

Trois temps :

1. **Extraction** — à chaque clôture de conversation, l'appel IA de suivi renvoie en plus un tableau `marketingEvents` (`SuiviResult.marketingEvents`). `MarketingExtractionService` enrichit ces brouillons (identifiant d'événement, horodatage, client pseudonymisé, versions d'extracteur/prompt, modèle, marqueur `demo`) et masque les données personnelles.
2. **Stockage** — `MarketingEventStore` ajoute une ligne JSONL par événement dans `events/marketing_events_YYYY-MM-DD.jsonl` (dédoublonnage par `eventId`, écriture en `CREATE`/`APPEND`, une ligne invalide est ignorée et comptée).
3. **Agrégation & interprétation** — `MarketingAnalyticsService` calcule les agrégats **à la demande** pour la fenêtre demandée ; `MarketingBatchService` pré-calcule les mêmes agrégats en fichiers quotidiens et `MarketingReportService` fait rédiger le rapport par l'agent `agent/marketing.txt`.

### 16.2 Fichiers créés (`data/marketing`, configurable via `app.marketing.dir`)

| Chemin | Contenu |
|---|---|
| `events/marketing_events_<AAAA-MM-JJ>.jsonl` | 1 événement par ligne (append-only, dédoublonné par `eventId`) |
| `aggregates/marketing_{overview,product_metrics,project_metrics,rejections,cross_sell,unmet_needs,missing_info,series}_<date>.json` | Agrégats figés du jour (batch, écrasement idempotent) |
| `reports/marketing_report_<date>.json` | Rapport IA du jour (résumé, tendances, frictions, opportunités…) |

### 16.3 Schéma d'un événement

```json
{
  "eventId": "…uuid…", "eventType": "PRODUCT_INTEREST", "timestamp": "2026-09-11T10:22:41",
  "sessionId": "…", "anonymousCustomerId": "customer_hash_1f3c…",
  "projectType": "VEHICLE", "projectAmountRange": "10000_15000",
  "productId": "sg_auto_tous_risques", "productName": "Assurance Auto…", "productFamily": "INSURANCE_AUTO",
  "interestLevel": "HIGH", "reasonCategory": "DETAIL_REQUEST", "reason": "[masqué] je veux être rappelé",
  "advisorFollowUpRecommended": true, "confidence": 0.9,
  "extractorVersion": "marketing-events-v1", "promptVersion": "marketing-extractor-v1",
  "model": "MOCK", "createdAt": "2026-09-11T10:22:42", "demo": false
}
```

Types : `PROJECT_DETECTED`, `PRODUCT_RECOMMENDED`, `PRODUCT_INTEREST`, `PRODUCT_REJECTED`, `PRODUCT_COMPARISON`, `SUBSCRIPTION_INTEREST`, `APPOINTMENT_INTEREST`, `ADVISOR_HANDOFF`, `UNMET_NEED`, `MISSING_PRODUCT_INFORMATION`, et les indicateurs qualité (`COACH_*`, comptés séparément, non imputés au client).

### 16.4 Endpoints

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/marketing/status` | État du module (activé, mode démo, jours disponibles, bornes de montant) |
| GET | `/api/marketing/overview` | KPI + produits + projets + séries + tendances |
| GET | `/api/marketing/products`, `/products/{productId}` | Classement produits / détail d'un produit |
| GET | `/api/marketing/projects`, `/trends`, `/rejections`, `/cross-sell`, `/unmet-needs`, `/missing-information` | Vues analytiques |
| GET | `/api/marketing/reports/daily` | Rapport IA (204 si aucun rapport) |
| GET | `/api/marketing/export/products.csv` | Export CSV (`;` + BOM UTF-8) |
| POST | `/api/marketing/reports/daily/regenerate` | Régénérer le rapport (paramètre `provider`) |
| POST | `/api/marketing/batch` | Batch quotidien (agrégats + rapport) |
| POST | `/api/marketing/demo-data` | Jeu de démonstration déterministe (`days`, `sessionsPerDay`) |

Toutes les lectures acceptent `period` (`today`, `yesterday`, `7d`, `30d`, `custom`) avec `from`/`to`, plus `productId`, `productFamily`, `projectType`, `interestLevel`, `eventType`.

### 16.5 Règles de calcul (côté code uniquement)

- **Taux d'intérêt** = sessions intéressées / sessions où le produit a été recommandé, **plafonné à 100 %** ; `null` si le produit n'a jamais été recommandé (aucune division par zéro).
- **Score d'intérêt** = somme pondérée configurable (`app.marketing.score.*`) : recommandation +1, intérêt moyen +2, intérêt fort +3, comparaison +2, intention de souscription +4, demande de RDV +5, refus −5.
- **Évolutions** : période précédente de **même longueur** ; `null` si la base précédente est nulle ou absente.
- **Cross-sell** : nombre de sessions où deux produits sont associés, rapporté au nombre de sessions intéressées par le produit source.
- **Client unique** : `anonymousCustomerId` = SHA-256(sel + identifiant client) tronqué → aucun identifiant en clair sur disque.

### 16.6 Frontend

`Marketing.tsx` (route `#/marketing`, lien `TrendingUp` dans l'en-tête du chat) : en-tête + filtres de période, 7 cartes KPI, histogramme jour par jour, classement des produits, tableau « recommandé vs intérêt », projets, refus, cross-sell, besoins non couverts, informations manquantes, rapport IA (avec avertissement « rapport généré par IA »), tableau triable/filtrable et tiroir de détail produit. Types dans `types.ts`, appels dans `api.ts`, styles `.mkt-*` dans `styles.css`.

### 16.7 Commandes utiles

```powershell
# Batch du jour + rapport IA (MOCK par défaut)
curl -X POST http://localhost:9797/api/marketing/batch
# Jeu de démonstration (30 jours) puis lecture des KPI
curl -X POST "http://localhost:9797/api/marketing/demo-data?days=30&sessionsPerDay=8"
curl "http://localhost:9797/api/marketing/overview?period=30d"
```

### 16.8 Limites assumées

- Les conversations vivent **en mémoire** : une session perdue (redémarrage) ne produit pas d'événement.
- Les événements de démonstration (`demo=true`) et réels cohabitent : le bandeau de la page le signale ; filtrer/supprimer `data/marketing` avant toute lecture sérieuse.
- L'extraction dépend de la qualité du prompt `agent/marketing.txt` (le taux de `confidence` faible est conservé, pas corrigé).

---

## 17. Module Qualité & Satisfaction du Coach IA (POC, sans base de données)

### 17.1 Principe : deux dimensions jamais fusionnées

| Dimension | Question | Source | Calcul |
|---|---|---|---|
| **Satisfaction client** | « Le client a-t-il apprécié son expérience ? » | Note 1 à 5, motifs, commentaire | `QualityAnalyticsService` (déterministe) |
| **Qualité / conformité** | « Le Coach a-t-il correctement fonctionné ? » | Contrôles automatiques | `CoachQualityCheckService` + agrégations |

> **Règle absolue** : une mauvaise note n'est jamais convertie en anomalie du Coach. Une plainte devient une anomalie **uniquement** si un contrôle automatique la confirme.

### 17.2 Fichiers (`data/quality`, configurable via `app.quality.dir`)

| Chemin | Contenu |
|---|---|
| `feedback/coach_feedback_<AAAA-MM-JJ>.jsonl` | 1 avis par ligne (idempotent par `feedbackId` **et** par session) |
| `checks/coach_quality_checks_<AAAA-MM-JJ>.jsonl` | 1 ligne par contrôle **exécuté** (avec `detected`, sévérité, détail) |
| `aggregates/coach_quality_daily_<date>.json` | Agrégat consolidé du jour + fichiers par section (satisfaction, conformité, notes, motifs, thèmes, contrôles, croisement, série) |
| `reports/coach_quality_report_<date>.json` | Rapport IA qualité du jour |

### 17.3 Format d'un avis client

```json
{
  "feedbackId": "fb-37520825-1890-335a-901f-b57ca0751a0b",
  "timestamp": "2026-09-11T09:42:29", "sessionId": "web-…",
  "anonymousCustomerId": "customer_hash_97cd4fd9c04f522a",
  "rating": 2, "customerSelectedReasons": ["TOO_REPETITIVE", "MISSING_INFORMATION"],
  "aiDetectedReasons": [], "comment": "… rappelez-moi au [numéro masqué]",
  "source": "END_CONVERSATION_POPUP", "createdAt": "2026-09-11T09:42:29"
}
```

Motifs (`user`/`client`) : `NOT_ANSWERING_QUESTION`, `HARD_TO_UNDERSTAND`, `TOO_LONG`, `TOO_REPETITIVE`, `PRODUCT_NOT_RELEVANT`, `MISSING_INFORMATION`, `ACTION_NOT_POSSIBLE`, `OTHER`. Les libellés affichés sont séparés des codes (reformulables sans casser l'analytique).

### 17.4 Contrôles automatiques réellement implémentés (§15 à §21)

| Contrôle | Sévérité par défaut | Ce qui est détecté |
|---|---|---|
| `CREDIT_SIMULATION_VIOLATION` | HIGH | Le Coach produit lui-même un chiffrage (mensualité, coût total, intérêts, capacité d'emprunt). Une **redirection** vers le simulateur officiel est conforme (aucune alerte) ; les rappels de crédit **existant** sont hors périmètre |
| `PRODUCT_MISMATCH` | HIGH | Une offre présentée dont la famille n'est autorisée pour **aucun** projet de la conversation (les crédits existants ne sont pas des offres) |
| `INVENTED_URL` | HIGH | URL citée (brute ou `[URL|nom|url]`) absente des fiches officielles et du lien de RDV configuré |
| `UNANSWERED_REQUEST` | MEDIUM | Conversation terminée sur un message client sans réponse, ou appel IA en erreur |
| `MISSING_DATA_NOT_RETRIEVED` | MEDIUM | Données demandées par l'IA (`NEED_DATA`) jamais fournies |
| `EXCESSIVE_REPETITION` | LOW | Phrases reformulées à l'identique (≥ 40 caractères, 2 occurrences) ou salutations répétées — contrôle volontairement prudent |

**Non implémentés** (affichés comme tels, jamais comptés comme « 0 ») : `UNNECESSARY_ADVISOR_REDIRECT`, `UNSUPPORTED_PRODUCT_CLAIM`, `INVENTED_DATA`, `CONVERSATION_CONTEXT_LOST`. La liste exécutée et les sévérités sont **configurables** (`app.quality.checks`, `app.quality.severity.*`).

### 17.5 Règles de calcul (côté code uniquement)

- **Note moyenne**, distribution, avis positifs (≥ 4) / négatifs (≤ 2) / neutres (3), taux de participation = avis / conversations terminées (dénominateur = sessions disposant de contrôles) ; `null` si dénominateur nul.
- **Croisement** satisfaction × conformité : A = satisfait + conforme, B = satisfait + anomalie, C = insatisfait + conforme (**règle correctement appliquée**), D = insatisfait + anomalie (prioritaire). Les notes neutres sont exclues.
- **Évolutions** : période précédente de même longueur ; `null` si la base est absente ou nulle.
- **Thèmes de commentaires** : heuristique locale déterministe (accents normalisés, mots-clés) — **aucun appel IA** ; l'analyse IA des commentaires est en P2 (non activée).
- **Anonymisation** : identifiant client = SHA-256 salé ; emails, téléphones et longues suites de chiffres masqués avant stockage, affichage ou envoi à l'IA.

### 17.6 Endpoints

Voir §5 (`/api/conversations/{sessionId}/feedback` et `/api/quality/**`). Le frontend ne lit **jamais** les fichiers.

### 17.7 Frontend

- `FeedbackPopup.tsx` : pop-in ouverte au clic sur « Terminer et envoyer au conseiller » (suivi actif **et** ≥ 2 échanges) — 5 étoiles, motifs à partir de 3 étoiles ou moins, commentaire facultatif, « Envoyer mon avis » / « Passer ». L'avis part en fire-and-forget **avant** la clôture ; un échec n'empêche rien.
- `Quality.tsx` (route `#/quality`, lien `BadgeCheck` dans l'en-tête) : filtres de période/note/sévérité, KPI satisfaction **et** conformité, distribution des notes, motifs, thèmes de commentaires, table des contrôles, croisement A/B/C/D, rapport IA, export CSV. Réutilise le design system de la page Marketing (`.mkt-*`) — pas de seconde architecture.

### 17.8 Commandes utiles

```powershell
# Jeu de démonstration (avis + contrôles marqués DEMO) puis lecture des indicateurs
curl -X POST "http://localhost:9797/api/quality/demo-data?days=21&reviewsPerDay=5"
curl "http://localhost:9797/api/quality/overview?period=30d"
# Batch du jour (agrégat consolidé + rapport IA) et export CSV
curl -X POST "http://localhost:9797/api/quality/batch?date=2026-09-11"
curl "http://localhost:9797/api/quality/export/satisfaction.csv?period=7d"
```

### 17.9 Limites assumées

- Le taux de participation ne compte que les conversations pour lesquelles des contrôles ont été exécutés (une conversation jamais clôturée n'est pas comptée).
- Les contrôles sont **heuristiques** : ils privilégient la précision à l'exhaustivité (mieux vaut manquer une anomalie que produire un faux positif) ; chaque contrôle est documenté et testé.
- Les avis de démonstration (`source=DEMO`) cohabitent avec les avis réels : le bandeau de la page le signale.
- Le rapport IA n'est qu'une **proposition** : le module ne modifie jamais automatiquement le prompt, les règles métier, les catalogues ou le code.
