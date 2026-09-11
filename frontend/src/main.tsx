import React, { useEffect, useState } from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import Logs from './Logs'
import Agents from './Agents'
import Marketing from './Marketing'
import './styles.css'

type Page = 'chat' | 'logs' | 'agents' | 'marketing'

function currentPage(): Page {
  const hash = window.location.hash
  if (hash.startsWith('#/logs')) return 'logs'
  if (hash.startsWith('#/agents')) return 'agents'
  if (hash.startsWith('#/marketing')) return 'marketing'
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
  if (page === 'marketing') return <Marketing />
  return <App />
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Router />
  </React.StrictMode>,
)
