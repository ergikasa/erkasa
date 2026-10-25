import React from 'react';
import { useTranslation } from 'react-i18next';
import { PAIRED_TYPES, Scene, Visual, VideoScript, VisualType, countWords, spokenSeconds, visualType } from '../api/projects';
import { TextArea, TextField } from './ui';

/**
 * Edit every text of a script: what the voice says, the big on-screen text, and the texts of the
 * motion graphic (steps, list points, the number, code ...). The structure (number of scenes,
 * visual types) stays as the AI wrote it; "New script" asks for a different one.
 */
export const ScriptEditor: React.FC<{
  script: VideoScript;
  targetSeconds: number;
  onChange: (script: VideoScript) => void;
}> = ({ script, targetSeconds, onChange }) => {
  const { t } = useTranslation();
  const setScene = (i: number, scene: Scene) => onChange({ ...script, scenes: script.scenes.map((s, k) => (k === i ? scene : s)) });
  const spoken = spokenSeconds(script);
  const off = spoken > targetSeconds * 1.2 + 3 || spoken < targetSeconds * 0.4;

  return (
    <div>
      <TextField label={t('project.fields.title')} value={script.title} onChange={(e) => onChange({ ...script, title: e.target.value })} maxLength={120} />
      <p className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted">
        <span>{t('project.scenes', { n: script.scenes.length })}</span>
        <span className={off ? 'text-danger' : ''}>{t('project.spoken', { n: spoken, target: targetSeconds })}</span>
        <span>{t('project.music', { mood: t(`mood.${script.musicMood}`, { defaultValue: script.musicMood }) })}</span>
      </p>
      <ol className="mt-5 space-y-4">
        {script.scenes.map((scene, i) => (
          <li key={i}>
            <SceneCard index={i} scene={scene} onChange={(s) => setScene(i, s)} />
          </li>
        ))}
      </ol>
    </div>
  );
};

const SceneCard: React.FC<{ index: number; scene: Scene; onChange: (s: Scene) => void }> = ({ index, scene, onChange }) => {
  const { t } = useTranslation();
  const type = visualType(scene);
  const words = countWords(scene.narration);
  const setVisual = (patch: Partial<Visual>) => onChange({ ...scene, visual: { ...(scene.visual as Visual), ...patch } });

  return (
    <article className="rounded-2xl border border-line bg-surface p-4 sm:p-5" aria-label={t('project.scene', { n: index + 1 })}>
      <header className="mb-4 flex items-center gap-2">
        <span className="flex size-7 items-center justify-center rounded-lg bg-surface-2 text-sm font-bold text-accent">{index + 1}</span>
        <span className="rounded-full border border-line px-2.5 py-0.5 text-xs font-medium text-muted">{t(`visual.${type}`)}</span>
      </header>

      <div className="space-y-4">
        <TextArea
          label={t('project.narration')}
          value={scene.narration}
          onChange={(e) => onChange({ ...scene, narration: e.target.value })}
          hint={`${words} · ~${Math.round(words / 2.5)} s`}
        />
        <TextField
          label={t('project.onScreenText')}
          value={scene.onScreenText}
          maxLength={80}
          onChange={(e) => onChange({ ...scene, onScreenText: e.target.value })}
          error={countWords(scene.onScreenText) > 8 ? '≤ 8' : undefined}
        />

        {type === 'image' ? (
          <TextArea label={t('project.imagePrompt')} value={scene.imagePrompt} onChange={(e) => onChange({ ...scene, imagePrompt: e.target.value })} />
        ) : (
          <VisualFields visual={scene.visual as Visual} onChange={setVisual} />
        )}

        {scene.sfx.length ? (
          <div>
            <p className="mb-1.5 text-sm font-medium text-muted">{t('project.sounds')}</p>
            <ul className="flex flex-wrap gap-2">
              {scene.sfx.map((cue, k) => (
                <li key={k} className="inline-flex min-h-9 items-center gap-1 rounded-full border border-accent-2/40 bg-accent-2/10 pl-3 text-sm text-accent-2">
                  <span>
                    {cue.sound} · {cue.onWord ? t('project.onWord', { word: cue.onWord }) : t('project.atStart')}
                  </span>
                  <button
                    type="button"
                    aria-label={t('project.removeSound', { sound: cue.sound })}
                    onClick={() => onChange({ ...scene, sfx: scene.sfx.filter((_, j) => j !== k) })}
                    className="flex size-9 items-center justify-center rounded-full hover:bg-accent-2/20"
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </article>
  );
};

/** A copy of the list with one position changed (missing positions are filled with the value's empty form). */
function setAt<T>(list: T[], i: number, value: T): T[] {
  const copy = [...list];
  copy[i] = value;
  return copy;
}

/** A value the editor shows but does not change (an icon name, a pose). */
const ReadOnly: React.FC<{ label: string; value: string; mono?: boolean }> = ({ label, value, mono }) => (
  <div>
    <p className="mb-1.5 text-sm font-medium text-muted">{label}</p>
    <p className={`flex min-h-12 items-center rounded-xl border border-line bg-surface-2 px-3 text-sm text-muted ${mono ? 'font-mono' : ''}`}>{value}</p>
  </div>
);

/** One row per item with two fields side by side (label + number, date + event, icon + text). */
const Pairs: React.FC<{ rows: string[]; left: (i: number) => React.ReactNode; right: (i: number) => React.ReactNode }> = ({ rows, left, right }) => (
  <div className="space-y-3">
    {rows.map((_, i) => (
      <div key={i} className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-3">
        {left(i)}
        {right(i)}
      </div>
    ))}
  </div>
);

/** "one per line" lists: keep the raw lines while typing, clean them when saving. */
const Lines: React.FC<{ label: string; value: string[]; onChange: (v: string[]) => void }> = ({ label, value, onChange }) => (
  <TextArea label={label} rows={3} value={value.join('\n')} onChange={(e) => onChange(e.target.value.split('\n'))} />
);

const VisualFields: React.FC<{ visual: Visual; onChange: (patch: Partial<Visual>) => void }> = ({ visual: v, onChange }) => {
  const { t } = useTranslation();
  const f = (k: string) => t(`project.fields.${k}`);
  switch (v.type) {
    case 'title':
      return (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label={f('title')} value={v.title} onChange={(e) => onChange({ title: e.target.value })} />
          <TextField label={f('subtitle')} value={v.subtitle} onChange={(e) => onChange({ subtitle: e.target.value })} />
        </div>
      );
    case 'flow':
      return <Lines label={f('steps')} value={v.items} onChange={(items) => onChange({ items })} />;
    case 'list':
      return <Lines label={f('items')} value={v.items} onChange={(items) => onChange({ items })} />;
    case 'stat':
      return (
        <div className="grid grid-cols-3 gap-3">
          <TextField label={f('prefix')} value={v.prefix} onChange={(e) => onChange({ prefix: e.target.value })} />
          <TextField
            label={f('value')}
            inputMode="decimal"
            value={v.value == null ? '' : String(v.value)}
            onChange={(e) => {
              const n = Number(e.target.value.replace(',', '.'));
              onChange({ value: e.target.value === '' || Number.isNaN(n) ? null : n });
            }}
          />
          <TextField label={f('suffix')} value={v.suffix} onChange={(e) => onChange({ suffix: e.target.value })} />
          <TextField className="col-span-3" label={f('label')} value={v.label} onChange={(e) => onChange({ label: e.target.value })} />
        </div>
      );
    case 'compare':
      return (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-3">
            <TextField label={f('leftTitle')} value={v.leftTitle} onChange={(e) => onChange({ leftTitle: e.target.value })} />
            <Lines label={f('left')} value={v.left} onChange={(left) => onChange({ left })} />
          </div>
          <div className="space-y-3">
            <TextField label={f('rightTitle')} value={v.rightTitle} onChange={(e) => onChange({ rightTitle: e.target.value })} />
            <Lines label={f('right')} value={v.right} onChange={(right) => onChange({ right })} />
          </div>
        </div>
      );
    case 'code':
      return (
        <div className="space-y-3">
          <TextField label={f('codeLanguage')} value={v.language} onChange={(e) => onChange({ language: e.target.value })} />
          <TextArea label={f('code')} mono rows={4} spellCheck={false} value={v.code} onChange={(e) => onChange({ code: e.target.value })} />
        </div>
      );
    case 'cta':
      return (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label={f('text')} value={v.text} onChange={(e) => onChange({ text: e.target.value })} />
          <TextField label={f('handle')} value={v.handle} onChange={(e) => onChange({ handle: e.target.value })} />
        </div>
      );
    // ---- INFOGRAPHIC (9.3): the number of rows stays as the AI wrote it ----
    case 'bars':
      return (
        <div className="space-y-3">
          <TextField label={f('title')} value={v.title} onChange={(e) => onChange({ title: e.target.value })} />
          <Pairs
            rows={v.items}
            left={(i) => (
              <TextField label={f('barLabel')} value={v.items[i]} onChange={(e) => onChange({ items: setAt(v.items, i, e.target.value) })} />
            )}
            right={(i) => (
              <TextField
                label={f('value')}
                inputMode="decimal"
                value={v.values?.[i] == null ? '' : String(v.values[i])}
                onChange={(e) => {
                  const n = Number(e.target.value.replace(',', '.'));
                  onChange({ values: setAt(v.values ?? [], i, Number.isNaN(n) ? 0 : n) });
                }}
              />
            )}
          />
          <div className="grid grid-cols-2 gap-3">
            <TextField label={f('prefix')} value={v.prefix} onChange={(e) => onChange({ prefix: e.target.value })} />
            <TextField label={f('suffix')} value={v.suffix} onChange={(e) => onChange({ suffix: e.target.value })} />
          </div>
        </div>
      );
    case 'timeline':
      return (
        <Pairs
          rows={v.items}
          left={(i) => (
            <TextField label={f('date')} value={v.labels?.[i] ?? ''} onChange={(e) => onChange({ labels: setAt(v.labels ?? [], i, e.target.value) })} />
          )}
          right={(i) => (
            <TextField label={f('event')} value={v.items[i]} onChange={(e) => onChange({ items: setAt(v.items, i, e.target.value) })} />
          )}
        />
      );
    case 'cycle':
      return (
        <div className="space-y-3">
          <TextField label={f('center')} value={v.title} onChange={(e) => onChange({ title: e.target.value })} />
          <Lines label={f('stages')} value={v.items} onChange={(items) => onChange({ items })} />
        </div>
      );
    case 'iconGrid':
      return (
        <Pairs
          rows={v.items}
          left={(i) => <ReadOnly label={f('icon')} value={v.icons?.[i] ?? '—'} mono />}
          right={(i) => (
            <TextField label={f('point')} value={v.items[i]} onChange={(e) => onChange({ items: setAt(v.items, i, e.target.value) })} />
          )}
        />
      );
    // ---- ANIMATED_2D (9.4): pose and icons stay as the AI chose them; texts can change ----
    case 'character':
      return (
        <div className="space-y-3">
          <ReadOnly label={f('pose')} value={t(`pose.${v.pose}`, { defaultValue: v.pose ?? '' })} />
          <TextField label={f('speech')} value={v.text} onChange={(e) => onChange({ text: e.target.value })} />
        </div>
      );
    case 'iconLink':
      return (
        <Pairs
          rows={v.items}
          left={(i) => <ReadOnly label={f('icon')} value={v.icons?.[i] ?? '—'} mono />}
          right={(i) => <TextField label={f('barLabel')} value={v.items[i]} onChange={(e) => onChange({ items: setAt(v.items, i, e.target.value) })} />}
        />
      );
    case 'metaphor':
      return (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label={f('idea')} value={v.title} onChange={(e) => onChange({ title: e.target.value })} />
          <TextField label={f('comparison')} value={v.subtitle} onChange={(e) => onChange({ subtitle: e.target.value })} />
        </div>
      );
    case 'beforeAfter':
      return (
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-3">
            <TextField label={f('before')} value={v.leftTitle} onChange={(e) => onChange({ leftTitle: e.target.value })} />
            <Lines label={f('left')} value={v.left} onChange={(left) => onChange({ left })} />
          </div>
          <div className="space-y-3">
            <TextField label={f('after')} value={v.rightTitle} onChange={(e) => onChange({ rightTitle: e.target.value })} />
            <Lines label={f('right')} value={v.right} onChange={(right) => onChange({ right })} />
          </div>
        </div>
      );
    case 'zoom':
      return (
        <div className="space-y-3">
          <TextField label={f('object')} value={v.title} onChange={(e) => onChange({ title: e.target.value })} />
          <Lines label={f('details')} value={v.items} onChange={(items) => onChange({ items })} />
        </div>
      );
    default:
      return null;
  }
};

/** Before saving: drop empty lines and spaces around texts, like the AI would have written them. */
export function cleanScript(script: VideoScript): VideoScript {
  const lines = (l: string[] | undefined) => (l ?? []).map((x) => x.trim()).filter(Boolean);
  return {
    ...script,
    title: script.title.trim(),
    scenes: script.scenes.map((s) => ({
      ...s,
      narration: s.narration.trim(),
      onScreenText: s.onScreenText.trim(),
      imagePrompt: s.imagePrompt.trim(),
      visual: s.visual
        ? {
            ...s.visual,
            // paired lists keep every position, so items still match their values / labels / icons
            items: PAIRED_TYPES.includes(s.visual.type as VisualType) ? (s.visual.items ?? []).map((x) => x.trim()) : lines(s.visual.items),
            left: lines(s.visual.left),
            right: lines(s.visual.right),
            ...(s.visual.labels ? { labels: s.visual.labels.map((x) => x.trim()) } : {}),
          }
        : s.visual,
    })),
  };
}
