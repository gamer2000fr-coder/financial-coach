# Financial Coach — Frontend React + TypeScript

Frontend du POC de coach financier conversationnel. Il est conçu pour être utilisé directement avec le backend Spring Boot fourni séparément.

Dépôt du projet : `https://github.com/gamer2000fr-coder/financial-coach` (branche `main`).

## Stack

- React
- TypeScript
- Vite
- lucide-react

## Prérequis

- Node.js 20+ recommandé
- Backend Spring Boot lancé sur `http://localhost:9797`

## Installation

```bash
npm install
npm run dev
```

Le frontend est disponible sur `http://localhost:9898` (et sur l'IP LAN de la machine, Vite écoute sur `0.0.0.0`).

## Configuration API

Par défaut :

```text
http://localhost:9797/api
```

Pour changer l'URL :

```bash
cp .env.example .env.local
```

Puis :

```text
VITE_API_BASE_URL=http://localhost:9797/api
```

## Fonctionnement

La page principale est centrée sur une conversation avec le coach financier.

Le fournisseur IA peut être changé à tout moment :

- `GPT` → backend utilisant OpenAI
- `DEEPSEEK` → backend utilisant DeepSeek
- `MOCK` → mode démo sans appel externe

Le choix est envoyé à chaque `POST /api/chat`, ce qui correspond exactement à l'API du backend.

## Endpoints consommés

- `GET /api/health`
- `GET /api/financial-summary`
- `POST /api/chat`

Exemple d'appel chat envoyé :

```json
{
  "sessionId": "web-123456",
  "message": "Analyse mes dépenses des 3 derniers mois.",
  "provider": "GPT"
}
```

Réponse attendue :

```json
{
  "sessionId": "web-123456",
  "provider": "GPT",
  "category": "BUDGET",
  "inScope": true,
  "status": "ANSWER",
  "answer": "...",
  "financialSummary": { "...": "..." },
  "conversationSummary": "..."
}
```

## Persistance navigateur

La session, le fournisseur choisi et les derniers messages sont conservés dans `localStorage` pour rendre le POC plus agréable à tester.

Aucune clé OpenAI/DeepSeek n'est stockée dans le frontend.
