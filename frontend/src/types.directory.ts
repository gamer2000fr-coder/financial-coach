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
  /** Statut d'avancement saisi par le centre d'appels (`NOUVEAU` par défaut). */
  status: string
  statusLabel: string
  statusUpdatedAt: string | null
  /** Nombre de messages laissés sur le dossier (journal du centre d'appels). */
  noteCount: number
}

export interface DirectoryCategory {
  code: string
  label: string
  count: number
}

export interface DirectoryList {
  rows: DirectoryRow[]
  categories: DirectoryCategory[]
  statuses: DirectoryCategory[]
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
  /** Pièce jointe du mail conseiller : brouillon d'email préparé POUR LE CLIENT (jamais envoyé seul). */
  customerEmailSubject: string | null
  customerEmailBody: string | null
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
  /** Historique des changements de statut, du plus ancien au plus récent. */
  statusHistory: DossierStatusEvent[]
}

/** Changement de statut d'un dossier (trace de l'avancement du fil de travail). */
export interface DossierStatusEvent {
  eventId: string | null
  sessionId: string
  status: string
  statusLabel: string | null
  previousStatus: string | null
  comment: string | null
  timestamp: string | null
}

/** Statut d'avancement proposé à la saisie (ordre du cycle de vie). */
export interface DossierStatusOption {
  code: string
  label: string
}

/** Tri serveur : `date` (défaut), `score`, `client`, `categorie`, `titre`. */
export type DirectorySort = 'date' | 'score' | 'client' | 'categorie' | 'titre'
export type DirectoryOrder = 'asc' | 'desc'

export interface DirectoryQuery {
  /** Période en jours (0 = tout l'historique). */
  days: number
  category?: string
  /** Filtre sur le statut d'avancement (`NOUVEAU`, `CONTACTE`, `QUALIFIE`, `RDV`, `CONCLU`, `PERDU`, `CLOTURE`). */
  status?: string
  q?: string
  sort?: DirectorySort
  order?: DirectoryOrder
}

/** Statuts d'avancement d'un dossier, dans l'ordre du cycle de vie (mêmes codes que le backend). */
export const DOSSIER_STATUSES: DossierStatusOption[] = [
  { code: 'NOUVEAU', label: 'Nouveau' },
  { code: 'CONTACTE', label: 'Contacté' },
  { code: 'QUALIFIE', label: 'Qualifié' },
  { code: 'RDV', label: 'RDV planifié' },
  { code: 'CONCLU', label: 'Conclu' },
  { code: 'PERDU', label: 'Sans suite' },
  { code: 'CLOTURE', label: 'Clôturé' },
]
