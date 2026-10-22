import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Snapshot, subscribeProject } from '../api/events';
import { Project } from '../api/projects';

export type Live = { done: number; total: number; renderPercent: number | null; connected: boolean };

/**
 * Keeps the project page up to date from the server's live events (SSE):
 * - status / title / error change at once in the cached project,
 * - a new status reloads the full project (script, jobs) and the files list,
 * - every finished voice or picture reloads the files list.
 */
export function useLiveProject(id: string, enabled: boolean): Live {
  const queryClient = useQueryClient();
  const [live, setLive] = useState<Live>({ done: 0, total: 0, renderPercent: null, connected: false });
  const last = useRef<Snapshot | null>(null);

  useEffect(() => {
    if (!enabled) return;
    const stop = subscribeProject(
      id,
      (s) => {
        const before = last.current;
        last.current = s;
        setLive({ done: s.done, total: s.total, renderPercent: s.renderPercent, connected: true });
        queryClient.setQueryData<Project>(['project', id], (old) =>
          old ? { ...old, status: s.status, title: s.title ?? old.title, errorCode: s.errorCode, errorMessage: s.errorMessage } : old,
        );
        if (!before || before.status !== s.status) {
          void queryClient.invalidateQueries({ queryKey: ['project', id] });
        }
        if (!before || before.status !== s.status || before.done !== s.done) {
          void queryClient.invalidateQueries({ queryKey: ['assets', id] });
        }
      },
      () => setLive((l) => ({ ...l, connected: false })),
    );
    return () => {
      stop();
      last.current = null;
    };
  }, [id, enabled, queryClient]);

  return live;
}
