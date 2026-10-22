import { api, apiBlob } from './client';

/* The /api/projects endpoints (backend: ProjectController). */

export const LANGUAGES = ['EN', 'SQ', 'DE', 'ES', 'FR', 'IT', 'MK', 'SR', 'RU'] as const;
export type Language = (typeof LANGUAGES)[number];
export type AspectRatio = 'PORTRAIT' | 'LANDSCAPE' | 'SQUARE';
export type ProjectStatus =
  | 'SCRIPT_PENDING'
  | 'SCRIPT_REVIEW'
  | 'ASSETS_PENDING'
  | 'ASSETS_READY'
  | 'RENDERING'
  | 'DONE'
  | 'FAILED'
  | 'CANCELLED';

export const MIN_DURATION = 15;
export const MAX_DURATION = 90;
/** ScriptValidator.WORDS_PER_SECOND: how fast the voice speaks */
export const WORDS_PER_SECOND = 2.5;

export const VISUAL_TYPES = ['image', 'title', 'flow', 'list', 'stat', 'compare', 'code', 'cta'] as const;
export type VisualType = (typeof VISUAL_TYPES)[number];

/** The flat visual object (backend Visual): every field present, the unused ones empty. */
export type Visual = {
  type: VisualType;
  title: string;
  subtitle: string;
  items: string[];
  value: number | null;
  prefix: string;
  suffix: string;
  label: string;
  leftTitle: string;
  rightTitle: string;
  left: string[];
  right: string[];
  code: string;
  language: string;
  text: string;
  handle: string;
};

export type SoundCue = { sound: string; onWord: string };

export type Scene = {
  narration: string;
  onScreenText: string;
  imagePrompt: string;
  /** null in scripts written before 5.2 = an image scene */
  visual: Visual | null;
  sfx: SoundCue[];
};

export type VideoScript = { title: string; musicMood: string; scenes: Scene[] };

export type JobSummary = {
  id: string;
  kind: 'SCRIPT' | 'ASSETS' | 'RENDER';
  status: string;
  errorCode: string | null;
  startedAt: string | null;
  finishedAt: string | null;
};

export type Project = {
  id: string;
  status: ProjectStatus;
  idea: string;
  language: Language;
  durationSeconds: number;
  aspectRatio: AspectRatio;
  voiceId: string | null;
  title: string | null;
  script: VideoScript | null;
  estimatedCredits: number;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  jobs: JobSummary[];
};

export type CreateProject = {
  idea: string;
  language: Language;
  durationSeconds: number;
  aspectRatio: AspectRatio;
};

export type StepStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

export type StepView = {
  status: StepStatus;
  attempts: number;
  /** where to download the file once SUCCEEDED */
  url: string | null;
  meta: Record<string, unknown> | null;
  errorCode: string | null;
  errorMessage: string | null;
};

/** scenes count from 0; image is null for motion-graphics scenes */
export type SceneAssets = { scene: number; image: StepView | null; voice: StepView | null };
export type Assets = { projectId: string; done: number; total: number; scenes: SceneAssets[] };

export type RedoScene = { image?: boolean; voice?: boolean; imagePrompt?: string; narration?: string };

export const projectsApi = {
  create: (body: CreateProject) => api<Project>('/api/projects', { body }),
  list: (limit = 20) => api<Project[]>(`/api/projects?limit=${limit}`),
  get: (id: string) => api<Project>(`/api/projects/${id}`),
  saveScript: (id: string, script: VideoScript) => api<Project>(`/api/projects/${id}/script`, { method: 'PUT', body: { script } }),
  regenerateScript: (id: string) => api<Project>(`/api/projects/${id}/regenerate-script`, { method: 'POST' }),
  approve: (id: string) => api<Project>(`/api/projects/${id}/approve`, { method: 'POST' }),
  cancel: (id: string) => api<Project>(`/api/projects/${id}/cancel`, { method: 'POST' }),
  retry: (id: string) => api<Project>(`/api/projects/${id}/retry`, { method: 'POST' }),
  assets: (id: string) => api<Assets>(`/api/projects/${id}/assets`),
  redoScene: (id: string, scene: number, body: RedoScene) => api<Project>(`/api/projects/${id}/scenes/${scene}/redo`, { body }),
  render: (id: string) => api<Project>(`/api/projects/${id}/render`, { method: 'POST' }),
  /** the MP4, the voice or the image of a scene, as a Blob (needs the login token) */
  file: (path: string, signal?: AbortSignal) => apiBlob(path, signal),
  videoPath: (id: string) => `/api/projects/${id}/video`,
};

/** Words in a text, the way the backend counts them. */
export const countWords = (s: string | null | undefined) => (s ?? '').trim().split(/\s+/).filter(Boolean).length;

/** About how long the voice will speak this script, in seconds. */
export const spokenSeconds = (script: VideoScript) =>
  Math.round(script.scenes.reduce((n, s) => n + countWords(s.narration), 0) / WORDS_PER_SECOND);

/** The scene's visual type; old scripts without a visual are image scenes. */
export const visualType = (s: Scene): VisualType => (s.visual?.type ?? 'image') as VisualType;

/** Statuses where the backend works on its own and the page should keep checking. */
export const isWorking = (s: ProjectStatus) => s === 'SCRIPT_PENDING' || s === 'ASSETS_PENDING' || s === 'RENDERING';
