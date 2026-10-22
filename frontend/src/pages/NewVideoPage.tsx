import React, { useState } from 'react';
import { useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useQueryClient } from '@tanstack/react-query';
import { AspectRatio, LANGUAGES, Language, projectsApi } from '../api/projects';
import { Alert, Button, Choice, TextArea, useErrorText } from '../components/ui';
import { useCredits } from '../hooks/useCredits';

const DURATIONS = [15, 30, 45, 60, 90] as const;
const MAX_IDEA = 1000;

/** First guess for the video language: the app language (sq -> SQ), else English. */
const defaultLanguage = (appLang: string | undefined): Language => (appLang === 'sq' ? 'SQ' : 'EN');

export const NewVideoPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const errorText = useErrorText();

  const [idea, setIdea] = useState('');
  const [language, setLanguage] = useState<Language>(defaultLanguage(i18n.resolvedLanguage));
  const [durationSeconds, setDuration] = useState<number>(30);
  const [aspectRatio, setAspect] = useState<AspectRatio>('PORTRAIT');
  const [ideaError, setIdeaError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const balance = useCredits().data?.balance;
  const tooLong = balance != null && durationSeconds > balance;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const text = idea.trim();
    if (!text) return setIdeaError(t('errors.required'));
    if (text.length > MAX_IDEA) return setIdeaError(t('newVideo.ideaTooLong'));
    setIdeaError(undefined);
    setBusy(true);
    setError(null);
    try {
      const project = await projectsApi.create({ idea: text, language, durationSeconds, aspectRatio });
      queryClient.setQueryData(['project', project.id], project);
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
      navigate(`/projects/${project.id}`);
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  };

  const examples = [t('newVideo.example1'), t('newVideo.example2'), t('newVideo.example3')];

  return (
    <section className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{t('newVideo.title')}</h1>
      <p className="mt-2 text-muted">{t('newVideo.subtitle')}</p>

      <form onSubmit={submit} noValidate className="mt-6 space-y-6">
        {error ? <Alert>{error}</Alert> : null}

        <div>
          <TextArea
            label={t('newVideo.idea')}
            placeholder={t('newVideo.ideaPlaceholder')}
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

        <Choice
          label={t('newVideo.duration')}
          value={durationSeconds}
          onChange={setDuration}
          columns="grid-cols-5"
          options={DURATIONS.map((d) => ({ value: d, label: t('newVideo.seconds', { n: d }) }))}
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

        <div className="flex flex-col-reverse items-stretch gap-3 border-t border-line pt-5 sm:flex-row sm:items-center sm:justify-between">
          <div className="text-center text-sm sm:text-left">
            <p className="text-muted">
              {t('newVideo.credits', { n: durationSeconds })}
              {balance != null ? ` · ${t('credits.youHave', { n: balance })}` : ''}
            </p>
            {tooLong ? <p className="mt-1 text-danger">{t('credits.notEnough', { need: durationSeconds, n: balance })}</p> : null}
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
