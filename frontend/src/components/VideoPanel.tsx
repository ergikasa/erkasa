import React from 'react';
import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { Project, projectsApi } from '../api/projects';
import { useBlobUrl } from '../hooks/useBlobUrl';
import { Alert, Spinner, buttonClass } from './ui';

/** The finished video: watch it here, download the MP4. */
export const VideoPanel: React.FC<{ project: Project }> = ({ project }) => {
  const { t } = useTranslation();
  const { url, error } = useBlobUrl(projectsApi.videoPath(project.id));
  // the whole video always fits on the screen: tall formats by height, wide ones by width
  const frame =
    project.aspectRatio === 'PORTRAIT'
      ? 'aspect-[9/16] h-[min(56dvh,720px)] max-w-full'
      : project.aspectRatio === 'SQUARE'
        ? 'aspect-square w-full max-w-[min(100%,56dvh)]'
        : 'aspect-video w-full';
  return (
    <div>
      <h2 className="text-lg font-semibold">{t('project.watch')}</h2>
      <div className={`mx-auto mt-4 flex items-center justify-center overflow-hidden rounded-2xl border border-line bg-black ${frame}`}>
        {url ? (
          <video src={url} controls playsInline className="size-full" aria-label={project.title ?? project.idea} />
        ) : error ? (
          <Alert>{t('errors.generic')}</Alert>
        ) : (
          <Spinner />
        )}
      </div>
      <div className="mt-5 flex flex-col gap-3 sm:flex-row">
        {url ? (
          <a href={url} download={`erkasa-${slug(project.title ?? project.idea)}.mp4`} className={buttonClass('primary', true, 'sm:w-auto')}>
            {t('project.download')}
          </a>
        ) : null}
        <Link to="/new" className={buttonClass('secondary', true, 'sm:w-auto')}>
          {t('project.another')}
        </Link>
      </div>
    </div>
  );
};

const slug = (s: string) =>
  s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^\p{L}\p{N}]+/gu, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 40) || 'video';
