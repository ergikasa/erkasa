/** One render job: download the files, render with Remotion, upload the MP4, report. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Renderer } from './render';
import { RenderSpec, fpsOf, totalFrames } from './spec';
import { resolveMusic } from './music';
import { withSoundEffects } from './sfx';

export type Request = {
  jobId: string;
  projectId: string;
  outputKey: string;
  outputUrl: string;
  spec: RenderSpec;
};

export type Result = {
  jobId: string;
  type: 'PROGRESS' | 'SUCCEEDED' | 'FAILED';
  percent?: number;
  durationSeconds?: number;
  bytes?: number;
  renderSeconds?: number;
  error?: string;
};

/** Downloads every image / audio link into dir and points the spec at the local files. */
function log(...args: unknown[]) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

async function downloadAssets(spec: RenderSpec, dir: string): Promise<RenderSpec> {
  let n = 0;
  const fetchTo = async (url: string | undefined | null): Promise<string | undefined> => {
    if (!url) return undefined;
    if (!/^https?:\/\//i.test(url)) return url; // a local file (e.g. music from the music folder)
    const res = await fetch(url);
    if (!res.ok) throw new Error(`Download failed (HTTP ${res.status}) for ${url.split('?')[0]}`);
    const ext = path.extname(new URL(url).pathname) || '.bin';
    const name = `in-${n++}${ext}`;
    fs.writeFileSync(path.join(dir, name), Buffer.from(await res.arrayBuffer()));
    return name;
  };
  // all files at the same time: a 10-scene video no longer waits for 20 downloads in a row
  const scenes = await Promise.all(
    spec.scenes.map(async (s) => {
      const [image, audio] = await Promise.all([fetchTo(s.image), fetchTo(s.audio)]);
      return { ...s, image, audio };
    }),
  );
  return { ...spec, music: (await fetchTo(spec.music ?? undefined)) ?? null, scenes };
}

async function upload(url: string, file: string): Promise<void> {
  const body = fs.readFileSync(file);
  // Content-Type must be exactly what the backend signed the link with.
  const res = await fetch(url, { method: 'PUT', headers: { 'Content-Type': 'video/mp4' }, body });
  if (!res.ok) {
    throw new Error(`Upload failed (HTTP ${res.status}): ${(await res.text()).slice(0, 200)}`);
  }
}

export async function handle(req: Request, report: (r: Result) => void, renderer: Renderer): Promise<void> {
  const started = Date.now();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'erkasa-job-'));
  try {
    log(`job ${req.jobId}: ${req.spec.scenes.length} scenes, ${req.spec.aspectRatio}`);
    report({ jobId: req.jobId, type: 'PROGRESS', percent: 0 });
    const music = resolveMusic(req.spec.music, req.jobId);
    if (req.spec.music) log(`job ${req.jobId}: music ${music ? path.basename(music) : 'none found for ' + req.spec.music}`);
    const downloaded = await downloadAssets({ ...req.spec, music }, dir);
    const { spec: local, missing } = withSoundEffects(downloaded, req.jobId);
    log(`job ${req.jobId}: ${local.effects?.length ?? 0} sound effects` + (missing.length ? ` (no files for: ${missing.join(', ')}; run npm run make-sfx)` : ''));

    const out = path.join(dir, 'video.mp4');
    let lastSent = -1;
    await renderer.render(local, out, {
      baseDir: dir,
      onProgress: (p) => {
        // every 10 % is enough for a progress bar, and keeps the queue quiet
        if (p >= lastSent + 10 || p === 100) {
          lastSent = p;
          report({ jobId: req.jobId, type: 'PROGRESS', percent: Math.min(99, p) });
        }
      },
    });

    await upload(req.outputUrl, out);
    const bytes = fs.statSync(out).size;
    const durationSeconds = +(totalFrames(local) / fpsOf(local)).toFixed(2);
    const renderSeconds = +((Date.now() - started) / 1000).toFixed(1);
    report({ jobId: req.jobId, type: 'SUCCEEDED', percent: 100, durationSeconds, bytes, renderSeconds });
    log(`job ${req.jobId}: done in ${renderSeconds} s (${(bytes / 1024 / 1024).toFixed(1)} MB)`);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

