/**
 * The reference video for 2D quality (9.4b): "What happens when you type a URL", written the way
 * the LLM writes it with the ANIMATED_2D catalog (exchange, browser, host poses). No AI files:
 * silent scenes with word timings estimated from the narration (2.6 words per second).
 * Render it after every change to the 2D scenes and compare with the previous video.
 *
 *   npm run demo:url                        -> samples/demo-url/spec-landscape-light.json
 *   npm run demo:url -- PORTRAIT DARK
 *   npm run render -- samples/demo-url/spec-landscape-light.json
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

const aspect = ((process.argv[2] ?? 'LANDSCAPE').toUpperCase() as AspectRatio);
const themeName = (process.argv[3] ?? 'LIGHT').toUpperCase();

const spec: RenderSpec = {
  aspectRatio: aspect,
  theme: themeName,
  title: 'What happens when you type a URL',
  scenes: [
    scene('You type a web address and press Enter. Less than a second later, a page appears. So what happens in between?', {
      type: 'browser',
      url: 'example.com',
      items: [],
      pose: 'thinking',
    }),
    scene('First, your browser asks a DNS server: where is example.com? The server answers with the IP address, like a phone book.', {
      type: 'exchange',
      actors: ['Browser', 'DNS server'],
      icons: ['monitor', 'server'],
      messages: [
        { from: 0, to: 1, label: 'where is example.com?' },
        { from: 1, to: 0, label: 'IP 93.184.216.34' },
      ],
      pose: 'pointing',
    }),
    scene('Next, the browser opens a TCP connection to that address. Three quick messages: SYN, SYN-ACK, and ACK.', {
      type: 'exchange',
      actors: ['Browser', 'Server'],
      icons: ['monitor', 'server'],
      messages: [
        { from: 0, to: 1, label: 'SYN' },
        { from: 1, to: 0, label: 'SYN-ACK' },
        { from: 0, to: 1, label: 'ACK' },
      ],
      result: 'Connected',
      pose: 'presenting',
    }),
    scene('Then TLS makes the connection secure. Hello, certificate, keys. Now nobody in between can read your data.', {
      type: 'exchange',
      actors: ['Browser', 'Server'],
      icons: ['monitor', 'server'],
      messages: [
        { from: 0, to: 1, label: 'Hello' },
        { from: 1, to: 0, label: 'Certificate' },
        { from: 0, to: 1, label: 'Keys' },
      ],
      result: 'Encrypted',
      pose: 'thinking',
    }, [{ sound: 'success', onWord: 'secure.' }]),
    scene('Now the browser sends an HTTP request: GET the page. The server replies: 200 OK, here is the HTML.', {
      type: 'exchange',
      actors: ['Browser', 'Server'],
      icons: ['monitor', 'server'],
      messages: [
        { from: 0, to: 1, label: 'GET /' },
        { from: 1, to: 0, label: '200 OK + HTML' },
      ],
      pose: 'pointing',
    }),
    scene('Finally, the browser reads the HTML, loads the styles and images, and draws the page.', {
      type: 'browser',
      url: 'example.com',
      items: ['HTML', 'Styles', 'Images'],
      pose: 'happy',
    }),
    scene('All of that, in under a second. Now you know what happens when you type a URL.', {
      type: 'character',
      pose: 'celebrating',
      text: 'All in under a second!',
      icons: [],
    }, [{ sound: 'success', onWord: 'know' }]),
  ],
};

const dir = path.join('samples', 'demo-url');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, `spec-${aspect.toLowerCase()}-${themeName.toLowerCase()}.json`);
fs.writeFileSync(file, JSON.stringify(spec, null, 2));
const seconds = spec.scenes.reduce((t, sc) => t + sc.audioSeconds, 0);
console.log(`Wrote ${file}: ${spec.scenes.length} scenes, ~${Math.round(seconds)} s, ${aspect}, ${themeName}`);
