import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Forwards browser requests beginning with /api to Spring Boot on port 8081 during development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8081',
    },
  },
})
