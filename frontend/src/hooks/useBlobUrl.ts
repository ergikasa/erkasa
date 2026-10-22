import { useEffect, useState } from 'react';
import { projectsApi } from '../api/projects';

/**
 * A file from the API (needs the login token, so a plain <img src> / <video src> cannot load it)
 * as an object URL. Freed again when the component goes away or the path changes.
 */
export function useBlobUrl(path: string | null | undefined): { url: string | null; error: boolean } {
  const [state, setState] = useState<{ url: string | null; error: boolean }>({ url: null, error: false });
  useEffect(() => {
    if (!path) {
      setState({ url: null, error: false });
      return;
    }
    const controller = new AbortController();
    let url: string | null = null;
    setState({ url: null, error: false });
    projectsApi
      .file(path, controller.signal)
      .then((blob) => {
        url = URL.createObjectURL(blob);
        setState({ url, error: false });
      })
      .catch((e: unknown) => {
        if (!(e instanceof DOMException && e.name === 'AbortError')) setState({ url: null, error: true });
      });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [path]);
  return state;
}
