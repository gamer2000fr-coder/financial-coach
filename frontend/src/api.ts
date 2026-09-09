import type { AiLog, AIProvider, ChatResponse, ConversationData, FinancialSummary } from './types'

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
