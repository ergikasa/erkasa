/**
 * Render a spec file by hand:
 *
 *   npm run render -- path/to/spec.json path/to/video.mp4
 *
 * Relative file names inside spec.json are read from the folder of spec.json.
 * Set REMOTION_BROWSER_EXECUTABLE to use an installed Chrome instead of Remotion's own.
 */
import fs from 'node:fs';
import path from 'node:path';
import { renderVideo } from './render';
import { RenderSpec, totalFrames, fpsOf } from './spec';
import { resolveMusic } from './music';
import { withSoundEffects } from './sfx';

async function main() {
  const [specPath, outPath] = process.argv.slice(2);
  if (!specPath) {
    console.error('Usage: npm run render -- <spec.json> [video.mp4]');
    process.exit(1);
  }
  const raw = JSON.parse(fs.readFileSync(specPath, 'utf8')) as RenderSpec;
  // "mood:upbeat" -> a track from the music folder (MUSIC_DIR, default ./music)
  const withMusic = { ...raw, music: resolveMusic(raw.music, specPath) };
  if (raw.music) console.log(`Music: ${withMusic.music ?? 'none found for ' + raw.music}`);
  // whooshes, pops and the LLM's sounds from the sfx/ folder (5.6)
  const { spec, missing } = withSoundEffects(withMusic, specPath);
  console.log(`Sound effects: ${spec.effects?.length ?? 0}` + (missing.length ? ` (no files for: ${missing.join(', ')})` : ''));
  const output = outPath ?? path.join(path.dirname(specPath), 'video.mp4');
  const seconds = (totalFrames(spec) / fpsOf(spec)).toFixed(1);
  console.log(`Rendering ${spec.scenes.length} scenes, ${seconds} s, ${spec.aspectRatio} -> ${output}`);

  const started = Date.now();
  await renderVideo(spec, output, {
    baseDir: path.dirname(path.resolve(specPath)),
    browserExecutable: process.env.REMOTION_BROWSER_EXECUTABLE ?? null,
    onProgress: (p) => process.stdout.write(`\r  ${p}%   `),
  });
  const mb = (fs.statSync(output).size / 1024 / 1024).toFixed(1);
  console.log(`\nDone in ${((Date.now() - started) / 1000).toFixed(0)} s: ${output} (${mb} MB)`);
}

main().catch((e) => {
  console.error('\nRender failed:', e instanceof Error ? e.message : e);
  process.exit(1);
});
