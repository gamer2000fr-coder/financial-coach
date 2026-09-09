# Coach financier — POC

> Démonstrateur conversationnel de coach financier bancaire.
> Dépôt : `https://github.com/gamer2000fr-coder/financial-coach` (branche `main`)

Un client discute en langage naturel de son budget, de ses projets (achat, travaux, crédit, épargne…) et reçoit des recommandations pédagogiques basées sur des **données bancaires de démonstration**.

**Principe clé : LLM = comprendre · JAVA = décider des règles métier et calculer · LLM = expliquer**

## Architecture
- **Backend** : Spring Boot (Java), port `9797` — orchestration, moteur métier déterministe (filtrage de produits), agents IA par thème.
- **Frontend** : React + Vite + TypeScript, port `9898`.
- **IA** : GPT/OpenAI, DeepSeek ou mode démo (MOCK) sans clé.

## Démarrage rapide
```bash
# Backend (port 9797)
mvnw spring-boot:run          # Linux/macOS
mvnw.cmd spring-boot:run      # Windows
# clés facultatives :
#   export DEEPSEEK_API_KEY=sk-...   (ou OPENAI_API_KEY)

# Frontend (port 9898)
cd frontend
npm install
npm run dev
```

## Documentation
- `docs/doc-fonctionnel.md` — fonctionnalités et parcours utilisateur.
- `docs/doc-technique.md` — architecture, flux, données et API.
- `docs/flux-architectural.md` — schémas de la chaîne complète.

## Notes
- Données **fictives** (démo uniquement).
- Aucune clé API en dur : externalisée via variables d'environnement.
- État en mémoire (sessions, logs) — perdu au redémarrage.
