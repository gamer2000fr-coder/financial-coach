import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 9898,
    host: true, // écoute sur 0.0.0.0 → accessible via l'IP LAN (ex. http://192.168.1.83:9898)
  },
})
