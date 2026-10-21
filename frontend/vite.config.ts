/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// In development the browser talks only to Vite (http://localhost:5173); /api is passed on to
// the Spring Boot backend, so there are no CORS problems and the same paths work in production.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // host: true lets a phone on the same Wi-Fi open http://<your-PC-IP>:5173 (mobile testing)
    host: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
