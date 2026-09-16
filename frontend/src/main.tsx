import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import Logs from './Logs'
import Agents from './Agents'
import Marketing from './Marketing'
import Quality from './Quality'
import AdvisorFeedback from './AdvisorFeedback'
import DossierFeedback from './DossierFeedback'
import PromptLab from './PromptLab'
import './styles.css'

type Page = 'chat' | 'logs' | 'agents' | 'marketing' | 'quality' | 'advisor-feedback' | 'advisor-dossier' | 'prompt-lab'

/** SessionId porté par le lien du mail conseiller : `#/advisor-feedback/session/<sessionId>` (§42). */
function dossierSessionId(): string | null {
  const match = window.location.hash.match(/^#\/advisor-feedback\/session\/(.+)$/)
  return match ? decodeURIComponent(match[1]) : null
}

function currentPage(): Page {
  const hash = window.location.hash
  if (hash.startsWith('#/logs')) return 'logs'
  if (hash.startsWith('#/agents')) return 'agents'
  if (hash.startsWith('#/prompt-lab')) return 'prompt-lab'
  if (hash.startsWith('#/marketing')) return 'marketing'
  if (hash.startsWith('#/quality')) return 'quality'
  if (hash.startsWith('#/advisor-feedback/session/')) return 'advisor-dossier'
  if (hash.startsWith('#/advisor-feedback')) return 'advisor-feedback'
  return 'chat'
}

function Router() {
  const [page, setPage] = useState<Page>(currentPage())

  useEffect(() => {
    const onHashChange = () => setPage(currentPage())
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  if (page === 'logs') return <Logs />
  if (page === 'agents') return <Agents />
  if (page === 'prompt-lab') return <PromptLab />
  if (page === 'marketing') return <Marketing />
  if (page === 'quality') return <Quality />
  if (page === 'advisor-dossier') {
    const sessionId = dossierSessionId()
    return sessionId ? <DossierFeedback sessionId={sessionId} /> : <AdvisorFeedback />
  }
  if (page === 'advisor-feedback') return <AdvisorFeedback />
  return <App />
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Router />
  </React.StrictMode>,
)
