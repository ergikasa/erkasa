/**
 * Background music from a local folder, picked by mood.
 *
 *   music/
 *     calm/        *.mp3
 *     upbeat/
 *     dramatic/
 *     inspiring/
 *     mysterious/
 *     fun/
 *
 * The backend sends music = "mood:upbeat" (the mood the LLM chose for the script). The worker
 * picks one track from that folder; the same job always gets the same track, so a retried
 * render sounds the same. No folder or no files = a video without music (never an error).
 *
 * Only use tracks you are allowed to use in videos you publish (e.g. your own, or royalty-free
 * libraries whose license allows commercial use without attribution).
 */
import fs from 'node:fs';
import path from 'node:path';

export const MOODS = ['calm', 'upbeat', 'dramatic', 'inspiring', 'mysterious', 'fun'] as const;
const AUDIO = /\.(mp3|m4a|aac|wav|ogg)$/i;

export function musicDir(): string {
  return path.resolve(process.env.MUSIC_DIR ?? path.join(process.cwd(), 'music'));
}

/** "mood:calm" -> absolute path of a track, or null. Anything else is returned unchanged. */
export function resolveMusic(music: string | null | undefined, seed: string): string | null {
  if (!music) return null;
  if (!music.startsWith('mood:')) return music;
  const mood = music.slice(5).trim().toLowerCase();
  const pick = (dir: string): string | null => {
    if (!fs.existsSync(dir)) return null;
    const files = fs.readdirSync(dir).filter((f) => AUDIO.test(f)).sort();
    if (!files.length) return null;
    return path.join(dir, files[hash(seed) % files.length]);
  };
  // the mood's folder, else any track at all, else no music
  return pick(path.join(musicDir(), mood)) ?? pickAny(seed, pick);
}

function pickAny(seed: string, pick: (dir: string) => string | null): string | null {
  for (const mood of MOODS) {
    const found = pick(path.join(musicDir(), mood));
    if (found) return found;
  }
  return pick(musicDir());
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}
