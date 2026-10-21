import React from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { Button } from '../components/ui';

/** 6.1: the empty state. The real list of videos comes in 6.4. */
export const HomePage: React.FC = () => {
  const { t } = useTranslation();
  const auth = useAuth();
  const name = auth.status === 'user' ? auth.user.displayName || auth.user.email.split('@')[0] : '';
  return (
    <section>
      <p className="text-sm text-muted">{t('home.hello', { name })}</p>
      <h1 className="mt-1 text-2xl font-bold tracking-tight sm:text-3xl">{t('home.title')}</h1>

      <div className="glow mt-6 flex flex-col items-center rounded-2xl border border-dashed border-line px-6 py-12 text-center sm:py-16">
        <div className="flex size-14 items-center justify-center rounded-2xl bg-surface-2 text-accent">
          <svg viewBox="0 0 24 24" className="size-7" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
            <rect x="3" y="5" width="18" height="14" rx="3" />
            <path d="M10 9.5v5l4.5-2.5z" fill="currentColor" />
          </svg>
        </div>
        <h2 className="mt-4 text-lg font-semibold">{t('home.emptyTitle')}</h2>
        <p className="mt-2 max-w-sm text-muted">{t('home.emptyText')}</p>
        <Link to="/new" className="mt-6 w-full sm:w-auto">
          <Button full>{t('home.create')}</Button>
        </Link>
      </div>
    </section>
  );
};

export const NewVideoPage: React.FC = () => {
  const { t } = useTranslation();
  return (
    <section>
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{t('newVideo.title')}</h1>
      <p className="mt-4 text-muted">{t('newVideo.soon')}</p>
    </section>
  );
};

export const AccountPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const auth = useAuth();
  if (auth.status !== 'user') return null;
  const u = auth.user;
  const since = new Date(u.createdAt).toLocaleDateString(i18n.resolvedLanguage, { year: 'numeric', month: 'long', day: 'numeric' });
  return (
    <section className="max-w-xl">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{t('account.title')}</h1>
      <dl className="mt-6 divide-y divide-line rounded-2xl border border-line bg-surface">
        <Row label={t('auth.name')} value={u.displayName || '—'} />
        <Row
          label={t('account.email')}
          value={
            <span className="flex flex-wrap items-center gap-2">
              <span className="break-all">{u.email}</span>
              <span className={`rounded-full px-2 py-0.5 text-xs ${u.emailVerified ? 'bg-success/15 text-success' : 'bg-accent/15 text-accent'}`}>
                {u.emailVerified ? t('account.verified') : t('account.notVerified')}
              </span>
            </span>
          }
        />
        <Row label={t('account.memberSince')} value={since} />
      </dl>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <Button variant="secondary" onClick={() => void auth.logout()}>
          {t('nav.logout')}
        </Button>
        <Button variant="danger" onClick={() => void auth.logoutEverywhere()}>
          {t('account.logoutAll')}
        </Button>
      </div>
    </section>
  );
};

const Row: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div className="flex flex-col gap-1 px-4 py-3 sm:flex-row sm:items-center sm:gap-4">
    <dt className="text-sm text-muted sm:w-40">{label}</dt>
    <dd className="text-text">{value}</dd>
  </div>
);

export const NotFoundPage: React.FC = () => {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 px-4 text-center">
      <p className="text-6xl font-black text-accent">404</p>
      <h1 className="text-xl font-semibold">{t('notFound.title')}</h1>
      <Link to="/">
        <Button variant="secondary">{t('notFound.back')}</Button>
      </Link>
    </div>
  );
};
