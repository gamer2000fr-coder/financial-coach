/**
 * Fournisseurs IA : `LOCAL` = serveur compatible OpenAI lancé sur la machine (LM Studio, Ollama…),
 * sans clé API ; `MOCK` = mode démo (aucun appel réseau).
 */
export type AIProvider = 'GPT' | 'DEEPSEEK' | 'LOCAL' | 'MOCK'

export type RequestCategory =
  | 'PURCHASE_PROJECT'
  | 'BUDGET'
  | 'SAVINGS'
  | 'CREDIT'
  | 'CASHFLOW'
  | 'FINANCIAL_HEALTH'
  | 'BANK_PRODUCT'
  | 'OTHER_FINANCIAL'
  | 'OUT_OF_SCOPE'

export interface FinancialSummary {
  periodMonths: number
  periodStart: string
  periodEnd: string
  averageMonthlyIncome: number
  medianMonthlyIncome: number
  averageMonthlyExpenses: number
  fixedExpenses: number
  variableExpenses: number
  averageMonthlySavings: number
  averageDisposableIncome: number
  currentAccountBalance: number
  savingsBalance: number
  monthlyLoanPayments: number
  debtServiceToIncomeRatio: number
  savingsToIncomeRatio: number
  savingsToIncomeRatio3Months: number
  savingsRatePeriodLabel: string
  overdraftOccurrences: number
  minimumObservedBalance: number
  largestRecentExpense: number
  largestRecentExpenseLabel: string
  transactionCount: number
  averageMonthlyExpensesByCategory: Record<string, number>
}

export interface ChatResponse {
  sessionId: string
  provider: AIProvider
  category: RequestCategory
  inScope: boolean
  status: 'ANSWER' | 'NEED_DATA'
  answer: string
  financialSummary: FinancialSummary | null
  conversationSummary: string | null
  agent?: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: string
  provider?: AIProvider
  category?: RequestCategory
  agent?: string
}

export interface AiLog {
  id: number
  timestamp: string
  sessionId: string
  clientMessage: string
  dataSent: string[]
  historyCount: number
  charCount: number
  status: 'ANSWER' | 'NEED_DATA' | 'ERROR'
  agent?: string
  requestedData: string[]
  debug?: string
  /** État d'envoi du mail au conseiller — renseigné UNIQUEMENT sur la trace de clôture de conversation. */
  mailStatus?: string
}

export interface ConversationMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp?: string
}

export interface ConversationData {
  sessionId: string
  summary?: string | null
  messages: ConversationMessage[]
}

export type InterestLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'REJECTED'

export interface ProductOfInterest {
  productId: string
  name: string
  category?: string | null
  interestLevel: InterestLevel
  interestReason?: string | null
  productUrl?: string | null
}

export interface SuiviEmailContent {
  subject: string
  body: string
}

export interface SuiviConversationSummary {
  mainProject?: string | null
  otherProjects?: string[] | null
  importantCustomerPreferences?: string[] | null
}

export type ClosureStatus = 'SENT' | 'PREPARED' | 'MAIL_UNAVAILABLE' | 'SEND_FAILED' | 'NO_CONVERSATION'

/** Dossier de suivi produit à la clôture d'une conversation (email conseiller + brouillon client joint). */
export interface ConversationClosure {
  sessionId: string
  status: ClosureStatus
  advisorName?: string | null
  advisorAddress?: string | null
  attachmentName?: string | null
  sentTo?: string[] | null
  conversationSummary?: SuiviConversationSummary | null
  productsOfInterest?: ProductOfInterest[] | null
  rejectedProducts?: string[] | null
  advisorEmail?: SuiviEmailContent | null
  preparedCustomerEmail?: SuiviEmailContent | null
  warnings?: string[] | null
}

// ------------------------------------------------------------------ Marketing Intelligence (#/marketing)

export type MarketingPeriod = 'today' | 'yesterday' | '7d' | '30d' | 'custom'

export interface MarketingOverview {
  conversationCount: number
  sessionsWithInterest: number
  uniqueCustomers: number
  highInterestCount: number
  subscriptionIntentCount: number
  appointmentRequestCount: number
  unmetNeedCount: number
  missingInformationCount: number
  coachQualityIssueCount: number
}

export interface MarketingProductMetric {
  productId: string
  productName: string
  productFamily?: string | null
  recommendedSessions: number
  interestedSessions: number
  uniqueInterestedCustomers: number
  highCount: number
  mediumCount: number
  lowCount: number
  rejectedCount: number
  comparisonCount: number
  subscriptionIntentCount: number
  appointmentRequestCount: number
  interestRate?: number | null
  score: number
  previousInterestedSessions?: number | null
  evolutionPercent?: number | null
}

export interface MarketingProductCount {
  productId: string
  productName: string
  count: number
}

export interface MarketingProjectMetric {
  projectType: string
  volume: number
  share?: number | null
  amountRanges: Record<string, number>
  interestCount: number
  rejectedCount: number
  unmetNeedCount: number
  topProducts: MarketingProductCount[]
  previousVolume?: number | null
  evolutionPercent?: number | null
}

export interface MarketingRejectionMetric {
  reasonCategory: string
  productId?: string | null
  productName?: string | null
  count: number
  share?: number | null
}

export interface MarketingCrossSellMetric {
  sourceProductId: string
  sourceProductName: string
  targetProductId: string
  targetProductName: string
  commonSessions: number
  sourceSessions: number
  rate?: number | null
}

export interface MarketingUnmetNeedMetric {
  projectType?: string | null
  reasonCategory?: string | null
  reason?: string | null
  count: number
  averageConfidence?: number | null
}

export interface MarketingMissingInfoMetric {
  productId: string
  productName: string
  reasonCategory: string
  count: number
}

export interface MarketingDailyPoint {
  date: string
  conversationCount: number
  interestCount: number
  highInterestCount: number
  subscriptionIntentCount: number
  appointmentRequestCount: number
}

export interface MarketingTrendMetric {
  entityType: string
  entityId: string
  entityName: string
  current: number
  previous: number
  evolutionPercent?: number | null
}

export interface MarketingAggregates {
  dateFrom: string
  dateTo: string
  overview: MarketingOverview
  products: MarketingProductMetric[]
  projects: MarketingProjectMetric[]
  rejections: MarketingRejectionMetric[]
  crossSell: MarketingCrossSellMetric[]
  unmetNeeds: MarketingUnmetNeedMetric[]
  missingInformation: MarketingMissingInfoMetric[]
  series: MarketingDailyPoint[]
  trends: MarketingTrendMetric[]
  demo: boolean
  invalidEventLines: number
  generatedAt: string
}

export interface MarketingReportItem {
  title?: string | null
  description?: string | null
  importance?: string | null
}

export interface MarketingMainTrend {
  type?: string | null
  entityType?: string | null
  entityId?: string | null
  entityName?: string | null
  observation?: string | null
}

export interface MarketingRecommendationPerformance {
  productId?: string | null
  productName?: string | null
  observation?: string | null
}

export interface MarketingFrictionItem {
  category?: string | null
  observation?: string | null
}

export interface MarketingCrossSellInsight {
  sourceProduct?: string | null
  targetProduct?: string | null
  observation?: string | null
}

export interface MarketingUnmetNeedInsight {
  projectType?: string | null
  observation?: string | null
}

export interface MarketingMissingInfoInsight {
  productId?: string | null
  productName?: string | null
  observation?: string | null
}

export interface MarketingAlert {
  level?: string | null
  title?: string | null
  description?: string | null
}

export interface MarketingOpportunity {
  title?: string | null
  description?: string | null
  recommendation?: string | null
}

export interface MarketingReport {
  reportDate?: string | null
  executiveSummary?: MarketingReportItem[] | null
  mainTrends?: MarketingMainTrend[] | null
  recommendationPerformance?: MarketingRecommendationPerformance[] | null
  customerFriction?: MarketingFrictionItem[] | null
  crossSellInsights?: MarketingCrossSellInsight[] | null
  unmetNeeds?: MarketingUnmetNeedInsight[] | null
  missingProductInformation?: MarketingMissingInfoInsight[] | null
  aiCoachQuality?: { type?: string | null; observation?: string | null }[] | null
  alerts?: MarketingAlert[] | null
  opportunities?: MarketingOpportunity[] | null
  finalSummary?: string | null
  generatedAt?: string | null
  model?: string | null
  aiGenerated?: boolean | null
  error?: string | null
}

export interface MarketingStatus {
  enabled: boolean
  demoMode: boolean
  eventsDir: string
  availableDays: string[]
  reportDates: string[]
  amountBounds: number[]
  /** Libellés métier fournis par le backend (source unique) : code technique → libellé lisible. */
  projectTypeLabels?: Record<string, string>
  productFamilyLabels?: Record<string, string>
  rejectionReasonLabels?: Record<string, string>
  interestReasonLabels?: Record<string, string>
  unmetReasonLabels?: Record<string, string>
  missingInfoReasonLabels?: Record<string, string>
}

export interface MarketingProductDetail {
  productId: string
  metric?: MarketingProductMetric | null
  rejections: MarketingRejectionMetric[]
  crossSell: MarketingCrossSellMetric[]
  dateFrom: string
  dateTo: string
}
