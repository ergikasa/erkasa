/**
 * Writes a demo spec for the ANIMATED_2D template (9.4): every 2D scene, no AI files,
 * silent scenes with word timings estimated from the narration (2.6 words per second).
 *
 *   npm run demo:2d                         -> samples/demo-2d/spec-portrait-dark.json
 *   npm run demo:2d -- LANDSCAPE LIGHT      -> 16:9 in the light theme
 *   npm run demo:2d -- SQUARE VIBRANT
 *   npm run render -- samples/demo-2d/spec-landscape-light.json
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
  title: 'How an API works',
  scenes: [
    scene('Ever wondered how your phone app gets the weather in one second?', {
      type: 'character',
      pose: 'thinking',
      text: 'How does my app know the weather?',
      icons: ['smartphone', 'cloud-rain'],
    }, [{ sound: 'pop', onWord: '' }]),
    scene('An API is like a waiter in a restaurant. It takes your order to the kitchen and brings back the food.', {
      type: 'metaphor',
      title: 'An API',
      subtitle: 'like a restaurant waiter',
      icons: ['server', 'utensils'],
    }),
    scene('Your app asks the API, the API asks the database, and the answer comes back.', {
      type: 'iconLink',
      items: ['Your app', 'API', 'Database'],
      icons: ['smartphone', 'server', 'database'],
    }, [{ sound: 'click', onWord: 'asks' }]),
    scene('Inside every request there is a URL, a method, and a key.', {
      type: 'zoom',
      title: 'A request',
      icons: ['mail'],
      items: ['URL', 'Method', 'Key'],
    }),
    scene('Before APIs, every app built everything itself. After APIs, apps share services and ship faster.', {
      type: 'beforeAfter',
      leftTitle: 'Before APIs',
      rightTitle: 'After APIs',
      left: ['Build everything', 'Slow'],
      right: ['Share services', 'Ship faster'],
      icons: ['wrench', 'rocket'],
    }),
    scene('Now you know! Follow for more tech explained simply.', {
      type: 'character',
      pose: 'celebrating',
      text: 'Now you know!',
      icons: [],
    }, [{ sound: 'success', onWord: 'know!' }]),
  ],
};

const dir = path.join('samples', 'demo-2d');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, `spec-${aspect.toLowerCase()}-${themeName.toLowerCase()}.json`);
fs.writeFileSync(file, JSON.stringify(spec, null, 2));
const seconds = spec.scenes.reduce((t, sc) => t + sc.audioSeconds, 0);
console.log(`Wrote ${file}: ${spec.scenes.length} scenes, ~${Math.round(seconds)} s, ${aspect}, ${themeName}`);
