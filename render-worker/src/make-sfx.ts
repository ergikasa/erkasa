/**
 * Writes the free starter pack of sound effects into sfx/<key>/erkasa-<key>.wav (5.6).
 *
 *   npm run make-sfx            -> writes the missing ones
 *   npm run make-sfx -- --force -> writes all of them again
 *
 * Every sound is synthesized here from sine waves and noise, so there is no license to worry
 * about. For a more polished sound, add more files to the same folders (Pixabay, Mixkit, your
 * own); the worker picks one per use, the same one for the same job.
 */
import fs from 'node:fs';
import path from 'node:path';
import { SOUNDS, sfxDir } from './sfx';

const RATE = 44100;
type Synth = (t: number, rnd: () => number) => number;

/** Seeded noise, so `make-sfx` always writes the same files. */
function random(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

const sin = (f: number, t: number) => Math.sin(2 * Math.PI * f * t);
const decay = (t: number, d: number) => Math.exp(-t / d);
const env = (t: number, attack: number, d: number) => (t < attack ? t / attack : decay(t - attack, d));
const square = (f: number, t: number) => (sin(f, t) >= 0 ? 1 : -1);
const note = (t: number, at: number, f: number, d = 0.25) => (t < at ? 0 : sin(f, t - at) * env(t - at, 0.004, d));

/** Noise through a 3-stage lowpass whose cutoff follows `cutoff(t)`: the body of whooshes. */
function filteredNoise(cutoff: (t: number) => number): Synth {
  const y = [0, 0, 0];
  let low = 0;
  return (t, rnd) => {
    const a = 1 - Math.exp((-2 * Math.PI * cutoff(t)) / RATE);
    let x = rnd() * 2 - 1;
    for (let k = 0; k < y.length; k++) {
      y[k] += a * (x - y[k]);
      x = y[k];
    }
    low += 0.004 * (x - low); // remove the rumble below ~30 Hz
    return x - low;
  };
}

function whoosh(seconds: number, from: number, to: number): [number, Synth] {
  const body = filteredNoise((t) => from + (to - from) * Math.sin((Math.PI * t) / seconds));
  return [seconds, (t, rnd) => body(t, rnd) * Math.sin((Math.PI * t) / seconds) ** 2 * 3];
}

/** key -> [seconds, synth]. One entry per key in SOUNDS. */
const PACK: Record<string, [number, Synth]> = {
  whoosh: whoosh(0.55, 300, 3500),
  swoosh: whoosh(0.3, 800, 6000),
  riser: [1.6, (() => {
    const body = filteredNoise((t) => 200 + 4000 * (t / 1.6) ** 2);
    return (t: number, rnd: () => number) =>
      (body(t, rnd) * 2 + 0.3 * sin(150 + 900 * (t / 1.6) ** 2, t)) * (t / 1.6) ** 1.5 * (t > 1.55 ? (1.6 - t) / 0.05 : 1);
  })()],
  impact: [1.2, (t, rnd) => {
    const f = 40 + 80 * decay(t, 0.08);
    return sin(f, t) * decay(t, 0.35) + (rnd() * 2 - 1) * decay(t, 0.03) * 0.6;
  }],
  pop: [0.12, (t) => sin(900 - 2500 * t, t) * env(t, 0.002, 0.03)],
  click: [0.04, (t, rnd) => (rnd() * 2 - 1) * decay(t, 0.003) + sin(2500, t) * decay(t, 0.004) * 0.5],
  tick: [0.05, (t) => sin(3200, t) * decay(t, 0.006)],
  ding: [1.5, (t) => (sin(1318.5, t) + 0.35 * sin(2637, t) + 0.15 * sin(3955, t)) * env(t, 0.003, 0.45) * 0.7],
  chime: [1.4, (t) => (note(t, 0, 1046.5, 0.5) + note(t, 0.1, 1318.5, 0.5) + note(t, 0.2, 1568, 0.6)) * 0.5],
  notification: [0.6, (t) => (note(t, 0, 880, 0.12) + note(t, 0.13, 1318.5, 0.2)) * 0.8],
  success: [1.0, (t) => (note(t, 0, 523.3, 0.15) + note(t, 0.1, 659.3, 0.15) + note(t, 0.2, 784, 0.15) + note(t, 0.3, 1046.5, 0.4)) * 0.6],
  error: [0.55, (t) => (t < 0.2 ? square(220, t) * env(t, 0.005, 0.12) : t >= 0.25 ? square(170, t - 0.25) * env(t - 0.25, 0.005, 0.15) : 0) * 0.35],
  typing: [1.1, (() => {
    const hits = [0, 0.11, 0.19, 0.33, 0.42, 0.5, 0.64, 0.71, 0.83, 0.97];
    return (t: number, rnd: () => number) => {
      let v = 0;
      for (const h of hits) if (t >= h && t < h + 0.03) v += (rnd() * 2 - 1) * decay(t - h, 0.004) + sin(1800, t - h) * decay(t - h, 0.003) * 0.4;
      return v * 0.8;
    };
  })()],
  beep: [0.2, (t) => sin(1000, t) * (t < 0.01 ? t / 0.01 : t > 0.17 ? (0.2 - t) / 0.03 : 1) * 0.6],
  glitch: [0.45, (() => {
    let held = 0;
    return (t: number, rnd: () => number) => {
      if (Math.floor(t * 400) !== Math.floor((t - 1 / RATE) * 400)) held = rnd() * 2 - 1;
      const on = Math.floor(t * 23) % 3 !== 1 ? 1 : 0;
      return (held * 0.6 + square(120 + 600 * held * held, t) * 0.25) * on * (1 - t / 0.45);
    };
  })()],
  cash: [1.2, (t, rnd) =>
    (rnd() * 2 - 1) * decay(t, 0.02) * 0.5 +
    (note(t, 0.05, 2093, 0.4) + note(t, 0.05, 2637, 0.35) + 0.6 * note(t, 0.12, 3136, 0.45)) * 0.45],
  coin: [0.5, (t) => (t < 0.08 ? square(988, t) : square(1319, t)) * (t < 0.08 ? 1 : decay(t - 0.08, 0.12)) * 0.3],
  camera: [0.3, (t, rnd) =>
    (rnd() * 2 - 1) * (decay(t, 0.008) + (t > 0.09 ? decay(t - 0.09, 0.015) : 0)) * 0.9],
  heartbeat: [0.9, (t) => {
    const thump = (at: number) => (t < at ? 0 : sin(55 - 15 * (t - at), t - at) * env(t - at, 0.005, 0.07));
    return (thump(0) + 0.8 * thump(0.28)) * 1.2;
  }],
  boing: [0.7, (t) => sin(180 + 220 * t + 60 * Math.sin(2 * Math.PI * 14 * t) * decay(t, 0.25), t) * env(t, 0.005, 0.22)],
};

function render([seconds, synth]: [number, Synth], seed: number): Int16Array {
  const n = Math.round(seconds * RATE);
  const raw = new Float64Array(n);
  const rnd = random(seed);
  let peak = 0;
  for (let i = 0; i < n; i++) {
    raw[i] = synth(i / RATE, rnd);
    peak = Math.max(peak, Math.abs(raw[i]));
  }
  const fadeOut = Math.min(n, Math.round(0.01 * RATE)); // no click at the end
  const out = new Int16Array(n);
  const gain = peak > 0 ? 0.85 / peak : 0;
  for (let i = 0; i < n; i++) {
    const fade = i >= n - fadeOut ? (n - i) / fadeOut : 1;
    out[i] = Math.round(raw[i] * gain * fade * 32767);
  }
  return out;
}

function wav(samples: Int16Array): Buffer {
  const data = Buffer.from(samples.buffer, samples.byteOffset, samples.byteLength);
  const h = Buffer.alloc(44);
  h.write('RIFF', 0);
  h.writeUInt32LE(36 + data.length, 4);
  h.write('WAVE', 8);
  h.write('fmt ', 12);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); // PCM
  h.writeUInt16LE(1, 22); // mono
  h.writeUInt32LE(RATE, 24);
  h.writeUInt32LE(RATE * 2, 28);
  h.writeUInt16LE(2, 32);
  h.writeUInt16LE(16, 34);
  h.write('data', 36);
  h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

const force = process.argv.includes('--force');
const dir = sfxDir();
let written = 0;
Object.keys(SOUNDS).forEach((key, i) => {
  const synth = PACK[key];
  if (!synth) throw new Error(`No synth for "${key}"`);
  const file = path.join(dir, key, `erkasa-${key}.wav`);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (fs.existsSync(file) && !force) return;
  fs.writeFileSync(file, wav(render(synth, 1000 + i)));
  written++;
});
console.log(`Sound effects in ${dir}: ${written} written, ${Object.keys(SOUNDS).length - written} already there.`);
