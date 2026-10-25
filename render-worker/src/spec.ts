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

/**
 * What the scene SHOWS. Chosen by the LLM per scene:
 * explain with graphics, use an AI image only when a real picture helps.
 * Items (steps, list items, compare rows) appear when the voice says them.
 */
export type Visual =
  | { type: 'image' }
  | { type: 'title'; title: string; subtitle?: string }
  | { type: 'flow'; steps: string[] }
  | { type: 'list'; items: string[] }
  | { type: 'stat'; value: number; prefix?: string; suffix?: string; label: string }
  | { type: 'compare'; leftTitle: string; rightTitle: string; left: string[]; right: string[] }
  | { type: 'code'; language?: string; code: string }
  | { type: 'cta'; text: string; handle?: string }
  // INFOGRAPHIC template (9.3)
  | { type: 'bars'; title?: string; items: string[]; values: number[]; prefix?: string; suffix?: string }
  | { type: 'timeline'; items: string[]; labels: string[] }
  | { type: 'cycle'; title?: string; items: string[] }
  | { type: 'iconGrid'; items: string[]; icons: string[] }
  // ANIMATED_2D template (9.4)
  | { type: 'character'; pose: string; text?: string; icons: string[] }
  | { type: 'iconLink'; items: string[]; icons: string[] }
  | { type: 'metaphor'; title: string; subtitle: string; icons: string[] }
  | { type: 'beforeAfter'; leftTitle: string; rightTitle: string; left: string[]; right: string[]; icons: string[] }
  | { type: 'zoom'; title: string; icons: string[]; items: string[] }
  // scenes that show an action (9.4b); `pose` = the 2D host next to the diagram ('' = no host)
  | { type: 'exchange'; actors: string[]; icons: string[]; messages: Message[]; result?: string; pose?: string }
  | { type: 'browser'; url: string; items: string[]; pose?: string };

/** One message of an exchange: from party `from` to party `to` (0, 1, 2), with a short label. */
export type Message = { from: number; to: number; label: string };

/** A sound effect the LLM chose: plays on that word of the narration ("" = scene start). */
export type SoundCue = { sound: string; onWord?: string };

/** A sound effect ready to play: the worker resolved the file and the frame (see sfx.ts). */
export type Effect = { file: string; frame: number; volume: number };

export type SpecScene = {
  /** the AI image; only needed when visual is 'image' (or missing) */
  image?: string;
  /** voice-over; may be missing in demos (silent scene of audioSeconds) */
  audio?: string;
  /** default { type: 'image' } */
  visual?: Visual;
  /** length of the voice-over of this scene */
  audioSeconds: number;
  /** a few big words on screen at the start of the scene (may be empty) */
  onScreenText?: string;
  /** word timings, from the start of THIS scene's audio */
  words: Word[];
  /** 0-2 sound effects chosen by the LLM (5.6) */
  sfx?: SoundCue[];
};

export type RenderSpec = {
  aspectRatio: AspectRatio;
  fps?: number;
  title?: string;
  /** background music file; null/absent = no music */
  music?: string | null;
  /** 0..1, default 0.06: under the voice, never over it */
  musicVolume?: number;
  /** automatic whooshes between scenes and pops when items appear, default true (5.6) */
  autoSfx?: boolean;
  /** filled in by the worker from the sfx/ library: what plays when (5.6) */
  effects?: Effect[];
  /** word-by-word subtitles, default true */
  captions?: boolean;
  /** colour theme: DARK (default), LIGHT, VIBRANT (9.3, see theme.ts) */
  theme?: string;
  scenes: SpecScene[];
};

/**
 * The backend writes one flat object for every visual type (see backend Visual.java):
 * { type, title, subtitle, items, value, prefix, suffix, label, leftTitle, rightTitle,
 *   left, right, code, language, text, handle, values, labels, icons, pose } with unused fields empty.
 * This turns it (or an already typed Visual) into the typed form the templates use.
 */
export function normalizeVisual(raw: unknown): Visual {
  const v = (raw ?? {}) as Record<string, unknown>;
  const str = (k: string) => (typeof v[k] === 'string' ? (v[k] as string) : '');
  const list = (k: string) => (Array.isArray(v[k]) ? (v[k] as unknown[]).map(String).filter((x) => x.trim()) : []);
  // lists that go in pairs (items + values / labels / icons): keep the positions, only trim
  const rawList = (k: string) => (Array.isArray(v[k]) ? (v[k] as unknown[]).map((x) => String(x ?? '').trim()) : []);
  switch (v.type) {
    case 'title':
      return { type: 'title', title: str('title'), subtitle: str('subtitle') || undefined };
    case 'flow':
      return { type: 'flow', steps: list('steps').length ? list('steps') : list('items') };
    case 'list':
      return { type: 'list', items: list('items') };
    case 'stat':
      return { type: 'stat', value: Number(v.value) || 0, prefix: str('prefix'), suffix: str('suffix'), label: str('label') };
    case 'compare':
      return { type: 'compare', leftTitle: str('leftTitle'), rightTitle: str('rightTitle'), left: list('left'), right: list('right') };
    case 'code':
      return { type: 'code', code: str('code'), language: str('language') || undefined };
    case 'cta':
      return { type: 'cta', text: str('text'), handle: str('handle') || undefined };
    case 'bars': {
      const items = rawList('items');
      const values = Array.isArray(v.values) ? (v.values as unknown[]).map((x) => Math.max(0, Number(x) || 0)) : [];
      return {
        type: 'bars',
        title: str('title') || undefined,
        items,
        values: items.map((_, i) => values[i] ?? 0),
        prefix: str('prefix') || undefined,
        suffix: str('suffix') || undefined,
      };
    }
    case 'timeline': {
      const items = rawList('items');
      const labels = rawList('labels');
      return { type: 'timeline', items, labels: items.map((_, i) => labels[i] ?? '') };
    }
    case 'cycle':
      return { type: 'cycle', title: str('title') || undefined, items: list('items') };
    case 'iconGrid': {
      const items = rawList('items');
      const icons = rawList('icons');
      return { type: 'iconGrid', items, icons: items.map((_, i) => icons[i] ?? '') };
    }
    case 'character':
      return { type: 'character', pose: str('pose') || 'standing', text: str('text') || undefined, icons: list('icons') };
    case 'iconLink': {
      const items = rawList('items');
      const icons = rawList('icons');
      return { type: 'iconLink', items, icons: items.map((_, i) => icons[i] ?? '') };
    }
    case 'metaphor':
      return { type: 'metaphor', title: str('title'), subtitle: str('subtitle'), icons: rawList('icons') };
    case 'beforeAfter':
      return { type: 'beforeAfter', leftTitle: str('leftTitle'), rightTitle: str('rightTitle'), left: list('left'), right: list('right'), icons: rawList('icons') };
    case 'zoom':
      return { type: 'zoom', title: str('title'), icons: rawList('icons'), items: list('items') };
    case 'exchange': {
      // the backend sends the flat form (items, subtitle); demos may write the typed one (actors, result)
      const actors = rawList('actors').length ? rawList('actors') : rawList('items');
      const icons = rawList('icons');
      const messages = Array.isArray(v.messages)
        ? (v.messages as Record<string, unknown>[])
            .map((m) => ({ from: Number(m?.from) || 0, to: Number(m?.to) || 0, label: String(m?.label ?? '').trim() }))
            .filter((m) => m.label)
        : [];
      return { type: 'exchange', actors, icons: actors.map((_, i) => icons[i] ?? ''), messages, result: str('result') || str('subtitle') || undefined, pose: str('pose') || undefined };
    }
    case 'browser':
      return { type: 'browser', url: str('url') || str('title') || 'example.com', items: list('items'), pose: str('pose') || undefined };
    default:
      return { type: 'image' };
  }
}

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
    const type = s.visual?.type ?? 'image';
    if (type === 'image' && !s.image) throw new Error(`Scene ${i + 1} shows an image but has none`);
    if (!(s.audioSeconds > 0)) throw new Error(`Scene ${i + 1} has no audio length`);
  });
}

/**
 * When each item should appear, in seconds from the scene start.
 * An item appears when the voice says its first word; items the voice never says are
 * spread evenly between the ones that were found. Always in order, never after the voice ends.
 */
export function cueTimes(items: string[], words: Word[], audioSeconds: number): number[] {
  const norm = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}]/gu, '');
  const cues: (number | null)[] = [];
  let from = 0;
  for (const item of items) {
    const key = norm(item.split(/\s+/)[0] ?? '');
    let found: number | null = null;
    if (key.length >= 2) {
      for (let w = from; w < words.length; w++) {
        const word = norm(words[w].word);
        if (word.startsWith(key) || (key.startsWith(word) && word.length >= 3)) {
          found = words[w].startSeconds;
          from = w + 1;
          break;
        }
      }
    }
    cues.push(found);
  }
  // fill the gaps evenly; the first item shows almost at once if not spoken
  const end = Math.max(0.5, audioSeconds * 0.85);
  const result: number[] = [];
  for (let i = 0; i < cues.length; i++) {
    if (cues[i] != null) {
      result.push(cues[i]!);
      continue;
    }
    const prev = i === 0 ? 0.15 : result[i - 1];
    let next = end;
    let gap = 1;
    for (let j = i + 1; j < cues.length; j++) {
      gap++;
      if (cues[j] != null) {
        next = cues[j]!;
        break;
      }
    }
    result.push(i === 0 && cues[0] == null ? 0.15 : prev + (next - prev) / gap);
  }
  // keep the order strictly increasing
  for (let i = 1; i < result.length; i++) {
    result[i] = Math.max(result[i], result[i - 1] + 0.15);
  }
  return result;
}

/** How long a message of an exchange takes to travel from one party to the other (9.4b). */
export const MESSAGE_TRAVEL_SECONDS = 0.75;

/** When each message starts and lands (seconds from the scene start): it leaves when the voice says its label. */
export function messageTimes(messages: Message[], words: Word[], audioSeconds: number): { start: number; arrive: number }[] {
  const cues = cueTimes(messages.map((m) => m.label), words, audioSeconds);
  return cues.map((c) => {
    const start = Math.max(0.2, c - 0.15);
    return { start, arrive: start + MESSAGE_TRAVEL_SECONDS };
  });
}
