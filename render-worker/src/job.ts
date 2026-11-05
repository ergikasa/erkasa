/** One render job: download the files, render with Remotion, upload the MP4, report. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { makeCancelSignal } from '@remotion/renderer';
import { Renderer } from './render';
import { RenderSpec, fpsOf, totalFrames } from './spec';
import { resolveMusic } from './music';
import { withSoundEffects } from './sfx';
import { reporterFor, startHeartbeat, workerId } from './lease';

export type Request = {
  jobId: string;
  projectId: string;
  /** this send of the job (V2 A3): copied into every result, or the backend ignores the result */
  dispatchId?: string;
  /** 1 for the first send, 2 for the next one ... */
  attempt?: number;
  /** the backend's render time limit (V2 A4); the worker stops itself at 90 % of it */
  timeoutSeconds?: number;
  outputKey: string;
  outputUrl: string;
  spec: RenderSpec;
};

export type Result = {
  jobId: string;
  /** CLAIMED = this worker starts and takes the lease; PROGRESS is also the 20 s heartbeat */
  type: 'CLAIMED' | 'PROGRESS' | 'SUCCEEDED' | 'FAILED';
  dispatchId?: string;
  workerId?: string;
  percent?: number;
  durationSeconds?: number;
  bytes?: number;
  renderSeconds?: number;
  error?: string;
  /** with FAILED: RENDER_TIMEOUT when the worker stopped at its time limit (V2 A4) */
  errorCode?: string;
};

/** Share of the backend's limit after which the worker stops on its own (the backend waits until 100 % + 1 min). */
export const WORKER_TIMEOUT_SHARE = 0.9;

/** A failure with a code the backend understands (RENDER_TIMEOUT). */
export class JobError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

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

export async function handle(
  req: Request,
  send: (r: Result) => void,
  renderer: Renderer,
  worker: string = workerId(),
): Promise<void> {
  const started = Date.now();
  const report = reporterFor(req, worker, send);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'erkasa-job-'));
  let percent = 0;
  report({ type: 'CLAIMED' });
  // the heartbeat keeps the lease even while downloading or encoding (no progress for a while)
  const stopHeartbeat = startHeartbeat(() => report({ type: 'PROGRESS', percent }));
  // V2 A4: stop at 90 % of the backend's limit, so the user hears "too slow" from us, not from the watchdog
  const limitMs = req.timeoutSeconds && req.timeoutSeconds > 0 ? req.timeoutSeconds * 1000 * WORKER_TIMEOUT_SHARE : null;
  const { cancelSignal, cancel } = makeCancelSignal();
  let timedOut = false;
  const limit = limitMs === null ? null : setTimeout(() => {
    timedOut = true;
    cancel();
  }, limitMs);
  limit?.unref?.();
  const timeout = () =>
    new JobError('RENDER_TIMEOUT', `Render stopped after ${Math.round((Date.now() - started) / 1000)} s (limit ${Math.round(limitMs! / 1000)} s)`);
  try {
    log(`job ${req.jobId}: ${req.spec.scenes.length} scenes, ${req.spec.aspectRatio}, dispatch ${req.dispatchId ?? '-'}, attempt ${req.attempt ?? 1}`);
    report({ type: 'PROGRESS', percent: 0 });
    const music = resolveMusic(req.spec.music, req.jobId);
    if (req.spec.music) log(`job ${req.jobId}: music ${music ? path.basename(music) : 'none found for ' + req.spec.music}`);
    const downloaded = await downloadAssets({ ...req.spec, music }, dir);
    const { spec: local, missing } = withSoundEffects(downloaded, req.jobId);
    log(`job ${req.jobId}: ${local.effects?.length ?? 0} sound effects` + (missing.length ? ` (no files for: ${missing.join(', ')}; run npm run make-sfx)` : ''));

    if (timedOut) throw timeout();
    const out = path.join(dir, 'video.mp4');
    let lastSent = -1;
    try {
      await renderer.render(local, out, {
        baseDir: dir,
        cancelSignal,
        onProgress: (p) => {
          // every 10 % is enough for a progress bar, and keeps the queue quiet
          percent = Math.min(99, p);
          if (p >= lastSent + 10 || p === 100) {
            lastSent = p;
            report({ type: 'PROGRESS', percent });
          }
        },
      });
    } catch (e) {
      throw timedOut ? timeout() : e;
    }
    if (timedOut) throw timeout();

    await upload(req.outputUrl, out);
    const bytes = fs.statSync(out).size;
    const durationSeconds = +(totalFrames(local) / fpsOf(local)).toFixed(2);
    const renderSeconds = +((Date.now() - started) / 1000).toFixed(1);
    report({ type: 'SUCCEEDED', percent: 100, durationSeconds, bytes, renderSeconds });
    log(`job ${req.jobId}: done in ${renderSeconds} s (${(bytes / 1024 / 1024).toFixed(1)} MB)`);
  } finally {
    if (limit) clearTimeout(limit);
    stopHeartbeat();
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

