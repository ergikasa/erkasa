import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import {
  AUDIENCES,
  AspectRatio,
  Audience,
  CAPTION_POSITIONS,
  CAPTION_STYLES,
  CaptionPosition,
  CaptionStyle,
  LANGUAGES,
  Language,
  PURPOSES,
  PURPOSE_DEFAULTS,
  Purpose,
  TEMPLATES,
  THEMES,
  TONES,
  Template,
  Theme,
  Tone,
  VOICE_SPEEDS,
  projectsApi,
} from '../api/projects';
import { Alert, Button, Choice, TextArea, useErrorText } from '../components/ui';
import { useCredits } from '../hooks/useCredits';

/*
 * "New video" wizard (9.1).
 *   Step 1: what do you want to create? A purpose card sets template, tone, length and format.
 *   Step 2: the idea + audience, tone, visual style, format, length, language; "More options" for
 *           voice speed and captions.
 */

const DURATIONS = [30, 60, 90] as const;
const MAX_IDEA = 1000;
/** Shown in step 1 but not made yet (phase 9 adds them after the first two templates). */
const COMING_SOON = ['STORYTELLING', 'FACTS'] as const;

/** First guess for the video language: the app language (sq -> SQ), else English. */
const defaultLanguage = (appLang: string | undefined): Language => (appLang === 'sq' ? 'SQ' : 'EN');

export const NewVideoPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errorText = useErrorText();

  const [purpose, setPurpose] = useState<Purpose | null>(null);
  const [idea, setIdea] = useState('');
  const [language, setLanguage] = useState<Language>(defaultLanguage(i18n.resolvedLanguage));
  const [durationSeconds, setDuration] = useState<number>(60);
  const [aspectRatio, setAspect] = useState<AspectRatio>('LANDSCAPE');
  const [audience, setAudience] = useState<Audience>('BEGINNER');
  const [tone, setTone] = useState<Tone>('EDUCATIONAL');
  const [template, setTemplate] = useState<Template>('ANIMATED_2D');
  const [theme, setTheme] = useState<Theme>('DARK');
  const [voiceSpeed, setVoiceSpeed] = useState<number>(100);
  const [captionStyle, setCaptionStyle] = useState<CaptionStyle>('KARAOKE');
  const [captionPosition, setCaptionPosition] = useState<CaptionPosition>('BOTTOM');

  const [ideaError, setIdeaError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const balance = useCredits().data?.balance;
  const tooLong = balance != null && durationSeconds > balance;

  /** A card was clicked: take its defaults and go to step 2. */
  const choose = (p: Purpose) => {
    const d = PURPOSE_DEFAULTS[p];
    setPurpose(p);
    setTemplate(d.template);
    setTone(d.tone);
    setDuration(d.durationSeconds);
    setAspect(d.aspectRatio);
    window.scrollTo?.({ top: 0 });
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!purpose) return;
    const text = idea.trim();
    if (!text) return setIdeaError(t('errors.required'));
    if (text.length > MAX_IDEA) return setIdeaError(t('newVideo.ideaTooLong'));
    setIdeaError(undefined);
    setBusy(true);
    setError(null);
    try {
      const project = await projectsApi.create({
        idea: text,
        language,
        durationSeconds,
        aspectRatio,
        options: { purpose, template, audience, tone, theme, voiceSpeed, captionStyle, captionPosition },
      });
      queryClient.setQueryData(['project', project.id], project);
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/projects/${project.id}`);
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  };

  // ---------- step 1 ----------

  if (!purpose) {
    return (
      <section className="mx-auto max-w-3xl">
        <p className="text-sm font-medium text-accent">{t('newVideo.step', { n: 1 })}</p>
        <h1 className="mt-1 text-2xl font-bold tracking-tight sm:text-3xl">{t('newVideo.purposeTitle')}</h1>
        <p className="mt-2 text-muted">{t('newVideo.purposeSubtitle')}</p>

        <div className="mt-6 grid gap-3 sm:grid-cols-2">
          {PURPOSES.map((p) => {
            const d = PURPOSE_DEFAULTS[p];
            return (
              <button
                key={p}
                type="button"
                onClick={() => choose(p)}
                className="group flex items-start gap-4 rounded-2xl border border-line bg-surface p-4 text-left transition hover:border-accent/70 hover:bg-accent/5 focus:border-accent focus:outline-none"
              >
                <PurposeIcon purpose={p} />
                <span className="min-w-0">
                  <span className="block text-[15px] font-semibold text-text">{t(`newVideo.purposes.${p}.name`)}</span>
                  <span className="mt-1 block text-sm text-muted">{t(`newVideo.purposes.${p}.desc`)}</span>
                  <span className="mt-2 block text-xs text-muted/80">
                    {t('newVideo.seconds', { n: d.durationSeconds })} · {t(`newVideo.ratio.${d.aspectRatio}`)} ·{' '}
                    {t(`newVideo.templates.${d.template}`)}
                  </span>
                </span>
              </button>
            );
          })}
          {COMING_SOON.map((p) => (
            <div
              key={p}
              aria-disabled
              className="flex items-start gap-4 rounded-2xl border border-dashed border-line p-4 opacity-60"
            >
              <PurposeIcon purpose={p} />
              <span className="min-w-0">
                <span className="flex items-center gap-2 text-[15px] font-semibold text-text">
                  {t(`newVideo.purposes.${p}.name`)}
                  <span className="rounded-full border border-line px-2 py-0.5 text-[11px] font-medium text-muted">
                    {t('newVideo.soon')}
                  </span>
                </span>
                <span className="mt-1 block text-sm text-muted">{t(`newVideo.purposes.${p}.desc`)}</span>
              </span>
            </div>
          ))}
        </div>
      </section>
    );
  }

  // ---------- step 2 ----------

  const examples = [1, 2, 3].map((n) => t(`newVideo.purposes.${purpose}.example${n}`));

  return (
    <section className="mx-auto max-w-2xl">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-medium text-accent">{t('newVideo.step', { n: 2 })}</p>
        <button
          type="button"
          onClick={() => setPurpose(null)}
          className="min-h-9 rounded-full border border-line px-3 text-sm text-muted transition hover:border-accent/60 hover:text-text"
        >
          ← {t('newVideo.change')}
        </button>
      </div>
      <h1 className="mt-1 flex items-center gap-3 text-2xl font-bold tracking-tight sm:text-3xl">
        {t(`newVideo.purposes.${purpose}.name`)}
      </h1>
      <p className="mt-2 text-muted">{t('newVideo.subtitle')}</p>

      <form onSubmit={submit} noValidate className="mt-6 space-y-6">
        {error ? <Alert>{error}</Alert> : null}

        <div>
          <TextArea
            label={t('newVideo.idea')}
            placeholder={t(`newVideo.purposes.${purpose}.example1`)}
            rows={3}
            maxLength={MAX_IDEA + 100}
            value={idea}
            onChange={(e) => setIdea(e.target.value)}
            error={ideaError}
            hint={
              <span className="flex justify-between gap-2">
                <span>{t('newVideo.ideaHint')}</span>
                <span className={idea.length > MAX_IDEA ? 'text-danger' : ''}>
                  {idea.length}/{MAX_IDEA}
                </span>
              </span>
            }
          />
          <div className="mt-3 flex flex-wrap items-center gap-2" aria-label={t('newVideo.examples')}>
            <span className="text-xs text-muted">{t('newVideo.examples')}:</span>
            {examples.map((ex) => (
              <button
                key={ex}
                type="button"
                onClick={() => setIdea(ex)}
                className="min-h-9 rounded-full border border-line bg-surface px-3 text-sm text-muted transition hover:border-accent/60 hover:text-text"
              >
                {ex}
              </button>
            ))}
          </div>
        </div>

        <Choice
          label={t('newVideo.audience')}
          value={audience}
          onChange={setAudience}
          options={AUDIENCES.map((a) => ({ value: a, label: t(`newVideo.audiences.${a}`), hint: t(`newVideo.audienceHints.${a}`) }))}
        />

        <Choice
          label={t('newVideo.tone')}
          value={tone}
          onChange={setTone}
          options={TONES.map((x) => ({ value: x, label: t(`newVideo.tones.${x}`) }))}
        />

        <Choice
          label={t('newVideo.style')}
          value={template}
          onChange={setTemplate}
          columns="grid-cols-1 sm:grid-cols-3"
          options={TEMPLATES.map((x) => ({ value: x, label: t(`newVideo.templates.${x}`), hint: t(`newVideo.templateHints.${x}`) }))}
        />

        <Choice
          label={t('newVideo.theme')}
          value={theme}
          onChange={setTheme}
          options={THEMES.map((x) => ({ value: x, label: <ThemeLabel theme={x} text={t(`newVideo.themes.${x}`)} /> }))}
        />

        <Choice
          label={t('newVideo.format')}
          value={aspectRatio}
          onChange={setAspect}
          columns="grid-cols-1 sm:grid-cols-3"
          options={[
            { value: 'PORTRAIT' as const, label: <FormatLabel ratio="PORTRAIT" text={t('newVideo.portrait')} />, hint: t('newVideo.portraitHint') },
            { value: 'LANDSCAPE' as const, label: <FormatLabel ratio="LANDSCAPE" text={t('newVideo.landscape')} />, hint: t('newVideo.landscapeHint') },
            { value: 'SQUARE' as const, label: <FormatLabel ratio="SQUARE" text={t('newVideo.square')} />, hint: t('newVideo.squareHint') },
          ]}
        />

        <Choice
          label={t('newVideo.duration')}
          value={durationSeconds}
          onChange={setDuration}
          options={DURATIONS.map((d) => ({ value: d, label: t('newVideo.seconds', { n: d }) }))}
        />

        <div>
          <label htmlFor="video-language" className="mb-1.5 block text-sm font-medium text-muted">
            {t('newVideo.language')}
          </label>
          <select
            id="video-language"
            value={language}
            onChange={(e) => setLanguage(e.target.value as Language)}
            className="block min-h-12 w-full appearance-none rounded-xl border border-line bg-surface px-4 text-text focus:border-accent focus:outline-none"
          >
            {LANGUAGES.map((l) => (
              <option key={l} value={l}>
                {t(`languages.${l}`)}
              </option>
            ))}
          </select>
        </div>

        <details className="group rounded-2xl border border-line bg-surface/40 px-4 py-3">
          <summary className="flex min-h-9 cursor-pointer list-none items-center justify-between text-sm font-semibold text-text">
            {t('newVideo.more')}
            <span className="text-muted transition group-open:rotate-180" aria-hidden>
              ▾
            </span>
          </summary>
          <div className="mt-4 space-y-6 pb-1">
            <Choice
              label={t('newVideo.voiceSpeed')}
              value={voiceSpeed}
              onChange={setVoiceSpeed}
              options={VOICE_SPEEDS.map((s) => ({ value: s, label: t(`newVideo.speeds.s${s}`) }))}
            />
            <Choice
              label={t('newVideo.captions')}
              value={captionStyle}
              onChange={setCaptionStyle}
              columns="grid-cols-2 sm:grid-cols-4"
              options={CAPTION_STYLES.map((x) => ({ value: x, label: t(`newVideo.captionStyles.${x}`) }))}
            />
            {captionStyle !== 'NONE' ? (
              <Choice
                label={t('newVideo.captionPosition')}
                value={captionPosition}
                onChange={setCaptionPosition}
                options={CAPTION_POSITIONS.map((x) => ({ value: x, label: t(`newVideo.positions.${x}`) }))}
              />
            ) : null}
          </div>
        </details>

        <div className="flex flex-col-reverse items-stretch gap-3 border-t border-line pt-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="text-center text-sm sm:text-left">
            <p className="text-muted">
              {t('newVideo.credits', { n: durationSeconds })}
              {balance != null ? ` · ${t('credits.youHave', { n: balance })}` : ''}
            </p>
            {tooLong ? (
              <p className="mt-1 text-danger">
                {t('credits.notEnough', { need: durationSeconds, n: balance })}{' '}
                <Link to="/credits" className="font-semibold text-accent underline underline-offset-4">
                  {t('credits.buyMore')}
                </Link>
              </p>
            ) : null}
          </div>
          <Button type="submit" loading={busy} className="sm:min-w-56">
            {t('newVideo.submit')}
          </Button>
        </div>
      </form>
    </section>
  );
};

/** A small frame in the shape of the format, next to its name. */
const FormatLabel: React.FC<{ ratio: AspectRatio; text: string }> = ({ ratio, text }) => {
  const size = { PORTRAIT: 'h-5 w-3', LANDSCAPE: 'h-3 w-5', SQUARE: 'size-4' }[ratio];
  return (
    <span className="inline-flex items-center gap-2">
      <span className={`inline-block rounded-[3px] border-2 border-current ${size}`} aria-hidden />
      {text}
    </span>
  );
};

/** Background + accent colour of each theme (the render worker uses the same ones from 9.3). */
const THEME_COLORS: Record<Theme, [string, string]> = {
  DARK: ['#0B1020', '#22D3EE'],
  LIGHT: ['#F8FAFC', '#2563EB'],
  VIBRANT: ['#3B0764', '#F97316'],
};

const ThemeLabel: React.FC<{ theme: Theme; text: string }> = ({ theme, text }) => {
  const [bg, accent] = THEME_COLORS[theme];
  return (
    <span className="inline-flex items-center gap-2">
      <span className="relative inline-block size-5 rounded-md border border-line" style={{ background: bg }} aria-hidden>
        <span className="absolute bottom-0.5 right-0.5 size-2 rounded-full" style={{ background: accent }} />
      </span>
      {text}
    </span>
  );
};

/** Simple line icons for the purpose cards. */
const PurposeIcon: React.FC<{ purpose: string }> = ({ purpose }) => {
  const paths: Record<string, React.ReactNode> = {
    EXPLAINER: (
      <>
        <path d="M9 18h6M10 21h4" />
        <path d="M12 3a6 6 0 0 0-3.5 10.9c.6.4 1 1.1 1 1.8V16h5v-.3c0-.7.4-1.4 1-1.8A6 6 0 0 0 12 3Z" />
      </>
    ),
    SHORT_FORM: (
      <>
        <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
        <path d="m11 9 3.5 3-3.5 3V9Z" />
      </>
    ),
    BUSINESS: (
      <>
        <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
      </>
    ),
    STORYTELLING: (
      <>
        <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5v-15Z" />
        <path d="M4 20.5A2.5 2.5 0 0 0 6.5 23H20v-5" />
      </>
    ),
    FACTS: (
      <>
        <path d="M7 7h4v4c0 2-1 3.5-3 4M14 7h4v4c0 2-1 3.5-3 4" />
      </>
    ),
  };
  return (
    <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-accent/10 text-accent">
      <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        {paths[purpose]}
      </svg>
    </span>
  );
};