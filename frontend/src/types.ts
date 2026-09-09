export type AIProvider = 'GPT' | 'DEEPSEEK' | 'MOCK'

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
  status: 'ANSWER' | 'NEED_DATA'
  agent?: string
  requestedData: string[]
  debug?: string
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
