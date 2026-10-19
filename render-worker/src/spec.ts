/**
 * The render spec: everything the worker needs to make one video.
 * The backend builds it from the project (script + ASSETS job) in phase 5.2.
 *
 * image / audio / music are either an http(s) URL or a file name inside the bundle's
 * public folder (the render CLI copies local files there).
 */

export type AspectRatio = 'PORTRAIT' | 'LANDSCAPE' | 'SQUARE';

export type Word = {
  word: string;
  startSeconds: number;
  endSeconds: number;
};

export type SpecScene = {
  image: string;
  audio: string;
  /** length of the voice-over of this scene */
  audioSeconds: number;
  /** a few big words on screen at the start of the scene (may be empty) */
  onScreenText?: string;
  /** word timings, from the start of THIS scene's audio */
  words: Word[];
};

export type RenderSpec = {
  aspectRatio: AspectRatio;
  fps?: number;
  title?: string;
  /** background music file; null/absent = no music */
  music?: string | null;
  /** 0..1, default 0.12: under the voice, never over it */
  musicVolume?: number;
  /** word-by-word subtitles, default true */
  captions?: boolean;
  scenes: SpecScene[];
};

export const DEFAULT_FPS = 30;

/** Pause after each scene's voice before the next scene starts. */
export const SCENE_TAIL_SECONDS = 0.35;

/** A scene is never shorter than this, even with very short narration. */
export const MIN_SCENE_SECONDS = 1.5;

export const SIZES: Record<AspectRatio, { width: number; height: number }> = {
  PORTRAIT: { width: 1080, height: 1920 }, // Reels, TikTok, Shorts
  LANDSCAPE: { width: 1920, height: 1080 }, // YouTube
  SQUARE: { width: 1080, height: 1080 },
};

export function fpsOf(spec: RenderSpec): number {
  return spec.fps ?? DEFAULT_FPS;
}

export function sceneFrames(scene: SpecScene, fps: number): number {
  const seconds = Math.max(MIN_SCENE_SECONDS, scene.audioSeconds + SCENE_TAIL_SECONDS);
  return Math.ceil(seconds * fps);
}

export function totalFrames(spec: RenderSpec): number {
  const fps = fpsOf(spec);
  return Math.max(1, spec.scenes.reduce((sum, s) => sum + sceneFrames(s, fps), 0));
}

/** Throws with a clear message instead of rendering a broken video. */
export function validateSpec(spec: RenderSpec): void {
  if (!spec || !Array.isArray(spec.scenes) || spec.scenes.length === 0) {
    throw new Error('Render spec has no scenes');
  }
  if (!SIZES[spec.aspectRatio]) {
    throw new Error(`Unknown aspectRatio "${spec.aspectRatio}"`);
  }
  spec.scenes.forEach((s, i) => {
    if (!s.image) throw new Error(`Scene ${i + 1} has no image`);
    if (!s.audio) throw new Error(`Scene ${i + 1} has no audio`);
    if (!(s.audioSeconds > 0)) throw new Error(`Scene ${i + 1} has no audio length`);
  });
}