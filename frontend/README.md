# erkasa frontend (6.1)

React 19 + Vite + Tailwind v4 + React Router + TanStack Query + i18next (EN / SQ). Mobile first.

```
npm install
npm run dev        # http://localhost:5173  (the backend must run on :8080)
npm test           # unit tests (vitest)
npm run build      # production files in dist/
```

- `/api` is passed on to the backend by Vite (vite.config.ts), so no CORS setup is needed in dev.
- Phone on the same Wi-Fi: open `http://<your-PC-IP>:5173` (Vite prints the "Network" address).
- Google sign-in: copy `.env.example` to `.env.local` and set `VITE_GOOGLE_CLIENT_ID`
  (same value as `GOOGLE_CLIENT_ID` in `infra/.env`).
- Texts: `src/i18n/en.ts` and `src/i18n/sq.ts` (same keys; a test checks it).
