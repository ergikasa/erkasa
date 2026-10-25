/**
 * Writes a demo spec for the INFOGRAPHIC template (9.3): every infographic scene, no AI files,
 * silent scenes with word timings estimated from the narration (2.6 words per second).
 *
 *   npm run demo:info                       -> samples/demo-info/spec-portrait-dark.json
 *   npm run demo:info -- LANDSCAPE LIGHT    -> 16:9 in the light theme
 *   npm run demo:info -- SQUARE VIBRANT
 *   npm run render -- samples/demo-info/spec-landscape-light.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { AspectRatio, RenderSpec, SoundCue, SpecScene, Visual, Word } from './spec';

const WORDS_PER_SECOND = 2.6;

function scene(narration: string, visual: Visual, sfx: SoundCue[] = []): SpecScene {
  const parts = narration.split(/\s+/).filter(Boolean);
  const words: Word[] = parts.map((word, i) => ({
    word,
    startSeconds: +(0.2 + i / WORDS_PER_SECOND).toFixed(2),
    endSeconds: +(0.2 + (i + 0.85) / WORDS_PER_SECOND).toFixed(2),
  }));
  const audioSeconds = +(words[words.length - 1].endSeconds + 0.2).toFixed(2);
  return { audioSeconds, words, visual, sfx };
}

const aspect = ((process.argv[2] ?? 'PORTRAIT').toUpperCase() as AspectRatio);
const themeName = (process.argv[3] ?? 'DARK').toUpperCase();

const spec: RenderSpec = {
  aspectRatio: aspect,
  theme: themeName,
  title: 'The life of an online order',
  scenes: [
    scene('What really happens after you click buy? Here is the life of an online order.', {
      type: 'title',
      title: 'One online order',
      subtitle: 'From click to door',
    }, [{ sound: 'impact', onWord: '' }]),
    scene('It starts with the order. Then the payment, packing, shipping, and finally delivery.', {
      type: 'timeline',
      items: ['Order placed', 'Payment confirmed', 'Packed in the warehouse', 'Shipped', 'Delivered'],
      labels: ['10:02', '10:03', '14:30', '18:00', 'Day 2'],
    }),
    scene('Three things make it work. Speed, safe payments, and live tracking.', {
      type: 'iconGrid',
      items: ['Speed', 'Safe payments', 'Live tracking'],
      icons: ['zap', 'shield-check', 'map-pin'],
    }),
    scene('Online sales keep growing. 2020 was about 4 trillion dollars, 2023 almost 6, and 2026 more than 7.', {
      type: 'bars',
      title: 'Online sales worldwide',
      items: ['2020', '2023', '2026'],
      values: [4.2, 5.8, 7.4],
      prefix: '$',
      suffix: 'T',
    }, [{ sound: 'cash', onWord: '2026' }]),
    scene('And the warehouse works in a loop. Receive, store, pick, pack, and ship, again and again.', {
      type: 'cycle',
      title: 'Warehouse',
      items: ['Receive', 'Store', 'Pick', 'Pack', 'Ship'],
    }),
    scene('Follow for more business explained in a minute.', {
      type: 'cta',
      text: 'Follow for more',
      handle: '@erkasa',
    }, [{ sound: 'success', onWord: 'Follow' }]),
  ],
};

const dir = path.join('samples', 'demo-info');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, `spec-${aspect.toLowerCase()}-${themeName.toLowerCase()}.json`);
fs.writeFileSync(file, JSON.stringify(spec, null, 2));
const seconds = spec.scenes.reduce((t, sc) => t + sc.audioSeconds, 0);
console.log(`Wrote ${file}: ${spec.scenes.length} scenes, ~${Math.round(seconds)} s, ${aspect}, ${themeName}`);
