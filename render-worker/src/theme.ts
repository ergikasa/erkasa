/**
 * The colour themes (9.3). The wizard lets the user pick one per video; branding (own logo,
 * colours, font) comes after deploy. Keep the names in sync with the backend VideoOptions.Theme.
 *
 * `theme` is the one the current video uses. Video.tsx calls applyTheme(spec.theme) before any
 * scene is drawn; one render (one browser tab) only ever draws one video, so a single shared
 * object is safe and keeps every component simple (`theme.text`, `theme.accent`...).
 */

import type React from 'react';

export type ThemeName = 'DARK' | 'LIGHT' | 'VIBRANT';

type Theme = {
  bg: string;
  bg2: string;
  panel: string;
  panelBorder: string;
  shadow: string;
  text: string;
  muted: string;
  accent: string;
  accent2: string;
  good: string;
  bad: string;
  warn: string;
  /** the two slow glows and the grid lines of the moving background */
  glow1: string;
  glow2: string;
  grid: string;
  /** text on top of an accent colour (numbers in circles, buttons) */
  onAccent: string;
  /** captions over graphic scenes: text, outline, the word being spoken (image scenes stay white) */
  captionText: string;
  captionStroke: string;
  captionActive: string;
  font: string;
  mono: string;
  /** a different accent per item so long diagrams stay readable */
  palette: string[];
};

const FONT = 'Inter, "Segoe UI", Roboto, Arial, sans-serif';
const MONO = '"JetBrains Mono", Consolas, "Courier New", monospace';

export const THEMES: Record<ThemeName, Theme> = {
  /** "Dark tech": deep navy, neon accents (how every video looked before 9.3). */
  DARK: {
    bg: '#0A0F1F',
    bg2: '#111833',
    panel: 'rgba(20, 28, 56, 0.92)',
    panelBorder: 'rgba(120, 140, 255, 0.28)',
    shadow: '0 20px 60px rgba(0,0,0,0.45)',
    text: '#EEF2FF',
    muted: '#94A3B8',
    accent: '#22D3EE',
    accent2: '#A78BFA',
    good: '#34D399',
    bad: '#F87171',
    warn: '#FBBF24',
    glow1: 'rgba(34,211,238,0.18)',
    glow2: 'rgba(167,139,250,0.18)',
    grid: 'rgba(148,163,184,0.07)',
    onAccent: '#0A0F1F',
    captionText: '#FFFFFF',
    captionStroke: '#000000',
    captionActive: '#FFE14D',
    font: FONT,
    mono: MONO,
    palette: ['#22D3EE', '#A78BFA', '#34D399', '#FBBF24', '#F472B6', '#60A5FA'],
  },
  /** Clean and bright: white panels on a soft grey-blue, strong blue accents. */
  LIGHT: {
    bg: '#F8FAFC',
    bg2: '#E8EEF7',
    panel: 'rgba(255, 255, 255, 0.96)',
    panelBorder: 'rgba(15, 23, 42, 0.10)',
    shadow: '0 18px 50px rgba(15,23,42,0.12)',
    text: '#0F172A',
    muted: '#475569',
    accent: '#2563EB',
    accent2: '#7C3AED',
    good: '#059669',
    bad: '#DC2626',
    warn: '#D97706',
    glow1: 'rgba(37,99,235,0.12)',
    glow2: 'rgba(124,58,237,0.10)',
    grid: 'rgba(15,23,42,0.05)',
    onAccent: '#FFFFFF',
    captionText: '#0F172A',
    captionStroke: '#FFFFFF',
    captionActive: '#2563EB',
    font: FONT,
    mono: MONO,
    palette: ['#2563EB', '#7C3AED', '#059669', '#D97706', '#DB2777', '#0891B2'],
  },
  /** Bold and warm: deep purple with orange and pink, for short-form and fun topics. */
  VIBRANT: {
    bg: '#2E0854',
    bg2: '#4C0F6B',
    panel: 'rgba(64, 16, 102, 0.90)',
    panelBorder: 'rgba(251, 146, 60, 0.35)',
    shadow: '0 20px 60px rgba(20,0,40,0.5)',
    text: '#FFF7ED',
    muted: '#E9D5FF',
    accent: '#F97316',
    accent2: '#EC4899',
    good: '#4ADE80',
    bad: '#FB7185',
    warn: '#FACC15',
    glow1: 'rgba(249,115,22,0.22)',
    glow2: 'rgba(236,72,153,0.22)',
    grid: 'rgba(255,237,213,0.06)',
    onAccent: '#2E0854',
    captionText: '#FFFFFF',
    captionStroke: '#000000',
    captionActive: '#FACC15',
    font: FONT,
    mono: MONO,
    palette: ['#F97316', '#EC4899', '#FACC15', '#4ADE80', '#38BDF8', '#C084FC'],
  },
};

/** The theme of the video being drawn. Starts as DARK; Video.tsx switches it. */
export const theme: Theme = { ...THEMES.DARK, palette: [...THEMES.DARK.palette] };

/** Switches every component to this theme (unknown or missing name = DARK). */
export function applyTheme(name: string | null | undefined): void {
  const next = THEMES[(name ?? 'DARK') as ThemeName] ?? THEMES.DARK;
  Object.assign(theme, next, { palette: [...next.palette] });
}

/** The look of every card / box, read at draw time so it follows the theme. */
export function panelStyle(): React.CSSProperties {
  return {
    background: theme.panel,
    border: `2px solid ${theme.panelBorder}`,
    borderRadius: 28,
    boxShadow: theme.shadow,
  };
}
