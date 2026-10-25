/**
 * Sound effects from a local library (5.6).
 *
 *   sfx/
 *     whoosh/   *.wav | *.mp3 ...
 *     pop/
 *     ding/
 *     ...       (one folder per key in SOUNDS)
 *
 * Two kinds of effects:
 *   - automatic: a whoosh at every cut between scenes, a pop when a list / flow / compare item
 *     appears (same timing as the graphics, see cueTimes);
 *   - chosen by the LLM: 0-2 per scene, on a word of the narration (scene.sfx).
 *
 * `npm run make-sfx` writes a free starter pack (synthesized, no license needed). Better
 * sounds can be added to the same folders at any time, e.g. from Pixabay or Mixkit (check that
 * the license allows commercial use without attribution). A missing folder = that effect is
 * skipped (never an error).
 */
import fs from 'node:fs';
import path from 'node:path';
import { Effect, RenderSpec, SpecScene, Word, cueTimes, fpsOf, normalizeVisual, sceneFrames } from './spec';

/** key -> loudness (0..1). Same keys as the backend's SoundEffects.CATALOG. */
export const SOUNDS: Record<string, number> = {
  whoosh: 0.3,
  swoosh: 0.3,
  riser: 0.3,
  impact: 0.45,
  pop: 0.22,
  click: 0.3,
  tick: 0.3,
  ding: 0.35,
  chime: 0.35,
  notification: 0.35,
  success: 0.35,
  error: 0.2,
  typing: 0.3,
  beep: 0.15,
  glitch: 0.3,
  cash: 0.4,
  coin: 0.18,
  camera: 0.4,
  heartbeat: 0.5,
  boing: 0.35,
};

const AUDIO = /\.(wav|mp3|m4a|aac|ogg)$/i;
/** the whoosh starts a little before the cut, so it "carries" the next scene in */
const WHOOSH_LEAD_FRAMES = 5;
/** two pops closer than this are one pop */
const MIN_GAP_FRAMES = 6;

export function sfxDir(): string {
  return path.resolve(process.env.SFX_DIR ?? path.join(process.cwd(), 'sfx'));
}

export type PlannedSound = { sound: string; frame: number; volume: number; auto: boolean };

/** When every effect plays, in frames from the start of the video. Pure: no files needed. */
export function planSoundEffects(spec: RenderSpec): PlannedSound[] {
  const fps = fpsOf(spec);
  const auto = spec.autoSfx !== false;
  const planned: PlannedSound[] = [];
  let start = 0;
  spec.scenes.forEach((scene, i) => {
    if (auto && i > 0) {
      planned.push({ sound: 'whoosh', frame: Math.max(0, start - WHOOSH_LEAD_FRAMES), volume: SOUNDS.whoosh, auto: true });
    }
    const chosen = (scene.sfx ?? [])
      .filter((c) => c && SOUNDS[c.sound] !== undefined)
      .map((c) => ({
        sound: c.sound,
        frame: start + Math.round(wordTime(c.onWord, scene.words) * fps),
        volume: SOUNDS[c.sound],
        auto: false,
      }));
    if (auto) {
      let last = -Infinity;
      for (const t of itemTimes(scene)) {
        const frame = start + Math.round(t * fps);
        // the LLM's sound on the same moment wins; never two pops on top of each other
        if (frame - last < MIN_GAP_FRAMES || chosen.some((c) => Math.abs(c.frame - frame) < MIN_GAP_FRAMES)) continue;
        planned.push({ sound: 'pop', frame, volume: SOUNDS.pop, auto: true });
        last = frame;
      }
    }
    planned.push(...chosen);
    start += sceneFrames(scene, fps);
  });
  return planned.sort((a, b) => a.frame - b.frame);
}

/** Seconds into the scene where this word is spoken; the scene start if it is not found. */
function wordTime(onWord: string | undefined, words: Word[]): number {
  const norm = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}]/gu, '');
  const key = norm((onWord ?? '').trim().split(/\s+/)[0] ?? '');
  if (key.length < 2) return 0.1;
  const hit = words.find((w) => norm(w.word) === key) ?? words.find((w) => norm(w.word).startsWith(key));
  return hit ? hit.startSeconds : 0.1;
}

/** When the items of a flow / list / compare / infographic appear: the same numbers Graphics.tsx uses. */
function itemTimes(scene: SpecScene): number[] {
  const v = normalizeVisual(scene.visual);
  const words = scene.words ?? [];
  const a = scene.audioSeconds;
  switch (v.type) {
    case 'flow':
      return cueTimes(v.steps, words, a);
    case 'list':
    case 'bars':
    case 'timeline':
    case 'cycle':
    case 'iconGrid':
      return cueTimes(v.items, words, a);
    case 'compare': {
      const left = cueTimes([v.leftTitle, ...v.left], words, a * 0.5);
      const right = cueTimes([v.rightTitle, ...v.right], words.filter((w) => w.startSeconds >= a * 0.4), a)
        .map((c) => Math.max(c, a * 0.45));
      return [...left, ...right].sort((x, y) => x - y);
    }
    default:
      return [];
  }
}

/**
 * Plans the effects and picks a file for each from the library. The same job (seed) always
 * gets the same files, so a retried render sounds the same.
 */
export function withSoundEffects(spec: RenderSpec, seed: string): { spec: RenderSpec; missing: string[] } {
  const dir = sfxDir();
  const files = new Map<string, string[]>();
  const filesOf = (sound: string): string[] => {
    if (!files.has(sound)) {
      const folder = path.join(dir, sound);
      const list = fs.existsSync(folder) ? fs.readdirSync(folder).filter((f) => AUDIO.test(f)).sort() : [];
      files.set(sound, list.map((f) => path.join(folder, f)));
    }
    return files.get(sound)!;
  };
  const missing = new Set<string>();
  const effects: Effect[] = [];
  planSoundEffects(spec).forEach((p, n) => {
    const options = filesOf(p.sound);
    if (!options.length) {
      missing.add(p.sound);
      return;
    }
    effects.push({ file: options[hash(`${seed}:${p.sound}:${n}`) % options.length], frame: p.frame, volume: p.volume });
  });
  return { spec: { ...spec, effects }, missing: [...missing] };
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}
