import fs from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { handle, type Request, type Result } from '../job';
import { HEARTBEAT_MS, reporterFor, startHeartbeat, workerId } from '../lease';
import type { Renderer } from '../render';

/*
 * V2 task A3: every message from the worker carries the request's dispatch and the worker id,
 * the render starts with CLAIMED, and a heartbeat keeps the lease while rendering.
 */

const request = (over: Partial<Request> = {}): Request =>
  ({
    jobId: 'job-1',
    projectId: 'project-1',
    dispatchId: 'dispatch-7',
    attempt: 2,
    outputKey: 'projects/p/j/d/video.mp4',
    outputUrl: 'http://storage.test/upload',
    spec: { aspectRatio: 'PORTRAIT', scenes: [{ audioSeconds: 1, words: [], onScreenText: '' }] },
    ...over,
  }) as unknown as Request;

/** A renderer that does nothing until release() is called, then writes a fake MP4. */
function fakeRenderer() {
  let release: () => void = () => undefined;
  let progress: (p: number) => void = () => undefined;
  let started = false;
  const renderer = {
    render: (_spec: unknown, out: string, options: { onProgress: (p: number) => void }) =>
      new Promise<void>((resolve) => {
        started = true;
        progress = options.onProgress;
        release = () => {
          fs.writeFileSync(out, 'fake-mp4');
          resolve();
        };
      }),
  } as unknown as Renderer;
  return { renderer, started: () => started, release: () => release(), progress: (p: number) => progress(p) };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('lease helpers', () => {
  it('stamps every report with jobId, dispatchId and workerId', () => {
    const sent: Result[] = [];
    reporterFor({ jobId: 'j', dispatchId: 'd' }, 'w1', (r) => sent.push(r))({ type: 'PROGRESS', percent: 40 });
    expect(sent).toEqual([{ type: 'PROGRESS', percent: 40, jobId: 'j', dispatchId: 'd', workerId: 'w1' }]);
  });

  it('uses WORKER_ID, or host-pid, at most 100 characters', () => {
    expect(workerId({ WORKER_ID: ' render-a ' })).toBe('render-a');
    expect(workerId({})).toMatch(new RegExp(`-${process.pid}$`));
    expect(workerId({ WORKER_ID: 'x'.repeat(150) })).toHaveLength(100);
  });

  it('beats every 20 s until stopped', () => {
    vi.useFakeTimers();
    const beat = vi.fn();
    const stop = startHeartbeat(beat);
    vi.advanceTimersByTime(HEARTBEAT_MS * 3 + 1);
    expect(beat).toHaveBeenCalledTimes(3);
    stop();
    vi.advanceTimersByTime(HEARTBEAT_MS * 5);
    expect(beat).toHaveBeenCalledTimes(3);
  });
});

describe('handle', () => {
  it('claims first, beats while rendering, and stamps the result', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
    const sent: Result[] = [];
    const fake = fakeRenderer();

    const done = handle(request(), (r) => sent.push(r), fake.renderer, 'worker-a');
    await vi.waitFor(() => expect(fake.started()).toBe(true));
    fake.progress(30);
    vi.advanceTimersByTime(HEARTBEAT_MS * 2 + 1); // 40 s without real progress: two heartbeats
    fake.release();
    await done;

    expect(sent[0]).toMatchObject({ type: 'CLAIMED', jobId: 'job-1', dispatchId: 'dispatch-7', workerId: 'worker-a' });
    const beats = sent.filter((r) => r.type === 'PROGRESS' && r.percent === 30);
    expect(beats.length).toBeGreaterThanOrEqual(3); // the 30 % report + 2 heartbeats
    const last = sent[sent.length - 1];
    expect(last).toMatchObject({ type: 'SUCCEEDED', dispatchId: 'dispatch-7', workerId: 'worker-a', bytes: 8 });
    expect(sent.every((r) => r.dispatchId === 'dispatch-7' && r.workerId === 'worker-a')).toBe(true);

    // the heartbeat stops with the job
    const count = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS * 3);
    expect(sent.length).toBe(count);
  });

  it('stops the heartbeat when the render fails', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const sent: Result[] = [];
    const broken = { render: async () => { throw new Error('Chrome crashed'); } } as unknown as Renderer;

    await expect(handle(request(), (r) => sent.push(r), broken, 'worker-a')).rejects.toThrow('Chrome crashed');
    const count = sent.length;
    vi.advanceTimersByTime(HEARTBEAT_MS * 3);
    expect(sent.length).toBe(count);
  });
});
