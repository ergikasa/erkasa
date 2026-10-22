import React, { useState } from 'react';
import { Link, useRouteError } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useQuery } from '@tanstack/react-query';
import { Project, isWorking, projectsApi } from '../api/projects';
import { Alert, Button, Spinner, buttonClass } from '../components/ui';
import { StatusBadge } from './ProjectPage';

/** "Your videos": newest first, with the status of each. Keeps itself fresh while something runs. */
export const HomePage: React.FC = () => {
  const { t } = useTranslation();
  const auth = useAuth();
  const [limit, setLimit] = useState(20);
  const name = auth.status === 'user' ? auth.user.displayName || auth.user.email.split('@')[0] : '';
  const list = useQuery({
    queryKey: ['projects', limit],
    queryFn: () => projectsApi.list(limit),
    refetchInterval: (q) => (q.state.data?.some((p) => isWorking(p.status)) ? 10_000 : false),
  });
  const projects = list.data ?? [];

  return (
    <section>
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="text-sm text-muted">{t('home.hello', { name })}</p>
          <h1 className="mt-1 text-2xl font-bold tracking-tight sm:text-3xl">{t('home.title')}</h1>
        </div>
        {projects.length ? (
          // phones have "New video" in the tab bar
          <div className="hidden sm:block">
            <Link to="/new" className={buttonClass('primary')}>
              + {t('home.newVideo')}
            </Link>
          </div>
        ) : null}
      </div>

      {list.isPending ? (
        <div className="flex justify-center py-16">
          <Spinner />
        </div>
      ) : list.isError ? (
        <div className="mt-6">
          <Alert>{t('errors.generic')}</Alert>
        </div>
      ) : projects.length === 0 ? (
        <Empty />
      ) : (
        <>
          <ul className="mt-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {projects.map((p) => (
              // min-w-0: long titles are cut with "…" instead of pushing the card off the screen
              <li key={p.id} className="min-w-0">
                <ProjectCard project={p} />
              </li>
            ))}
          </ul>
          {projects.length >= limit && limit < 100 ? (
            <div className="mt-6 flex justify-center">
              <Button variant="secondary" onClick={() => setLimit((l) => Math.min(100, l + 30))} loading={list.isFetching}>
                {t('home.loadMore')}
              </Button>
            </div>
          ) : null}
        </>
      )}
    </section>
  );
};

const Empty: React.FC = () => {
  const { t } = useTranslation();
  return (
    <div className="glow mt-6 flex flex-col items-center rounded-2xl border border-dashed border-line px-6 py-12 text-center sm:py-16">
      <div className="flex size-14 items-center justify-center rounded-2xl bg-surface-2 text-accent">
        <svg viewBox="0 0 24 24" className="size-7" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
          <rect x="3" y="5" width="18" height="14" rx="3" />
          <path d="M10 9.5v5l4.5-2.5z" fill="currentColor" />
        </svg>
      </div>
      <h2 className="mt-4 text-lg font-semibold">{t('home.emptyTitle')}</h2>
      <p className="mt-2 max-w-sm text-muted">{t('home.emptyText')}</p>
      <Link to="/new" className={buttonClass('primary', false, 'mt-6 w-full sm:w-auto')}>
        {t('home.create')}
      </Link>
    </div>
  );
};

const ProjectCard: React.FC<{ project: Project }> = ({ project: p }) => {
  const { t } = useTranslation();
  const shape = { PORTRAIT: 'h-12 w-7', LANDSCAPE: 'h-7 w-12', SQUARE: 'size-9' }[p.aspectRatio];
  const ratio = { PORTRAIT: '9:16', LANDSCAPE: '16:9', SQUARE: '1:1' }[p.aspectRatio];
  return (
    <Link
      to={`/projects/${p.id}`}
      className="flex h-full min-h-20 w-full items-center gap-4 rounded-2xl border border-line bg-surface p-4 transition hover:border-accent/50 active:scale-[0.99]"
    >
      <div className="flex size-12 shrink-0 items-center justify-center">
        <span className={`rounded-md border-2 ${p.status === 'DONE' ? 'border-accent bg-accent/15' : 'border-line bg-surface-2'} ${shape}`} aria-hidden />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate font-semibold">{p.title || p.idea}</p>
        <p className="mt-0.5 truncate text-xs text-muted">
          {t(`languages.${p.language}`)} · {p.durationSeconds} s · {ratio} · {timeAgo(p.createdAt, t)}
        </p>
        <div className="mt-2">
          <StatusBadge status={p.status} />
        </div>
      </div>
      <span className="text-muted" aria-hidden>
        ›
      </span>
    </Link>
  );
};

function timeAgo(iso: string, t: (k: string, o?: Record<string, unknown>) => string): string {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (minutes < 1) return t('home.ago.now');
  if (minutes < 60) return t('home.ago.minutes', { n: minutes });
  const hours = Math.round(minutes / 60);
  if (hours < 24) return t('home.ago.hours', { n: hours });
  return t('home.ago.days', { n: Math.round(hours / 24) });
}

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
      <Link to="/" className={buttonClass('secondary')}>
        {t('notFound.back')}
      </Link>
    </div>
  );
};

/** Shown instead of a white screen when a page breaks. The error goes to the console for us. */
export const ErrorPage: React.FC = () => {
  const { t } = useTranslation();
  const error = useRouteError();
  console.error(error);
  return (
    <div className="flex min-h-[60dvh] flex-col items-center justify-center gap-4 px-4 text-center">
      <h1 className="text-xl font-semibold">{t('errors.page')}</h1>
      <p className="max-w-sm text-muted">{t('errors.pageHint')}</p>
      <div className="flex gap-3">
        <button type="button" onClick={() => window.location.reload()} className={buttonClass('primary')}>
          {t('errors.reload')}
        </button>
        <Link to="/" className={buttonClass('secondary')}>
          {t('notFound.back')}
        </Link>
      </div>
    </div>
  );
};
