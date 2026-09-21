// ------------------------------------------------------------------ Annuaire des conversations (#/centre-appels)
// Page de l'équipe commerciale / du centre d'appels : liste des conversations clôturées (dossiers de suivi),
// filtrable et triable, avec une pop-in de détail qui reprend la synthèse envoyée au conseiller par mail.

export interface DirectoryRow {
  sessionId: string
  customerId: string | null
  title: string
  mainProject: string | null
  category: string
  categoryLabel: string
  score: number | null
  priority: string | null
  priorityLabel: string | null
  scoreLabel: string | null
  closedAt: string | null
  productCount: number
  topProduct: string | null
  evaluated: boolean
}

export interface DirectoryCategory {
  code: string
  label: string
  count: number
}

export interface DirectoryList {
  rows: DirectoryRow[]
  categories: DirectoryCategory[]
  byPriority: Record<string, number>
  days: number
  sort: string
  order: string
  total: number
}

export interface DossierProductView {
  productId: string | null
  name: string | null
  category: string | null
  interestLevel: string | null
  interestReason: string | null
  productUrl: string | null
}

export interface DossierMessageView {
  role: string | null
  content: string | null
  timestamp: string | null
}

export interface DirectoryDetail {
  row: DirectoryRow
  advisorSubject: string | null
  advisorBody: string | null
  nextActions: string[]
  products: DossierProductView[]
  scoreReasons: string[]
  scoreCriteria: string[]
  scoreProposedByAi: boolean | null
  transcript: DossierMessageView[]
  contactPhone: string | null
  feedbackUrl: string | null
  conversationUrl: string | null
  evaluated: boolean
}

/** Tri serveur : `date` (défaut), `score`, `client`, `categorie`, `titre`. */
export type DirectorySort = 'date' | 'score' | 'client' | 'categorie' | 'titre'
export type DirectoryOrder = 'asc' | 'desc'

export interface DirectoryQuery {
  /** Période en jours (0 = tout l'historique). */
  days: number
  category?: string
  q?: string
  sort?: DirectorySort
  order?: DirectoryOrder
}
