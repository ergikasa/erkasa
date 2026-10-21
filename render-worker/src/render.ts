import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bundle } from '@remotion/bundler';
import { ChromiumOptions, HeadlessBrowser, OpenGlRenderer, openBrowser, renderMedia, selectComposition } from '@remotion/renderer';
import { RenderSpec, validateSpec } from './spec';

const here = path.dirname(fileURLToPath(import.meta.url));

export type RenderOptions = {
  /** folder that relative file names in the spec are read from */
  baseDir: string;
  onProgress?: (percent: number) => void;
};

export type RendererSettings = {
  /** Chrome to use; null = Remotion downloads its own headless shell once */
  browserExecutable?: string | null;
  /** browser tabs drawing frames at the same time; default = CPU cores (max 8) */
  concurrency?: number;
  /** how Chrome draws: null = Remotion's default; "angle" can use the graphics card on Windows */
  gl?: OpenGlRenderer | null;
};

const GL_OPTIONS = ['swangle', 'angle', 'egl', 'swiftshader', 'vulkan', 'angle-egl'] as const;

/** RENDER_GL from the environment (angle, swangle, egl, swiftshader, vulkan, angle-egl), else null. */
export function defaultGl(): OpenGlRenderer | null {
  const v = (process.env.RENDER_GL ?? '').trim().toLowerCase();
  if (!v) return null;
  if (!(GL_OPTIONS as readonly string[]).includes(v)) {
    throw new Error(`RENDER_GL=${v} is not valid; use one of: ${GL_OPTIONS.join(', ')}`);
  }
  return v as OpenGlRenderer;
}

/** RENDER_CONCURRENCY from the environment, else one tab per CPU core (max 8). */
export function defaultConcurrency(): number {
  const fromEnv = Number(process.env.RENDER_CONCURRENCY);
  if (Number.isInteger(fromEnv) && fromEnv > 0) return fromEnv;
  return Math.max(1, Math.min(8, os.cpus().length));
}

/**
 * Renders specs to MP4 (H.264 + AAC).
 *
 * Speed (5.5): the React code is bundled ONCE and the browser is opened ONCE when the worker
 * starts; every job only copies its files into the bundle's public folder and renders. Before,
 * each job bundled (webpack) and started Chrome again.
 */
export class Renderer {
  private browser: HeadlessBrowser | null = null;
  private jobs = 0;

  private constructor(
    private readonly serveUrl: string,
    private readonly settings: Required<RendererSettings>,
  ) {}

  static async start(settings: RendererSettings = {}): Promise<Renderer> {
    const emptyPublic = fs.mkdtempSync(path.join(os.tmpdir(), 'erkasa-public-'));
    const serveUrl = await bundle({ entryPoint: path.join(here, 'index.ts'), publicDir: emptyPublic });
    fs.rmSync(emptyPublic, { recursive: true, force: true });
    const renderer = new Renderer(serveUrl, {
      browserExecutable: settings.browserExecutable ?? null,
      concurrency: settings.concurrency ?? defaultConcurrency(),
      gl: settings.gl === undefined ? defaultGl() : settings.gl,
    });
    await renderer.openBrowser();
    return renderer;
  }

  get concurrency(): number {
    return this.settings.concurrency;
  }

  get gl(): string {
    return this.settings.gl ?? 'default';
  }

  private get chromiumOptions(): ChromiumOptions {
    return this.settings.gl ? { gl: this.settings.gl } : {};
  }

  private async openBrowser(): Promise<HeadlessBrowser> {
    if (!this.browser) {
      this.browser = await openBrowser('chrome', {
        browserExecutable: this.settings.browserExecutable,
        chromiumOptions: this.chromiumOptions,
      });
    }
    return this.browser;
  }

  async render(spec: RenderSpec, outputFile: string, options: RenderOptions): Promise<void> {
    validateSpec(spec);
    // this job's files, served by Remotion next to the bundle: <bundle>/public/jobs/<n>/...
    const folder = `jobs/${++this.jobs}-${Date.now()}`;
    const publicDir = path.join(this.serveUrl, 'public', folder);
    fs.mkdirSync(publicDir, { recursive: true });
    try {
      const localSpec = copyLocalFiles(spec, options.baseDir, publicDir, folder);
      const browser = await this.openBrowser();
      const composition = await selectComposition({
        serveUrl: this.serveUrl,
        id: 'Video',
        inputProps: localSpec,
        puppeteerInstance: browser,
      });

      fs.mkdirSync(path.dirname(path.resolve(outputFile)), { recursive: true });
      let lastReported = -1;
      await renderMedia({
        composition,
        serveUrl: this.serveUrl,
        codec: 'h264',
        audioCodec: 'aac',
        outputLocation: outputFile,
        inputProps: localSpec,
        puppeteerInstance: browser,
        chromiumOptions: this.chromiumOptions,
        concurrency: this.settings.concurrency,
        // Good quality for social media at a sensible file size; veryfast encodes ~10 % quicker.
        crf: 20,
        x264Preset: 'veryfast',
        onProgress: ({ progress }) => {
          const percent = Math.floor(progress * 100);
          if (percent !== lastReported && percent % 5 === 0) {
            lastReported = percent;
            options.onProgress?.(percent);
          }
        },
      });
    } catch (e) {
      // a crashed browser must not break the next job: open a fresh one then
      await this.closeBrowser();
      throw e;
    } finally {
      fs.rmSync(publicDir, { recursive: true, force: true });
    }
  }

  private async closeBrowser(): Promise<void> {
    const b = this.browser;
    this.browser = null;
    await b?.close({ silent: true }).catch(() => undefined);
  }

  async close(): Promise<void> {
    await this.closeBrowser();
    fs.rmSync(this.serveUrl, { recursive: true, force: true });
  }
}

/** One-off render (npm run render): start, render, clean up. */
export async function renderVideo(
  spec: RenderSpec,
  outputFile: string,
  options: RenderOptions & RendererSettings,
): Promise<void> {
  const renderer = await Renderer.start(options);
  try {
    await renderer.render(spec, outputFile, options);
  } finally {
    await renderer.close();
  }
}

/** Copies every local image / audio / music file into publicDir and points the spec at the copies. */
function copyLocalFiles(spec: RenderSpec, baseDir: string, publicDir: string, prefix: string): RenderSpec {
  let n = 0;
  const copied = new Map<string, string>(); // a pop used 12 times is copied once
  const copy = (file: string | null | undefined): string | null => {
    if (!file) return null;
    if (/^https?:\/\//i.test(file)) return file;
    const from = path.isAbsolute(file) ? file : path.join(baseDir, file);
    const done = copied.get(from);
    if (done) return done;
    if (!fs.existsSync(from)) {
      throw new Error(`File not found: ${from}`);
    }
    const name = `asset-${n++}${path.extname(from)}`;
    fs.copyFileSync(from, path.join(publicDir, name));
    copied.set(from, `${prefix}/${name}`);
    return `${prefix}/${name}`;
  };
  return {
    ...spec,
    music: copy(spec.music),
    effects: (spec.effects ?? []).map((e) => ({ ...e, file: copy(e.file)! })),
    scenes: spec.scenes.map((s) => ({
      ...s,
      image: copy(s.image) ?? undefined,
      audio: copy(s.audio) ?? undefined,
    })),
  };
}
