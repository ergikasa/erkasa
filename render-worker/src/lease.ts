/**
 * Lease and fencing on the worker side (V2 task A3).
 *
 * The backend owns the job; this worker only holds a lease on ONE dispatch of it:
 *   - every message it sends carries the request's jobId + dispatchId and this worker's id,
 *     so the backend can ignore messages from a dispatch that is no longer current;
 *   - CLAIMED is sent when the render starts (takes the lease);
 *   - PROGRESS is sent at least every 20 s while rendering (the heartbeat that keeps the lease;
 *     the backend's lease is 2 minutes, so a few lost beats do no harm).
 */
import os from 'node:os';
import type { Request, Result } from './job';

export const HEARTBEAT_MS = 20_000;

/** What the job code sends; the stamp (jobId, dispatchId, workerId) is added by reporterFor. */
export type Report = Omit<Result, 'jobId' | 'dispatchId' | 'workerId'>;

/** Who this process is: WORKER_ID if set, otherwise host name + process id (max 100 characters). */
export function workerId(env: NodeJS.ProcessEnv = process.env): string {
  const id = env.WORKER_ID?.trim() || `${os.hostname()}-${process.pid}`;
  return id.slice(0, 100);
}

/** A report function for one request: every message carries the request's dispatch. */
export function reporterFor(req: Pick<Request, 'jobId' | 'dispatchId'>, worker: string, send: (r: Result) => void) {
  return (r: Report) => send({ ...r, jobId: req.jobId, dispatchId: req.dispatchId, workerId: worker });
}

/** Calls beat() every everyMs until the returned stop function is called. */
export function startHeartbeat(beat: () => void, everyMs: number = HEARTBEAT_MS): () => void {
  const timer = setInterval(beat, everyMs);
  // never keep the process alive just for the heartbeat
  timer.unref?.();
  return () => clearInterval(timer);
}
