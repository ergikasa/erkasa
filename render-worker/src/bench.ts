/**
 * Speed test (5.5): renders the ~60 s demo in 9:16 and 16:9 with ONE renderer, like the worker.
 *
 *   npm run bench
 *
 * Writes samples/demo/bench-portrait.mp4 and bench-landscape.mp4 and prints how long each took.
 * Make the specs first: npm run demo -- PORTRAIT 60  and  npm run demo -- LANDSCAPE 60
 *   npm run bench -- PORTRAIT          only the 9:16 video (half the time)
 *
 * Try other settings (PowerShell):  $env:RENDER_CONCURRENCY=2; $env:RENDER_GL="angle"; npm run bench -- PORTRAIT
 */
import fs from 'node:fs';
import path from 'node:path';
import { Renderer } from './render';
import { withSoundEffects } from './sfx';
import { RenderSpec, fpsOf, totalFrames } from './spec';

const dir = path.join('samples', 'demo');
const only = (process.argv[2] ?? '').toUpperCase();
const runs = [
  { ratio: 'PORTRAIT', spec: 'spec-60.json', out: 'bench-portrait.mp4' },
  { ratio: 'LANDSCAPE', spec: 'spec-landscape-60.json', out: 'bench-landscape.mp4' },
].filter((r) => !only || r.ratio === only);

async function main() {
  for (const r of runs) {
    if (!fs.existsSync(path.join(dir, r.spec))) {
      throw new Error(`${path.join(dir, r.spec)} is missing: run  npm run demo -- PORTRAIT 60  and  npm run demo -- LANDSCAPE 60`);
    }
  }
  let t = Date.now();
  const renderer = await Renderer.start({ browserExecutable: process.env.REMOTION_BROWSER_EXECUTABLE ?? null });
  console.log(`Renderer ready in ${((Date.now() - t) / 1000).toFixed(1)} s, ${renderer.concurrency} tabs, gl=${renderer.gl}`);
  try {
    for (const r of runs) {
      const raw = JSON.parse(fs.readFileSync(path.join(dir, r.spec), 'utf8')) as RenderSpec;
      const { spec } = withSoundEffects(raw, r.spec);
      const video = (totalFrames(spec) / fpsOf(spec)).toFixed(1);
      const out = path.join(dir, r.out);
      t = Date.now();
      await renderer.render(spec, out, { baseDir: dir, onProgress: (p) => process.stdout.write(`\r  ${spec.aspectRatio} ${p}%   `) });
      const took = (Date.now() - t) / 1000;
      const mb = (fs.statSync(out).size / 1024 / 1024).toFixed(1);
      console.log(`\r  ${spec.aspectRatio}: ${video} s video in ${took.toFixed(0)} s (${(took / Number(video)).toFixed(1)} s per video second, ${mb} MB) -> ${out}`);
    }
  } finally {
    await renderer.close();
  }
}

main().catch((e) => {
  console.error('\nBench failed:', e instanceof Error ? e.message : e);
  process.exit(1);
});
