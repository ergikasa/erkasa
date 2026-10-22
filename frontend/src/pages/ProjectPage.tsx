import React, { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CREDITS_KEY } from '../api/credits';
import { ApiError } from '../api/client';
import { Project, VideoScript, isWorking, projectsApi } from '../api/projects';
import { ScriptEditor, cleanScript } from '../components/ScriptEditor';
import { AssetsBoard, Progress } from '../components/AssetsBoard';
import { VideoPanel } from '../components/VideoPanel';
import { Live, useLiveProject } from '../hooks/useLiveProject';
import { Alert, Button, Spinner, useErrorText } from '../components/ui';

/**
 * A safety net only: live events (SSE) update the page at once. If the live connection is
 * down, the page still asks for news this often while the backend works.
 */
const POLL_MS = 10_000;

export const ProjectPage: React.FC = () => {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const errorText = useErrorText();
  const query = useQuery({
    queryKey: ['project', id],
    queryFn: () => projectsApi.get(id),
    refetchInterval: (q) => (q.state.data && isWorking(q.state.data.status) ? POLL_MS : false),
  });
  const status = query.data?.status;
  const live = useLiveProject(id, !!status && status !== 'DONE' && status !== 'CANCELLED');

  if (query.isPending) {
    return (
      <div className="flex justify-center py-20">
        <Spinner />
      </div>
    );
  }
  if (query.isError) {
    return (
      <section className="space-y-4">
        <BackLink />
        <Alert>{errorText(query.error)}</Alert>
      </section>
    );
  }
  const p = query.data;
  return (
    <section className="mx-auto max-w-3xl">
      <BackLink />
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <StatusBadge status={p.status} />
        {isWorking(p.status) ? (
          <span className={`inline-flex items-center gap-1.5 text-xs ${live.connected ? 'text-success' : 'text-muted'}`}>
            <span className={`size-1.5 rounded-full ${live.connected ? 'animate-pulse bg-success' : 'bg-muted'}`} aria-hidden />
            {live.connected ? t('project.live') : t('project.reconnecting')}
          </span>
        ) : null}
        <span className="text-xs text-muted">
          {t(`languages.${p.language}`)} · {p.durationSeconds} s · {p.aspectRatio === 'PORTRAIT' ? '9:16' : p.aspectRatio === 'LANDSCAPE' ? '16:9' : '1:1'}
        </span>
      </div>
      <h1 className="mt-2 text-2xl font-bold tracking-tight break-words sm:text-3xl">{p.title || p.idea}</h1>
      <div className="mt-6">
        <Stage project={p} live={live} />
      </div>
    </section>
  );
};

const Stage: React.FC<{ project: Project; live: Live }> = ({ project: p, live }) => {
  const { t } = useTranslation();
  switch (p.status) {
    case 'SCRIPT_PENDING':
      return <Working title={t('project.writing')} hint={t('project.writingHint')} />;
    case 'SCRIPT_REVIEW':
      // the live event can say "script ready" a moment before the script itself has arrived
      return p.script ? <ScriptReview project={p} /> : <Working title={t('project.writing')} hint={t('project.writingHint')} />;
    case 'ASSETS_PENDING':
      return (
        <div className="space-y-5">
          <div>
            <h2 className="text-lg font-semibold">{t('project.making')}</h2>
            <p className="mt-1 text-sm text-muted">{t('project.makingHint')}</p>
          </div>
          <AssetsBoard project={p} live={live} />
        </div>
      );
    case 'ASSETS_READY':
      return (
        <div className="space-y-6">
          <RenderStart project={p} />
          <AssetsBoard project={p} live={live} />
        </div>
      );
    case 'RENDERING':
      return (
        <Rendering percent={live.renderPercent} />
      );
    case 'DONE':
      return <VideoPanel project={p} />;
    case 'CANCELLED':
      return <Alert tone="info">{t('project.cancelled')}</Alert>;
    case 'FAILED':
      return (
        <div className="space-y-6">
          <Failed project={p} />
          {/* failed while making files: show which scene broke */}
          {p.jobs.some((j) => j.kind === 'ASSETS') && !p.jobs.some((j) => j.kind === 'RENDER') ? <AssetsBoard project={p} live={live} /> : null}
        </div>
      );
  }
};

/** How long 0 % may last before we suspect that no render worker is running. */
const STUCK_MS = 60_000;

const Rendering: React.FC<{ percent: number | null }> = ({ percent }) => {
  const { t } = useTranslation();
  const [stuck, setStuck] = useState(false);
  const started = (percent ?? 0) > 0;
  useEffect(() => {
    if (started) {
      setStuck(false);
      return;
    }
    const timer = window.setTimeout(() => setStuck(true), STUCK_MS);
    return () => window.clearTimeout(timer);
  }, [started]);
  return (
    <div className="glow rounded-2xl border border-line p-5 sm:p-8" role="status">
      <h2 className="text-lg font-semibold">{t('project.rendering')}</h2>
      <p className="mt-1 mb-5 text-sm text-muted">{t('project.renderingHint')}</p>
      <Progress done={percent ?? 0} total={100} label={t('project.status.RENDERING')} />
      {stuck ? (
        <div className="mt-5">
          <Alert tone="info">{t('project.renderStuck')}</Alert>
        </div>
      ) : null}
    </div>
  );
};

/** All files are ready: the big "Make the video" button. */
const RenderStart: React.FC<{ project: Project }> = ({ project }) => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      queryClient.setQueryData(['project', project.id], await projectsApi.render(project.id));
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  };
  return (
    <div className="glow rounded-2xl border border-accent/30 p-5 sm:flex sm:items-center sm:gap-6 sm:p-6">
      <div className="flex-1">
        <h2 className="text-lg font-semibold">{t('project.ready')}</h2>
        <p className="mt-1 text-sm text-muted">{t('project.makeVideoHint', { n: project.estimatedCredits })}</p>
        {error ? (
          <div className="mt-3">
            <Alert>{error}</Alert>
          </div>
        ) : null}
      </div>
      <Button onClick={() => void start()} loading={busy} className="mt-4 w-full sm:mt-0 sm:w-auto sm:min-w-48">
        {t('project.makeVideo')}
      </Button>
    </div>
  );
};

const Working: React.FC<{ title: string; hint?: string }> = ({ title, hint }) => (
  <div className="glow flex flex-col items-center rounded-2xl border border-line px-6 py-14 text-center" role="status">
    <Spinner className="size-10" />
    <p className="mt-5 text-lg font-semibold">{title}</p>
    {hint ? <p className="mt-2 max-w-sm text-sm text-muted">{hint}</p> : null}
  </div>
);

/** The script stage: edit, save, ask for a new one, approve or cancel. */
const ScriptReview: React.FC<{ project: Project }> = ({ project }) => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<VideoScript>(project.script!);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState<'save' | 'approve' | 'regenerate' | 'cancel' | null>(null);
  const [error, setError] = useState<{ text: string; problems: string[] } | null>(null);
  const [saved, setSaved] = useState(false);
  const top = useRef<HTMLDivElement>(null);

  // a new script from the server (e.g. after "New script") replaces the draft, unless you are editing
  useEffect(() => {
    if (!dirty && project.script) setDraft(project.script);
  }, [project.script, dirty]);

  const update = (p: Project) => queryClient.setQueryData(['project', p.id], p);

  const fail = (e: unknown) => {
    setError({ text: errorText(e), problems: e instanceof ApiError ? e.problems : [] });
    top.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    if (e instanceof ApiError && e.code === 'INVALID_STATE') void queryClient.invalidateQueries({ queryKey: ['project', project.id] });
  };

  const run = async (kind: NonNullable<typeof busy>, action: () => Promise<void>) => {
    setBusy(kind);
    setError(null);
    setSaved(false);
    try {
      await action();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  const save = async () => {
    const p = await projectsApi.saveScript(project.id, cleanScript(draft));
    setDirty(false);
    update(p);
  };

  const onSave = () =>
    run('save', async () => {
      await save();
      setSaved(true);
    });

  const onApprove = () =>
    run('approve', async () => {
      if (dirty) await save();
      update(await projectsApi.approve(project.id));
      void queryClient.invalidateQueries({ queryKey: CREDITS_KEY });
    });

  const onRegenerate = () => {
    if (!window.confirm(t('project.regenerateConfirm'))) return;
    void run('regenerate', async () => {
      setDirty(false);
      update(await projectsApi.regenerateScript(project.id));
    });
  };

  const onCancel = () => {
    if (!window.confirm(t('project.cancelConfirm'))) return;
    void run('cancel', async () => {
      update(await projectsApi.cancel(project.id));
      void queryClient.invalidateQueries({ queryKey: CREDITS_KEY });
    });
  };

  return (
    <div ref={top} className="scroll-mt-20">
      <h2 className="text-lg font-semibold">{t('project.review')}</h2>
      <p className="mt-1 text-sm text-muted">{t('project.reviewHint')}</p>

      {error ? (
        <div className="mt-4">
          <Alert>
            <p>{error.text}</p>
            {error.problems.length ? (
              <>
                <p className="mt-2 font-semibold">{t('project.problems')}</p>
                <ul className="mt-1 list-disc space-y-0.5 pl-5">
                  {error.problems.map((pr) => (
                    <li key={pr}>{pr}</li>
                  ))}
                </ul>
              </>
            ) : null}
          </Alert>
        </div>
      ) : null}

      <div className="mt-5">
        <ScriptEditor
          script={draft}
          targetSeconds={project.durationSeconds}
          onChange={(s) => {
            setDraft(s);
            setDirty(true);
            setSaved(false);
          }}
        />
      </div>

      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <Button variant="secondary" onClick={onRegenerate} loading={busy === 'regenerate'} disabled={busy !== null}>
          {t('project.regenerate')}
        </Button>
        <Button variant="danger" onClick={onCancel} loading={busy === 'cancel'} disabled={busy !== null}>
          {t('project.cancel')}
        </Button>
      </div>

      {/* room for the action bar on phones */}
      <div className="h-32 md:hidden" />

      {/* Phone: fixed above the tab bar, always reachable. Desktop: sticky at the bottom of the page. */}
      <div className="fixed inset-x-0 bottom-[calc(3.5rem+max(0.75rem,env(safe-area-inset-bottom)))] z-10 border-t border-line bg-bg/95 px-4 py-3 backdrop-blur md:sticky md:bottom-0 md:mt-6 md:rounded-2xl md:border md:px-5">
        <div className="mx-auto flex max-w-3xl items-center gap-3">
          <p className="hidden flex-1 text-sm text-muted sm:block" aria-live="polite">
            {saved ? t('project.saved') : dirty ? t('project.unsaved') : t('project.approveHint')}
          </p>
          <Button variant="secondary" onClick={() => void onSave()} loading={busy === 'save'} disabled={!dirty || busy !== null} className="flex-1 sm:flex-none">
            {saved && !dirty ? (
              t('project.saved')
            ) : (
              <>
                <span className="sm:hidden">{t('project.saveShort')}</span>
                <span className="hidden sm:inline">{t('project.save')}</span>
              </>
            )}
          </Button>
          <Button onClick={() => void onApprove()} loading={busy === 'approve'} disabled={busy !== null} className="flex-[2] sm:flex-none">
            <span className="sm:hidden">{t('project.approveShort')}</span>
            <span className="hidden sm:inline">{t('project.approve')}</span>
          </Button>
        </div>
      </div>
    </div>
  );
};

const Failed: React.FC<{ project: Project }> = ({ project }) => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const retry = async () => {
    setBusy(true);
    setError(null);
    try {
      queryClient.setQueryData(['project', project.id], await projectsApi.retry(project.id));
      void queryClient.invalidateQueries({ queryKey: CREDITS_KEY });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="space-y-4">
      <Alert>
        <p className="font-semibold">{t('project.failed')}</p>
        {project.errorCode ? <p className="mt-1 opacity-90">{t(`errors.${project.errorCode}`, { defaultValue: project.errorMessage ?? project.errorCode })}</p> : null}
      </Alert>
      {error ? <Alert>{error}</Alert> : null}
      <Button onClick={() => void retry()} loading={busy}>
        {t('project.retry')}
      </Button>
    </div>
  );
};

export const StatusBadge: React.FC<{ status: Project['status'] }> = ({ status }) => {
  const { t } = useTranslation();
  const tone =
    status === 'DONE' || status === 'SCRIPT_REVIEW' || status === 'ASSETS_READY'
      ? 'bg-success/15 text-success'
      : status === 'FAILED'
        ? 'bg-danger/15 text-danger'
        : status === 'CANCELLED'
          ? 'bg-surface-2 text-muted'
          : 'bg-accent/15 text-accent';
  return <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${tone}`}>{t(`project.status.${status}`)}</span>;
};

const BackLink: React.FC = () => {
  const { t } = useTranslation();
  return (
    <Link to="/" className="inline-flex min-h-9 items-center gap-1 text-sm text-muted hover:text-text">
      <span aria-hidden>←</span> {t('project.back')}
    </Link>
  );
};
