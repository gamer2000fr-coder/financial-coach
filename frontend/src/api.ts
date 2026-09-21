import type {
  AiLog,
  AIProvider,
  ChatResponse,
  ConversationClosure,
  ConversationData,
  FinancialSummary,
  MarketingAggregates,
  MarketingPeriod,
  MarketingProductDetail,
  MarketingReport,
  MarketingStatus,
} from './types'
import type {
  QualityAggregates,
  QualityFeedbackRequest,
  QualityFeedbackResponse,
  QualityPeriod,
  QualityReport,
  QualityStatus,
} from './types.quality'
import type {
  AdvisorAggregates,
  AdvisorCandidatesResponse,
  AdvisorDossierView,
  AdvisorFeedbackInput,
  AdvisorFeedbackResponse,
  AdvisorPeriod,
  AdvisorReport,
  AdvisorStatus,
} from './types.advisor'
import type {
  HumanFeedbackView,
  PromptCampaign,
  PromptCampaignDetail,
  PromptCampaignStart,
  PromptThread,
  PromptThreadClosure,
  PromptComparison,
  PromptIteration,
  PromptOptimizationAgents,
  PromotionResult,
  StartCampaignInput,
  ClientQuestionInput,
  ClientTurnView,
  ConversationComparison,
  GeneratedClientBrief,
  GeneratedClientBriefInput,
} from './types.promptopt'
import type { DirectoryDetail, DirectoryList, DirectoryQuery } from './types.directory'

function resolveApiBaseUrl(): string {
  if (typeof window === 'undefined') return 'http://localhost:9797/api'
  const { hostname, protocol } = window.location
  // Accès via l'IP LAN (ex. http://192.168.1.83:5173) → on appelle le backend de cette même IP.
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return 'http://localhost:9797/api'
  }
  return `${protocol}//${hostname}:9797/api`
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? resolveApiBaseUrl()

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(init?.headers ?? {}),
    },
  })

  if (!response.ok) {
    let detail = `Erreur HTTP ${response.status}`
    try {
      const body = await response.json()
      detail = body?.message ?? body?.error ?? detail
    } catch {
      // Keep the generic HTTP error.
    }
    throw new Error(detail)
  }

  return response.json() as Promise<T>
}

export async function fetchFinancialSummary(): Promise<FinancialSummary> {
  return apiFetch<FinancialSummary>('/financial-summary')
}

export async function fetchHealth(): Promise<unknown> {
  return apiFetch('/health')
}

export async function sendChat(
  sessionId: string,
  message: string,
  provider: AIProvider,
  disableOutOfScopeGuard: boolean,
): Promise<ChatResponse> {
  return apiFetch<ChatResponse>('/chat', {
    method: 'POST',
    body: JSON.stringify({ sessionId, message, provider, disableOutOfScopeGuard }),
  })
}

export { API_BASE_URL }

export async function fetchLogs(): Promise<AiLog[]> {
  return apiFetch<AiLog[]>('/logs')
}

export async function fetchLogPrompt(id: number): Promise<string> {
  const data = await apiFetch<{ prompt: string }>(`/logs/${id}/prompt`)
  return data.prompt
}

export async function fetchLogAnswer(id: number): Promise<string> {
  const data = await apiFetch<{ answer: string }>(`/logs/${id}/answer`)
  return data.answer
}

export async function fetchConversation(sessionId: string): Promise<ConversationData> {
  return apiFetch<ConversationData>(`/conversations/${encodeURIComponent(sessionId)}`)
}

/**
 * ANNUAIRE DES CONVERSATIONS (page Centre d'appels) : conversations clôturées, filtrées (période,
 * catégorie, recherche) et triées côté serveur.
 */
export async function fetchConversationDirectory(query: DirectoryQuery): Promise<DirectoryList> {
  const params = new URLSearchParams()
  params.set('days', String(query.days))
  if (query.category) params.set('category', query.category)
  if (query.status) params.set('status', query.status)
  if (query.q) params.set('q', query.q)
  if (query.sort) params.set('sort', query.sort)
  if (query.order) params.set('order', query.order)
  return apiFetch<DirectoryList>(`/conversations/directory?${params.toString()}`)
}

/**
 * Change le STATUT d'avancement d'un dossier (fil de travail du centre d'appels) et renvoie le détail à jour.
 * Un code inconnu est refusé par le backend (aucun statut deviné).
 */
export async function updateDirectoryStatus(
  sessionId: string,
  status: string,
  comment?: string,
): Promise<DirectoryDetail> {
  return apiFetch<DirectoryDetail>(`/conversations/directory/${encodeURIComponent(sessionId)}/status`, {
    method: 'POST',
    body: JSON.stringify({ status, comment }),
  })
}

/** Détail d'une conversation (pop-in) : synthèse conseiller, score expliqué et transcript. */
export async function fetchConversationDirectoryDetail(sessionId: string): Promise<DirectoryDetail> {
  return apiFetch<DirectoryDetail>(`/conversations/directory/${encodeURIComponent(sessionId)}`)
}

export interface CloseConversationOptions {
  /** false = prépare le dossier sans l'envoyer (dry-run). Défaut : true. */
  send?: boolean
  /** Fournisseur IA choisi dans l'IHM (comme pour les échanges). */
  provider?: AIProvider
  /** txt | html | eml */
  attachmentFormat?: string
  /** Destinataire conseiller (sinon config backend). */
  advisorEmail?: string
  advisorName?: string
}

/**
 * Clôture la conversation : génère le dossier de suivi, envoie l'email au CONSEILLER
 * uniquement et joint le brouillon d'email client. Rien n'est envoyé au client.
 */
export async function closeConversation(
  sessionId: string,
  options?: CloseConversationOptions,
): Promise<ConversationClosure> {
  return apiFetch<ConversationClosure>(`/conversations/${encodeURIComponent(sessionId)}/close`, {
    method: 'POST',
    body: JSON.stringify(options ?? {}),
  })
}

export interface AgentEntry {
  key: string
  libelle: string
  file?: string
}

export async function fetchAgents(): Promise<AgentEntry[]> {
  return apiFetch<AgentEntry[]>('/agents')
}

export async function fetchAgentPrompt(key: string): Promise<{ key: string; content: string }> {
  return apiFetch<{ key: string; content: string }>(`/agents/${encodeURIComponent(key)}/prompt`)
}

export async function saveAgentPrompt(
  key: string,
  content: string,
): Promise<{ key: string; content: string }> {
  return apiFetch<{ key: string; content: string }>(`/agents/${encodeURIComponent(key)}/prompt`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

export async function clearLogs(): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/logs`, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) {
    let detail = `Erreur HTTP ${response.status}`
    try {
      const body = await response.json()
      detail = body?.message ?? body?.error ?? detail
    } catch {
      // Keep the generic HTTP error.
    }
    throw new Error(detail)
  }
}

// ------------------------------------------------------------------ Marketing Intelligence (#/marketing)

/** Filtres appliqués côté backend avant agrégation. */
export interface MarketingFilters {
  productId?: string
  productFamily?: string
  projectType?: string
  interestLevel?: string
  eventType?: string
}

function marketingQuery(period: MarketingPeriod, from?: string, to?: string,
                        filters?: MarketingFilters): string {
  const params = new URLSearchParams()
  params.set('period', period)
  if (period === 'custom') {
    if (from) params.set('from', from)
    if (to) params.set('to', to)
  }
  Object.entries(filters ?? {}).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return params.toString()
}

/** Agrégats complets de la période (KPI, produits, projets, refus, cross-sell, tendances). */
export async function fetchMarketingOverview(
  period: MarketingPeriod,
  from?: string,
  to?: string,
  filters?: MarketingFilters,
): Promise<MarketingAggregates> {
  return apiFetch<MarketingAggregates>(`/marketing/overview?${marketingQuery(period, from, to, filters)}`)
}

/** État du module (jours disponibles, mode démo, tranches de montant). */
export async function fetchMarketingStatus(): Promise<MarketingStatus> {
  return apiFetch<MarketingStatus>('/marketing/status')
}

/** Drill-down produit : métriques + refus détaillés + associations. */
export async function fetchMarketingProduct(
  productId: string,
  period: MarketingPeriod,
  from?: string,
  to?: string,
): Promise<MarketingProductDetail> {
  return apiFetch<MarketingProductDetail>(
    `/marketing/products/${encodeURIComponent(productId)}?${marketingQuery(period, from, to)}`,
  )
}

/** Rapport IA du jour demandé (ou le plus récent) ; {@code null} si aucun rapport. */
export async function fetchMarketingReport(date?: string): Promise<MarketingReport | null> {
  const query = date ? `?date=${encodeURIComponent(date)}` : ''
  const response = await fetch(`${API_BASE_URL}/marketing/reports/daily${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 204) return null
  if (!response.ok) throw new Error(`Erreur HTTP ${response.status}`)
  return (await response.json()) as MarketingReport
}

/** Régénère les agrégats et le rapport IA d'une date (idempotent). */
export async function regenerateMarketingReport(date: string, provider?: AIProvider): Promise<unknown> {
  const params = new URLSearchParams({ date })
  if (provider) params.set('provider', provider)
  return apiFetch(`/marketing/reports/daily/regenerate?${params.toString()}`, { method: 'POST' })
}

/** Génère un jeu de données de démonstration (événements marqués demo=true). */
export async function generateMarketingDemoData(
  days = 7,
  sessionsPerDay = 12,
): Promise<{ eventsGenerated: number; eventsWritten: number }> {
  const params = new URLSearchParams({ days: String(days), sessionsPerDay: String(sessionsPerDay) })
  return apiFetch(`/marketing/demo-data?${params.toString()}`, { method: 'POST' })
}

/** URL d'export CSV des métriques produit (aucune donnée personnelle). */
export function marketingProductsCsvUrl(period: MarketingPeriod, from?: string, to?: string): string {
  return `${API_BASE_URL}/marketing/export/products.csv?${marketingQuery(period, from, to)}`
}

// ------------------------------------------------------------------ Qualité & Satisfaction (#/quality)

/** Libellés client des motifs d'insatisfaction (le CODE reste la clé analytique). */
export const QUALITY_REASONS: { code: string; label: string }[] = [
  { code: 'NOT_ANSWERING_QUESTION', label: "La réponse ne répondait pas à ma question" },
  { code: 'HARD_TO_UNDERSTAND', label: 'Les explications étaient difficiles à comprendre' },
  { code: 'TOO_LONG', label: 'Les réponses étaient trop longues' },
  { code: 'TOO_REPETITIVE', label: 'Les réponses étaient trop répétitives' },
  { code: 'PRODUCT_NOT_RELEVANT', label: 'Les produits proposés ne correspondaient pas à mon besoin' },
  { code: 'MISSING_INFORMATION', label: 'Il manquait des informations' },
  { code: 'ACTION_NOT_POSSIBLE', label: "Je n'ai pas pu réaliser l'action souhaitée" },
  { code: 'OTHER', label: 'Autre' },
]

/** Filtres de la page Qualité. */
export interface QualityFilters {
  checkType?: string
  severity?: string
  rating?: number
  reason?: string
}

function qualityQuery(period: QualityPeriod, from?: string, to?: string,
                      filters?: QualityFilters): string {
  const params = new URLSearchParams({ period })
  if (period === 'custom') {
    if (from) params.set('from', from)
    if (to) params.set('to', to)
  }
  Object.entries(filters ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  })
  return params.toString()
}

/**
 * Enregistre l'avis du client (pop-in de fin de conversation). Le backend ne renvoie jamais d'erreur
 * bloquante : un échec d'enregistrement n'empêche pas la clôture de la conversation.
 */
export async function sendConversationFeedback(
  sessionId: string,
  payload: QualityFeedbackRequest,
): Promise<QualityFeedbackResponse> {
  return apiFetch<QualityFeedbackResponse>(
    `/conversations/${encodeURIComponent(sessionId)}/feedback`,
    { method: 'POST', body: JSON.stringify(payload) },
  )
}

/** État du module Qualité (jours disponibles, contrôles réellement implémentés, motifs). */
export async function fetchQualityStatus(): Promise<QualityStatus> {
  return apiFetch<QualityStatus>('/quality/status')
}

/** Agrégats qualité : satisfaction + conformité + croisement + séries + tendances. */
export async function fetchQualityOverview(
  period: QualityPeriod,
  from?: string,
  to?: string,
  filters?: QualityFilters,
): Promise<QualityAggregates> {
  return apiFetch<QualityAggregates>(`/quality/overview?${qualityQuery(period, from, to, filters)}`)
}

/** Rapport IA qualité de la date demandée (ou le plus récent) ; {@code null} si aucun rapport. */
export async function fetchQualityReport(date?: string): Promise<QualityReport | null> {
  const query = date ? `?date=${encodeURIComponent(date)}` : ''
  const response = await fetch(`${API_BASE_URL}/quality/report${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 204) return null
  if (!response.ok) throw new Error(`Erreur HTTP ${response.status}`)
  return (await response.json()) as QualityReport
}

/** Régénère les agrégats (batch) et le rapport IA qualité d'une journée. */
export async function runQualityBatch(date: string): Promise<unknown> {
  const params = new URLSearchParams({ date })
  return apiFetch(`/quality/batch?${params.toString()}`, { method: 'POST' })
}

/** Génère un jeu de démonstration (avis et contrôles marqués source=DEMO). */
export async function generateQualityDemoData(
  days = 14,
  reviewsPerDay = 5,
): Promise<{ days: number; feedbackWritten: number; checksWritten: number }> {
  const params = new URLSearchParams({ days: String(days), reviewsPerDay: String(reviewsPerDay) })
  return apiFetch(`/quality/demo-data?${params.toString()}`, { method: 'POST' })
}

/** URL d'export CSV des statistiques agrégées (jamais des commentaires bruts). */
export function qualitySatisfactionCsvUrl(period: QualityPeriod, from?: string, to?: string): string {
  return `${API_BASE_URL}/quality/export/satisfaction.csv?${qualityQuery(period, from, to)}`
}

// ------------------------------------------------------------------ Feedback Conseiller (#/advisor-feedback)

/** Zones corrigeables par le conseiller (§5 de la spécification). */
export const ADVISOR_AREAS: { code: string; label: string }[] = [
  { code: 'SUMMARY', label: 'Résumé de la conversation' },
  { code: 'CUSTOMER_NEED', label: 'Compréhension du besoin client' },
  { code: 'PROJECT_DETECTION', label: 'Projet détecté' },
  { code: 'PRODUCT_RELEVANCE', label: "Produit proposé / produit d'intérêt" },
  { code: 'INTEREST_LEVEL', label: "Niveau d'intérêt du client" },
  { code: 'NEXT_ACTION', label: 'Suivi conseillé' },
  { code: 'CLIENT_EMAIL', label: 'Email préparé pour le client' },
  { code: 'MISSING_INFORMATION', label: 'Information importante manquante' },
  { code: 'OTHER', label: 'Autre' },
]

/** Motifs structurés (§6). */
export const ADVISOR_REASONS: { code: string; label: string }[] = [
  { code: 'WRONG', label: 'Erreur' },
  { code: 'INCOMPLETE', label: 'Incomplet' },
  { code: 'NOT_RELEVANT', label: 'Non pertinent' },
  { code: 'TOO_VERBOSE', label: 'Trop verbeux' },
  { code: 'TOO_GENERIC', label: 'Trop générique' },
  { code: 'MISSING_CONTEXT', label: 'Contexte manquant' },
  { code: 'WRONG_PRODUCT', label: "Mauvais produit" },
  { code: 'WRONG_INTEREST_LEVEL', label: "Mauvais niveau d'intérêt" },
  { code: 'UNSUPPORTED_RECOMMENDATION', label: 'Recommandation non étayée' },
  { code: 'OTHER', label: 'Autre' },
]

/** Motifs d'un produit jugé non pertinent (§7). */
export const ADVISOR_PRODUCT_REASONS: { code: string; label: string }[] = [
  { code: 'CUSTOMER_NOT_INTERESTED', label: 'Client non intéressé' },
  { code: 'INCOMPATIBLE_WITH_NEED', label: 'Produit incompatible avec le besoin' },
  { code: 'INTEREST_OVERESTIMATED', label: 'Intérêt surestimé' },
  { code: 'MENTION_ONLY', label: 'Produit seulement mentionné' },
  { code: 'OTHER', label: 'Autre' },
]

/** Exploitabilité de l'email préparé (§11). */
export const ADVISOR_EMAIL_ASSESSMENTS: { code: string; label: string }[] = [
  { code: 'READY_TO_USE', label: 'Prêt à utiliser' },
  { code: 'MINOR_EDITS', label: 'Modifications mineures' },
  { code: 'MAJOR_EDITS', label: 'Modifications importantes' },
  { code: 'UNUSABLE', label: 'Non utilisable' },
]

/** Niveaux d'intérêt conservés tels quels (valeur IA et correction conseiller). */
export const ADVISOR_INTEREST_LEVELS = ['HIGH', 'MEDIUM', 'LOW', 'REJECTED']

function advisorQuery(period: AdvisorPeriod, from?: string, to?: string, filters?: AdvisorFilters): string {
  const params = new URLSearchParams({ period })
  if (period === 'custom') {
    if (from) params.set('from', from)
    if (to) params.set('to', to)
  }
  Object.entries(filters ?? {}).forEach(([key, value]) => {
    if (value) params.set(key, value)
  })
  return params.toString()
}

/** Filtres de la page Feedback Conseillers. */
export interface AdvisorFilters {
  assessment?: string
  area?: string
  productId?: string
  emailAssessment?: string
}

/** Enregistre (ou révise) un feedback conseiller — jamais bloquant pour le dossier. */
export async function submitAdvisorFeedback(
  payload: AdvisorFeedbackInput,
): Promise<AdvisorFeedbackResponse> {
  return apiFetch<AdvisorFeedbackResponse>('/advisor-feedback', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

/**
 * Dossier évaluable ciblé par le lien reçu par email (§43). {@code null} si le dossier n'existe pas
 * ou n'est plus disponible : l'IHM affiche alors « Ce dossier n'est plus disponible. » sans erreur
 * technique. Le backend reste seul juge de l'existence du dossier (§46).
 */
export async function fetchAdvisorDossier(sessionId: string): Promise<AdvisorDossierView | null> {
  const response = await fetch(
    `${API_BASE_URL}/advisor-feedback/sessions/${encodeURIComponent(sessionId)}/dossier`,
    { headers: { Accept: 'application/json' } },
  )
  if (response.status === 404 || response.status === 204) return null
  if (!response.ok) throw new Error(`Erreur HTTP ${response.status}`)
  return (await response.json()) as AdvisorDossierView
}

/** Dossiers proposés à l'évaluation + catalogue produits (produit manquant). */export async function fetchAdvisorCandidates(
  days = 7,
  includeEvaluated = false,
): Promise<AdvisorCandidatesResponse> {
  const params = new URLSearchParams({ days: String(days), includeEvaluated: String(includeEvaluated) })
  return apiFetch<AdvisorCandidatesResponse>(`/advisor-feedback/candidates?${params.toString()}`)
}

/** État du module (libellés, jours disponibles). */
export async function fetchAdvisorStatus(): Promise<AdvisorStatus> {
  return apiFetch<AdvisorStatus>('/advisor-feedback/status')
}

/** Agrégats complets de la période (KPI, zones, motifs, produits, corrections, emails). */
export async function fetchAdvisorOverview(
  period: AdvisorPeriod,
  from?: string,
  to?: string,
  filters?: AdvisorFilters,
): Promise<AdvisorAggregates> {
  return apiFetch<AdvisorAggregates>(`/advisor-feedback/overview?${advisorQuery(period, from, to, filters)}`)
}

/** Rapport IA de la période (ou le plus récent) ; {@code null} si aucun rapport. */
export async function fetchAdvisorReport(date?: string): Promise<AdvisorReport | null> {
  const query = date ? `?date=${encodeURIComponent(date)}` : ''
  const response = await fetch(`${API_BASE_URL}/advisor-feedback/report${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (response.status === 204) return null
  if (!response.ok) throw new Error(`Erreur HTTP ${response.status}`)
  return (await response.json()) as AdvisorReport
}

/** Bouton « Générer l'analyse IA » (§30) : rapport de la période sélectionnée. */
export async function generateAdvisorReport(
  period: AdvisorPeriod,
  from?: string,
  to?: string,
): Promise<AdvisorReport> {
  return apiFetch<AdvisorReport>(`/advisor-feedback/report/generate?${advisorQuery(period, from, to)}`, {
    method: 'POST',
  })
}

/** Jeu de démonstration (source=DEMO, rejouable sans doublon). */
export async function generateAdvisorDemoData(
  days = 14,
  evaluationsPerDay = 4,
): Promise<{ days: number; written: number }> {
  const params = new URLSearchParams({ days: String(days), evaluationsPerDay: String(evaluationsPerDay) })
  return apiFetch(`/advisor-feedback/demo-data?${params.toString()}`, { method: 'POST' })
}

/** URL d'export CSV des statistiques agrégées. */
export function advisorSummaryCsvUrl(period: AdvisorPeriod, from?: string, to?: string): string {
  return `${API_BASE_URL}/advisor-feedback/export/summary.csv?${advisorQuery(period, from, to)}`
}

// --- Atelier d'amélioration itérative des prompts (#/prompt-lab) --------------------------------

/** Agents et zones optimisables + plafond d'itérations (sélecteur de l'IHM). */
export async function fetchPromptOptimizationAgents(): Promise<PromptOptimizationAgents> {
  return apiFetch('/prompt-optimization/agents')
}

/** Campagnes connues (la plus récemment modifiée d'abord). */
export async function fetchPromptCampaigns(): Promise<PromptCampaign[]> {
  return apiFetch('/prompt-optimization/campaigns')
}

/**
 * Démarre une campagne : validation, snapshot de référence figé, statut RUNNING.
 * <p>
 * La réponse porte aussi le FIL DE CONVERSATION (créé ou repris) : c'est lui qui porte la mémoire rejouée au
 * cycle suivant (`threadId` transmis au démarrage).
 */
export async function startPromptCampaign(input: StartCampaignInput): Promise<PromptCampaignStart> {
  return apiFetch('/prompt-optimization/campaigns', { method: 'POST', body: JSON.stringify(input) })
}

/** Fils de conversation connus, du plus récemment modifié au plus ancien. */
export async function fetchPromptThreads(): Promise<PromptThread[]> {
  return apiFetch('/prompt-optimization/threads')
}

/** Conversation complète d'un fil (tours validés par promotion, dans l'ordre chronologique). */
export async function fetchPromptThread(threadId: string): Promise<PromptThread> {
  return apiFetch(`/prompt-optimization/threads/${encodeURIComponent(threadId)}`)
}

/**
 * BILAN début ↔ fin d'une conversation : le prompt du premier cycle face au prompt en vigueur à la fin
 * (dernière version promue). C'est le « comparer le prompt initial et le prompt final » du scénario.
 */
export async function fetchPromptThreadComparison(threadId: string): Promise<ConversationComparison> {
  return apiFetch(`/prompt-optimization/threads/${encodeURIComponent(threadId)}/comparison`)
}

/** Corrige le contenu d'un tour : l'humain garde la main sur la réponse rejouée au cycle suivant. */
export async function updatePromptTurn(
  threadId: string,
  turnIndex: number,
  content: string,
): Promise<PromptThread> {
  return apiFetch(`/prompt-optimization/threads/${encodeURIComponent(threadId)}/turns/${turnIndex}`, {
    method: 'PUT',
    body: JSON.stringify({ content }),
  })
}

/** Options de clôture d'un scénario d'atelier. */
export interface ThreadCloseOptions {
  /** true = envoie le mail au conseiller (comportement de « Terminer la conversation » de la page coach). */
  sendMail: boolean
  /** true = écrit le dossier de suivi, donc la conversation apparaît dans la page Centre d'appels. */
  archive: boolean
  provider?: AIProvider
}

/**
 * CLÔTURE du scénario : le fil de l'atelier passe dans le MÊME pipeline que la page coach (agent de suivi,
 * dossier, mail conseiller, score de sens commercial, annuaire du centre d'appels).
 */
export async function closePromptThread(
  threadId: string,
  options: ThreadCloseOptions,
): Promise<PromptThreadClosure> {
  return apiFetch(`/prompt-optimization/threads/${encodeURIComponent(threadId)}/close`, {
    method: 'POST',
    body: JSON.stringify(options),
  })
}

/**
 * Demande au CLIENT simulé (Agent C) la question suivante : il reçoit le brief, les trois chiffres du
 * dossier et la conversation déjà échangée ; il ne conseille jamais et n'invente aucun chiffre.
 */
export async function fetchClientQuestion(input: ClientQuestionInput): Promise<ClientTurnView> {
  return apiFetch('/prompt-optimization/client/question', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

/**
 * Projet proposé par l'Agent C pour le champ « Brief du client » (bouton « Générer projet ») : l'agent cherche
 * lui-même un client et un projet correspondant à l'agent de coach sélectionné. `previousBriefs` contient les
 * propositions déjà affichées : le modèle doit en chercher une franchement différente.
 */
export async function fetchGeneratedClientBrief(
  input: GeneratedClientBriefInput,
): Promise<GeneratedClientBrief> {
  return apiFetch('/prompt-optimization/client/brief', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

/** Vue complète d'une campagne : état, snapshot, itérations, versions et avis. */
export async function fetchPromptCampaign(campaignId: string): Promise<PromptCampaignDetail> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}`)
}

/** Exécute UNE itération complète (Coach → contrôleur → éditeur). */
export async function iteratePromptCampaign(campaignId: string): Promise<PromptIteration> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/iterate`, { method: 'POST' })
}

/** Arrêt gracieux : l'appel en cours se termine, puis la campagne passe en pause. */
export async function stopPromptCampaign(campaignId: string): Promise<PromptCampaign> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/stop`, { method: 'POST' })
}

/** Reprise : prolonge éventuellement le cycle et applique l'avis humain en attente. */
export async function resumePromptCampaign(
  campaignId: string,
  additionalIterations: number,
): Promise<PromptCampaign> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/resume`, {
    method: 'POST',
    body: JSON.stringify({ additionalIterations }),
  })
}

/** Avis humain : prioritaire sur le contrôleur automatique. */
export async function sendPromptHumanFeedback(
  campaignId: string,
  content: string,
): Promise<HumanFeedbackView> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/feedback`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
}

/** ProMEUT une version en production (action humaine explicite, prompt précédent sauvegardé). */
export async function promotePromptVersion(campaignId: string, version: string): Promise<PromotionResult> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/promote`, {
    method: 'POST',
    body: JSON.stringify({ version }),
  })
}

/**
 * ACCEPTATION d'une version POUR LA CONVERSATION, sans écrire le prompt de production : c'est ce qu'utilise le
 * mode automatique de l'Agent C. La réponse de la version acceptée entre dans le fil (le client garde sa
 * mémoire), la campagne est close, mais le fichier de production reste intact : la décision d'écrire reste
 * humaine, à la fin du scénario, après comparaison début ↔ fin.
 */
export async function acceptPromptVersion(campaignId: string, version: string): Promise<PromotionResult> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/accept`, {
    method: 'POST',
    body: JSON.stringify({ version }),
  })
}

/** Refuse la campagne : aucune version promue, rien n'est supprimé. */
export async function rejectPromptCampaign(campaignId: string): Promise<PromptCampaign> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/reject`, { method: 'POST' })
}

/** Comparaison version initiale / version courante. */
export async function fetchPromptComparison(campaignId: string): Promise<PromptComparison> {
  return apiFetch(`/prompt-optimization/campaigns/${encodeURIComponent(campaignId)}/compare`)
}
