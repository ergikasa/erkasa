/**
 * Builds spec.json from a folder made by the 3.5 smoke run (backend/smoke-output/<jobId>/),
 * so you can render a real video from real AI files before the backend sends render jobs (5.2).
 *
 *   npm run sample -- C:\dev\erkasa\backend\smoke-output\<jobId> [PORTRAIT|LANDSCAPE]
 *   npm run render -- C:\dev\erkasa\backend\smoke-output\<jobId>\spec.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { AspectRatio, RenderSpec, Word, normalizeVisual } from './spec';

type Summary = {
  title: string;
  rendered: {
    scene: number;
    imageFile: string;
    imageWidth: number;
    imageHeight: number;
    audioFile: string;
    audioSeconds: number;
    words: Word[];
    visual?: unknown;
  }[];
};
type Script = { scenes: { onScreenText?: string }[] };

function aspectOf(width: number, height: number): AspectRatio {
  if (height > width * 1.2) return 'PORTRAIT';
  if (width > height * 1.2) return 'LANDSCAPE';
  return 'SQUARE';
}

const dir = process.argv[2];
if (!dir || !fs.existsSync(path.join(dir, 'summary.json'))) {
  console.error('Usage: npm run sample -- <smoke-output folder with summary.json>');
  process.exit(1);
}
const summary = JSON.parse(fs.readFileSync(path.join(dir, 'summary.json'), 'utf8')) as Summary;
const script = JSON.parse(fs.readFileSync(path.join(dir, 'script.json'), 'utf8')) as Script;
if (!summary.rendered?.length) {
  console.error('summary.json has no rendered scenes');
  process.exit(1);
}

const withImage = summary.rendered.find((r) => r.imageFile);
const spec: RenderSpec = {
  // from an image if there is one; graphics-only videos are portrait unless told otherwise
  aspectRatio: (process.argv[3] as AspectRatio) || (withImage ? aspectOf(withImage.imageWidth, withImage.imageHeight) : 'PORTRAIT'),
  title: summary.title,
  captions: true,
  scenes: summary.rendered.map((r) => ({
    image: r.imageFile || undefined,
    audio: r.audioFile,
    visual: normalizeVisual(r.visual),
    audioSeconds: r.audioSeconds,
    onScreenText: script.scenes[r.scene - 1]?.onScreenText ?? '',
    words: r.words,
  })),
};
const out = path.join(dir, 'spec.json');
fs.writeFileSync(out, JSON.stringify(spec, null, 2));
console.log(`Wrote ${out}: ${spec.scenes.length} scenes, ${spec.aspectRatio}`);
