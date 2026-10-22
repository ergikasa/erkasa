import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Project, SceneAssets, StepView, projectsApi, visualType } from '../api/projects';
import { useBlobUrl } from '../hooks/useBlobUrl';
import { Alert, Button, Spinner, TextArea, useErrorText } from './ui';

/**
 * The files of a project, scene by scene: voice (play it) and picture (see it), each with its
 * status. When everything is ready, any scene can be redone (new voice and/or new picture).
 */
export const AssetsBoard: React.FC<{ project: Project; live: { done: number; total: number } }> = ({ project, live }) => {
  const { t } = useTranslation();
  const assets = useQuery({
    queryKey: ['assets', project.id],
    queryFn: () => projectsApi.assets(project.id),
    // live events invalidate this; the interval is only a safety net
    refetchInterval: project.status === 'ASSETS_PENDING' ? 8000 : false,
  });
  const done = assets.data?.done ?? live.done;
  const total = assets.data?.total ?? live.total;
  const canRedo = project.status === 'ASSETS_READY';

  return (
    <div>
      <Progress done={done} total={total} label={t('project.files.progress', { done, total })} />
      {assets.isPending ? (
        <div className="flex justify-center py-8">
          <Spinner />
        </div>
      ) : (
        <ol className="mt-5 grid gap-3 sm:grid-cols-2">
          {(assets.data?.scenes ?? []).map((s) => (
            <li key={s.scene}>
              <SceneFiles project={project} assets={s} canRedo={canRedo} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
};

export const Progress: React.FC<{ done: number; total: number; label: string }> = ({ done, total, label }) => {
  const pct = total > 0 ? Math.round((done / total) * 100) : 0;
  return (
    <div>
      <div className="mb-1.5 flex justify-between text-sm">
        <span className="text-muted">{label}</span>
        <span className="font-semibold text-accent">{pct}%</span>
      </div>
      <div className="h-2.5 overflow-hidden rounded-full bg-surface-2" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={label}>
        <div className="h-full rounded-full bg-gradient-to-r from-accent to-accent-2 transition-[width] duration-500" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
};

const SceneFiles: React.FC<{ project: Project; assets: SceneAssets; canRedo: boolean }> = ({ project, assets, canRedo }) => {
  const { t } = useTranslation();
  const n = assets.scene + 1;
  const scene = project.script?.scenes[assets.scene];
  const type = scene ? visualType(scene) : 'image';
  const [redo, setRedo] = useState(false);

  return (
    <article className="h-full rounded-2xl border border-line bg-surface p-4" aria-label={t('project.scene', { n })}>
      <header className="flex items-center gap-2">
        <span className="flex size-7 items-center justify-center rounded-lg bg-surface-2 text-sm font-bold text-accent">{n}</span>
        <span className="rounded-full border border-line px-2.5 py-0.5 text-xs font-medium text-muted">{t(`visual.${type}`)}</span>
        {canRedo && !redo ? (
          <button type="button" onClick={() => setRedo(true)} className="ml-auto min-h-9 rounded-lg px-3 text-sm font-semibold text-accent hover:bg-accent/10">
            {t('project.files.redo')}
          </button>
        ) : null}
      </header>
      {scene ? <p className="mt-3 line-clamp-3 text-sm text-muted">{scene.narration}</p> : null}

      <div className="mt-3 flex items-center gap-3">
        {assets.image ? <Picture step={assets.image} n={n} /> : null}
        <div className="min-w-0 flex-1 space-y-2">
          <StepLine label={t('project.files.voice')} step={assets.voice} extra={assets.voice?.status === 'SUCCEEDED' && assets.voice.url ? <PlayVoice url={assets.voice.url} n={n} /> : null} />
          {assets.image ? <StepLine label={t('project.files.image')} step={assets.image} /> : <p className="text-xs text-muted">{t('project.files.graphic')}</p>}
        </div>
      </div>

      {redo && scene ? <RedoForm project={project} scene={assets.scene} hasImage={!!assets.image} onClose={() => setRedo(false)} /> : null}
    </article>
  );
};

const StepLine: React.FC<{ label: string; step: StepView | null; extra?: React.ReactNode }> = ({ label, step, extra }) => {
  const { t } = useTranslation();
  if (!step) return null;
  const tone = { SUCCEEDED: 'text-success', FAILED: 'text-danger', RUNNING: 'text-accent', PENDING: 'text-muted', SKIPPED: 'text-muted' }[step.status];
  return (
    <div>
      <div className="flex items-center gap-2 text-sm">
        <span className="w-14 shrink-0 text-muted">{label}</span>
        {step.status === 'RUNNING' ? <Spinner className="size-3.5" /> : <StatusDot status={step.status} />}
        <span className={`font-medium ${tone}`}>{t(`project.files.${step.status}`)}</span>
        <span className="ml-auto">{extra}</span>
      </div>
      {step.status === 'FAILED' && step.errorCode ? <p className="mt-1 text-xs text-danger/90">{step.errorCode}</p> : null}
    </div>
  );
};

const StatusDot: React.FC<{ status: StepView['status'] }> = ({ status }) => (
  <span
    aria-hidden
    className={`inline-block size-2.5 rounded-full ${status === 'SUCCEEDED' ? 'bg-success' : status === 'FAILED' ? 'bg-danger' : 'bg-line'}`}
  />
);

/** The picture loads only when it is ready (it needs the login token, so it comes as a blob). */
const Picture: React.FC<{ step: StepView; n: number }> = ({ step, n }) => {
  const { t } = useTranslation();
  const { url } = useBlobUrl(step.status === 'SUCCEEDED' ? step.url : null);
  return (
    <div className="flex h-24 w-16 shrink-0 items-center justify-center overflow-hidden rounded-xl border border-line bg-surface-2">
      {url ? <img src={url} alt={t('project.files.pictureOf', { n })} className="size-full object-cover" /> : step.status === 'RUNNING' ? <Spinner className="size-5" /> : null}
    </div>
  );
};

/** Loads the voice only when you press play (saves data on phones). */
const PlayVoice: React.FC<{ url: string; n: number }> = ({ url, n }) => {
  const { t } = useTranslation();
  const [wanted, setWanted] = useState(false);
  const [playing, setPlaying] = useState(false);
  const { url: blobUrl } = useBlobUrl(wanted ? url : null);
  const audio = useRef<HTMLAudioElement | null>(null);

  useEffect(() => {
    if (!blobUrl) return;
    const a = new Audio(blobUrl);
    audio.current = a;
    a.onended = () => setPlaying(false);
    void a.play().then(() => setPlaying(true)).catch(() => setPlaying(false));
    return () => {
      a.pause();
      audio.current = null;
    };
  }, [blobUrl]);

  const toggle = () => {
    if (!wanted) return setWanted(true);
    const a = audio.current;
    if (!a) return;
    if (a.paused) void a.play().then(() => setPlaying(true));
    else {
      a.pause();
      setPlaying(false);
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={playing ? t('project.files.pause') : t('project.files.play', { n })}
      className="flex size-9 items-center justify-center rounded-full border border-line text-accent hover:border-accent"
    >
      {wanted && !blobUrl ? (
        <Spinner className="size-4" />
      ) : playing ? (
        <svg viewBox="0 0 24 24" className="size-4" fill="currentColor" aria-hidden>
          <rect x="6" y="5" width="4" height="14" rx="1" />
          <rect x="14" y="5" width="4" height="14" rx="1" />
        </svg>
      ) : (
        <svg viewBox="0 0 24 24" className="size-4" fill="currentColor" aria-hidden>
          <path d="M8 5v14l11-7z" />
        </svg>
      )}
    </button>
  );
};

const RedoForm: React.FC<{ project: Project; scene: number; hasImage: boolean; onClose: () => void }> = ({ project, scene, hasImage, onClose }) => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const queryClient = useQueryClient();
  const original = project.script!.scenes[scene];
  const [voice, setVoice] = useState(!hasImage);
  const [image, setImage] = useState(hasImage);
  const [narration, setNarration] = useState(original.narration);
  const [imagePrompt, setImagePrompt] = useState(original.imagePrompt);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const changedNarration = narration.trim() !== original.narration.trim();
      const changedPrompt = imagePrompt.trim() !== original.imagePrompt.trim();
      const p = await projectsApi.redoScene(project.id, scene, {
        // a new text needs a new recording / picture
        voice: voice || changedNarration,
        image: hasImage && (image || changedPrompt),
        narration: changedNarration ? narration.trim() : undefined,
        imagePrompt: hasImage && changedPrompt ? imagePrompt.trim() : undefined,
      });
      queryClient.setQueryData(['project', project.id], p);
      void queryClient.invalidateQueries({ queryKey: ['assets', project.id] });
      onClose();
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="mt-4 space-y-3 rounded-xl border border-accent/30 bg-accent/5 p-3">
      <p className="text-sm font-semibold">{t('project.files.redoTitle', { n: scene + 1 })}</p>
      {error ? <Alert>{error}</Alert> : null}
      <div className="flex flex-wrap gap-2">
        <Toggle checked={voice} onChange={setVoice} label={t('project.files.redoVoice')} />
        {hasImage ? <Toggle checked={image} onChange={setImage} label={t('project.files.redoImage')} /> : null}
      </div>
      <TextArea label={t('project.files.redoNarration')} value={narration} onChange={(e) => setNarration(e.target.value)} />
      {hasImage ? <TextArea label={t('project.files.redoImagePrompt')} value={imagePrompt} onChange={(e) => setImagePrompt(e.target.value)} /> : null}
      <div className="flex gap-2">
        <Button type="button" variant="ghost" onClick={onClose} disabled={busy} className="flex-1">
          {t('project.files.redoCancel')}
        </Button>
        <Button type="submit" loading={busy} className="flex-[2]">
          {t('project.files.redoSubmit')}
        </Button>
      </div>
    </form>
  );
};

const Toggle: React.FC<{ checked: boolean; onChange: (v: boolean) => void; label: string }> = ({ checked, onChange, label }) => (
  <label className={`inline-flex min-h-10 cursor-pointer items-center gap-2 rounded-full border px-3 text-sm ${checked ? 'border-accent bg-accent/10 text-text' : 'border-line text-muted'}`}>
    <input type="checkbox" className="size-4 accent-[var(--color-accent)]" checked={checked} onChange={(e) => onChange(e.target.checked)} />
    {label}
  </label>
);
