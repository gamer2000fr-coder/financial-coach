import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import Logs from './Logs'
import Agents from './Agents'
import Marketing from './Marketing'
import Quality from './Quality'
import AdvisorFeedback from './AdvisorFeedback'
import DossierFeedback from './DossierFeedback'
import AdvisorCallbackPopup from './AdvisorCallbackPopup'
import ConversationView from './ConversationView'
import CallCenter from './CallCenter'
import PromptLab from './PromptLab'
import './styles.css'

type Page = 'chat' | 'logs' | 'agents' | 'marketing' | 'quality' | 'advisor-feedback' | 'advisor-dossier'
  | 'conversation' | 'prompt-lab' | 'centre-appels'

/** SessionId porté par le lien du mail conseiller : `#/advisor-feedback/session/<sessionId>` (§42). */
function dossierSessionId(hash: string): string | null {
  const match = hash.match(/^#\/advisor-feedback\/session\/(.+)$/)
  return match ? decodeURIComponent(match[1]) : null
}

/** SessionId porté par le lien « historique de la conversation » du mail conseiller. */
function conversationSessionId(hash: string): string | null {
  const match = hash.match(/^#\/conversation\/(.+)$/)
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * SessionId porté par le lien « Ouvrir le dossier du client » du mail conseiller :
 * `#/centre-appels/<sessionId>` ouvre la page Centre d'appels AVEC la pop-in du dossier.
 */
function directorySessionId(hash: string): string | null {
  const match = hash.match(/^#\/centre-appels\/(.+)$/)
  return match ? decodeURIComponent(match[1]) : null
}

function pageFor(hash: string): Page {
  if (hash.startsWith('#/logs')) return 'logs'
  if (hash.startsWith('#/agents')) return 'agents'
  if (hash.startsWith('#/prompt-lab')) return 'prompt-lab'
  if (hash.startsWith('#/centre-appels')) return 'centre-appels'
  if (hash.startsWith('#/marketing')) return 'marketing'
  if (hash.startsWith('#/quality')) return 'quality'
  if (hash.startsWith('#/advisor-feedback/session/')) return 'advisor-dossier'
  if (hash.startsWith('#/conversation/')) return 'conversation'
  if (hash.startsWith('#/advisor-feedback')) return 'advisor-feedback'
  return 'chat'
}

function Router() {
  // On suit la ROUTE COMPLÈTE (pas seulement le type de page) : passer d'une session à une autre dans la
  // même route (`#/conversation/<a>` → `#/conversation/<b>`) doit recharger la vue et non garder l'ancienne.
  const [route, setRoute] = useState(() => window.location.hash)

  useEffect(() => {
    const onHashChange = () => setRoute(window.location.hash)
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const page = pageFor(route)

  // La pop-in « être rappelé par un conseiller » est montée ICI (et non dans une page) : le jeton [RAPPEL|…]
  // du Coach fonctionne donc sur toutes les pages qui affichent une réponse (chat, historique de conversation).
  return (
    <>
      {pageContent(page, route)}
      <AdvisorCallbackPopup />
    </>
  )
}

function pageContent(page: Page, route: string) {
  if (page === 'logs') return <Logs />
  if (page === 'agents') return <Agents />
  if (page === 'prompt-lab') return <PromptLab />
  if (page === 'centre-appels') {
    const sessionId = directorySessionId(route)
    return sessionId ? <CallCenter key={sessionId} initialSessionId={sessionId} /> : <CallCenter />
  }
  if (page === 'marketing') return <Marketing />
  if (page === 'quality') return <Quality />
  if (page === 'advisor-dossier') {
    const sessionId = dossierSessionId(route)
    return sessionId ? <DossierFeedback key={sessionId} sessionId={sessionId} /> : <AdvisorFeedback />
  }
  if (page === 'conversation') {
    const sessionId = conversationSessionId(route)
    return sessionId ? <ConversationView key={sessionId} sessionId={sessionId} /> : <App />
  }
  if (page === 'advisor-feedback') return <AdvisorFeedback />
  return <App />
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Router />
  </React.StrictMode>,
)
