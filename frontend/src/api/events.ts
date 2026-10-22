import { EventStreamContentType, fetchEventSource } from '@microsoft/fetch-event-source';
import { apiUrl, authHeaders, refreshOnce, session } from './client';
import type { ProjectStatus } from './projects';

/** One live update from GET /api/projects/{id}/events (backend ProjectEvents.Snapshot). */
export type Snapshot = {
  projectId: string;
  status: ProjectStatus;
  title: string | null;
  /** finished image + voice steps of the latest files job */
  done: number;
  total: number;
  /** 0-100 while RENDERING, else null */
  renderPercent: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  at: string;
};

class Retry extends Error {}
class Stop extends Error {}

/**
 * Live updates for one project. EventSource cannot send the Authorization header, so this uses
 * fetch-based SSE. Reconnects by itself (with a fresh token after a 401). Returns "stop".
 */
export function subscribeProject(id: string, onSnapshot: (s: Snapshot) => void, onTrouble?: () => void): () => void {
  const controller = new AbortController();
  void fetchEventSource(apiUrl(`/api/projects/${id}/events`), {
    signal: controller.signal,
    openWhenHidden: true, // keep the stream while the phone screen is off / tab is hidden
    // headers are read again on every (re)connect, so a refreshed token is used
    fetch: (input, init) => fetch(input, { ...init, headers: { ...(init?.headers as Record<string, string>), ...authHeaders() } }),
    async onopen(res) {
      if (res.ok && res.headers.get('content-type')?.startsWith(EventStreamContentType)) return;
      if (res.status === 401 && session.refreshToken() && (await refreshOnce())) throw new Retry();
      if (res.status === 404 || res.status === 403 || res.status === 401) throw new Stop();
      throw new Retry();
    },
    onmessage(msg) {
      if (msg.event !== 'project' || !msg.data) return;
      try {
        onSnapshot(JSON.parse(msg.data) as Snapshot);
      } catch {
        /* ignore a broken message; the next snapshot is complete on its own */
      }
    },
    onerror(err) {
      onTrouble?.();
      if (err instanceof Stop) throw err; // do not retry
      return 3000; // retry in 3 s
    },
  }).catch(() => undefined);
  return () => controller.abort();
}
