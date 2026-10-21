import React from 'react';
import { useTranslation } from 'react-i18next';
import { LANGUAGES, LanguageCode, setLanguage } from '../i18n';

export const Logo: React.FC<{ className?: string }> = ({ className = '' }) => (
  <span className={`inline-flex items-center gap-2 font-bold tracking-tight ${className}`}>
    <svg viewBox="0 0 64 64" className="size-8" aria-hidden>
      <defs>
        <linearGradient id="logo-g" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#a78bfa" />
        </linearGradient>
      </defs>
      <rect width="64" height="64" rx="16" fill="#0e1528" stroke="#243050" />
      <path d="M24 18 L46 32 L24 46 Z" fill="url(#logo-g)" />
    </svg>
    <span className="text-xl">erkasa</span>
  </span>
);

/** EN | SQ toggle. Two big buttons: easy to hit with a thumb. */
export const LanguageSwitch: React.FC<{ className?: string }> = ({ className = '' }) => {
  const { t, i18n } = useTranslation();
  const current = (i18n.resolvedLanguage ?? 'en') as LanguageCode;
  return (
    <div role="group" aria-label={t('app.language')} className={`inline-flex rounded-xl border border-line bg-surface p-1 ${className}`}>
      {LANGUAGES.map((l) => (
        <button
          key={l.code}
          type="button"
          onClick={() => setLanguage(l.code)}
          aria-pressed={current === l.code}
          title={l.label}
          className={`min-h-9 min-w-11 rounded-lg px-2 text-sm font-semibold transition ${current === l.code ? 'bg-surface-2 text-accent' : 'text-muted hover:text-text'}`}
        >
          {l.short}
        </button>
      ))}
    </div>
  );
};
