import React, { useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router';
import { useTranslation } from 'react-i18next';
import { authApi } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import { LanguageSwitch, Logo } from './brand';
import { Alert, Button } from './ui';
import { useCredits } from '../hooks/useCredits';

/**
 * Login, register, password pages.
 * Phone: full width, form starts near the top (keyboard-friendly).
 * Tablet / desktop: a centered card.
 */
export const AuthLayout: React.FC<{ title: string; subtitle?: string; children: React.ReactNode }> = ({ title, subtitle, children }) => {
  const { t } = useTranslation();
  return (
    <div className="glow flex min-h-dvh flex-col safe-top">
      <header className="flex items-center justify-between px-4 py-3 sm:px-6">
        <Link to="/" aria-label="erkasa">
          <Logo />
        </Link>
        <LanguageSwitch />
      </header>
      <main className="flex flex-1 justify-center px-4 pb-10 pt-4 sm:items-center sm:px-6 sm:pt-0">
        <div className="w-full max-w-md sm:rounded-2xl sm:border sm:border-line sm:bg-surface/80 sm:p-8 sm:shadow-2xl sm:shadow-black/40 sm:backdrop-blur">
          <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{title}</h1>
          {subtitle ? <p className="mt-2 text-muted">{subtitle}</p> : null}
          <div className="mt-6">{children}</div>
        </div>
      </main>
      <footer className="pb-6 text-center text-xs text-muted safe-bottom">{t('app.tagline')}</footer>
    </div>
  );
};

const NAV = [
  { to: '/', key: 'nav.videos', icon: IconGrid, end: true },
  { to: '/new', key: 'nav.newVideo', icon: IconPlus, end: false },
  { to: '/account', key: 'nav.account', icon: IconUser, end: false },
] as const;

/**
 * The logged-in app.
 * Phone: slim top bar + tab bar at the bottom (thumb reach).
 * md and up: everything in the top bar, no bottom bar.
 */
export const AppLayout: React.FC = () => {
  const { t } = useTranslation();
  const auth = useAuth();
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="sticky top-0 z-20 border-b border-line bg-bg/85 backdrop-blur safe-top">
        <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-4 sm:px-6">
          <Link to="/" aria-label="erkasa">
            <Logo />
          </Link>
          <nav className="ml-6 hidden items-center gap-1 md:flex" aria-label="Main">
            {NAV.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                end={n.end}
                className={({ isActive }) =>
                  `rounded-lg px-3 py-2 text-sm font-medium transition ${isActive ? 'bg-surface-2 text-text' : 'text-muted hover:text-text'}`
                }
              >
                {t(n.key)}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <CreditsChip />
            <LanguageSwitch />
            {/* on phones "Log out" is on the Account tab */}
            <div className="hidden md:block">
              <Button variant="ghost" onClick={() => void auth.logout()}>
                {t('nav.logout')}
              </Button>
            </div>
          </div>
        </div>
      </header>

      {auth.status === 'user' && !auth.user.emailVerified ? <VerifyBanner /> : null}

      {/* pb-24: room for the bottom tab bar on phones */}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 pb-24 pt-6 sm:px-6 md:pb-10">
        <Outlet />
      </main>

      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-3 border-t border-line bg-bg/95 backdrop-blur safe-bottom md:hidden"
      >
        {NAV.map((n) => (
          <NavLink
            key={n.to}
            to={n.to}
            end={n.end}
            className={({ isActive }) =>
              `flex min-h-14 flex-col items-center justify-center gap-1 pt-2 text-[11px] font-medium ${isActive ? 'text-accent' : 'text-muted'}`
            }
          >
            <n.icon />
            {t(n.key)}
          </NavLink>
        ))}
      </nav>
    </div>
  );
};

/** The balance, always in sight; tap it to see the history (Account). */
const CreditsChip: React.FC = () => {
  const { t } = useTranslation();
  const credits = useCredits();
  if (credits.data == null) return null;
  const n = credits.data.balance;
  return (
    <Link
      to="/credits"
      title={t('credits.perSecond')}
      aria-label={t('credits.short', { n })}
      className={`inline-flex min-h-9 items-center gap-1.5 rounded-xl border px-2.5 text-sm font-semibold transition hover:border-accent/60 ${n < 15 ? 'border-danger/50 text-danger' : 'border-line text-text'}`}
    >
      <svg viewBox="0 0 24 24" className="size-4 text-accent" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v10M9.5 9.5h3.75a1.75 1.75 0 0 1 0 3.5h-2.5a1.75 1.75 0 0 0 0 3.5H14.5" />
      </svg>
      {n}
    </Link>
  );
};

const VerifyBanner: React.FC = () => {
  const { t } = useTranslation();
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle');
  const resend = async () => {
    setState('sending');
    try {
      await authApi.resendVerification();
      setState('sent');
    } catch {
      setState('error');
    }
  };
  return (
    <div className="border-b border-accent/30 bg-accent/10">
      <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-3 text-sm sm:flex-row sm:items-center sm:px-6">
        <p className="text-accent">{state === 'sent' ? t('auth.verify.resent') : t('auth.verify.banner')}</p>
        {state !== 'sent' ? (
          <button
            type="button"
            onClick={() => void resend()}
            disabled={state === 'sending'}
            className="min-h-9 self-start font-semibold text-text underline underline-offset-4 sm:ml-auto"
          >
            {t('auth.verify.resend')}
          </button>
        ) : null}
      </div>
      {state === 'error' ? (
        <div className="mx-auto max-w-6xl px-4 pb-3 sm:px-6">
          <Alert>{t('errors.generic')}</Alert>
        </div>
      ) : null}
    </div>
  );
};

function IconGrid() {
  return (
    <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </svg>
  );
}

function IconPlus() {
  return (
    <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v8M8 12h8" />
    </svg>
  );
}

function IconUser() {
  return (
    <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6" />
    </svg>
  );
}
