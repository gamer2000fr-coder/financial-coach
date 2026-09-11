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
