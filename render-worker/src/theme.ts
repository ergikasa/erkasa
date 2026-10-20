/** "Dark tech" look: deep navy background, neon accents. One place to change the style. */
export const theme = {
  bg: '#0A0F1F',
  bg2: '#111833',
  panel: 'rgba(20, 28, 56, 0.92)',
  panelBorder: 'rgba(120, 140, 255, 0.28)',
  text: '#EEF2FF',
  muted: '#94A3B8',
  accent: '#22D3EE', // cyan
  accent2: '#A78BFA', // violet
  good: '#34D399', // green
  bad: '#F87171', // red
  warn: '#FBBF24',
  // bundled in src/fonts.ts; the rest is only a fallback
  font: 'Inter, "Segoe UI", Roboto, Arial, sans-serif',
  mono: '"JetBrains Mono", Consolas, "Courier New", monospace',
  /** a different accent per item so long diagrams stay readable */
  palette: ['#22D3EE', '#A78BFA', '#34D399', '#FBBF24', '#F472B6', '#60A5FA'],
} as const;
