import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bundle } from '@remotion/bundler';
import { renderMedia, selectComposition } from '@remotion/renderer';
import { RenderSpec, validateSpec } from './spec';

const here = path.dirname(fileURLToPath(import.meta.url));

export type RenderOptions = {
  /** folder that relative file names in the spec are read from */
  baseDir: string;
  /** Chrome to use; null = Remotion downloads its own headless shell once */
  browserExecutable?: string | null;
  onProgress?: (percent: number) => void;
};

/**
 * Renders the spec to an MP4 (H.264 + AAC).
 *
 * Local files in the spec (anything that is not http/https) are copied into a temporary
 * public folder, so the browser that renders can load them.
 */
export async function renderVideo(spec: RenderSpec, outputFile: string, options: RenderOptions): Promise<void> {
  validateSpec(spec);
  const publicDir = fs.mkdtempSync(path.join(os.tmpdir(), 'erkasa-render-'));
  try {
    const localSpec = copyLocalFiles(spec, options.baseDir, publicDir);

    const serveUrl = await bundle({
      entryPoint: path.join(here, 'index.ts'),
      publicDir,
    });
    const browserExecutable = options.browserExecutable ?? null;
    const composition = await selectComposition({
      serveUrl,
      id: 'Video',
      inputProps: localSpec,
      browserExecutable,
    });

    fs.mkdirSync(path.dirname(path.resolve(outputFile)), { recursive: true });
    let lastReported = -1;
    await renderMedia({
      composition,
      serveUrl,
      codec: 'h264',
      audioCodec: 'aac',
      outputLocation: outputFile,
      inputProps: localSpec,
      browserExecutable,
      // Good quality for social media at a sensible file size.
      crf: 20,
      onProgress: ({ progress }) => {
        const percent = Math.floor(progress * 100);
        if (percent !== lastReported && percent % 5 === 0) {
          lastReported = percent;
          options.onProgress?.(percent);
        }
      },
    });
  } finally {
    fs.rmSync(publicDir, { recursive: true, force: true });
  }
}

/** Copies every local image / audio / music file into publicDir and points the spec at the copies. */
function copyLocalFiles(spec: RenderSpec, baseDir: string, publicDir: string): RenderSpec {
  let n = 0;
  const copy = (file: string | null | undefined): string | null => {
    if (!file) return null;
    if (/^https?:\/\//i.test(file)) return file;
    const from = path.isAbsolute(file) ? file : path.join(baseDir, file);
    if (!fs.existsSync(from)) {
      throw new Error(`File not found: ${from}`);
    }
    const name = `asset-${n++}${path.extname(from)}`;
    fs.copyFileSync(from, path.join(publicDir, name));
    return name;
  };
  return {
    ...spec,
    music: copy(spec.music),
    scenes: spec.scenes.map((s) => ({ ...s, image: copy(s.image)!, audio: copy(s.audio)! })),
  };
}