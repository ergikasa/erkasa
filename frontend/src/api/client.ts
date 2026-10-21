/**
 * The one place that talks HTTP to the backend.
 *
 * - The access token (15 min) lives only in memory.
 * - The refresh token lives in localStorage, so a reload keeps you logged in.
 *   (Trade-off: a script injected into the page could read it. We render no user HTML and
 *   load no third-party scripts except Google sign-in; httpOnly cookies can come later.)
 * - A 401 refreshes the tokens ONCE (shared by parallel calls) and repeats the call.
 */

const REFRESH_KEY = 'erkasa.refreshToken';
const BASE = (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, '') ?? '';

export type LoginResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  user: User;
};

export type User = {
  id: string;
  email: string;
  displayName: string | null;
  emailVerified: boolean;
  role: 'USER' | 'ADMIN';
  createdAt: string;
};

/** An error answer from the API (Spring ProblemDetail + our "code"). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    /** field -> message, for VALIDATION_FAILED */
    readonly fields: Record<string, string> = {},
    /** what is wrong with a script, for INVALID_SCRIPT (422) */
    readonly problems: string[] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

let accessToken: string | null = null;
let refreshing: Promise<boolean> | null = null;
const expiredListeners = new Set<() => void>();

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null; // private mode / blocked storage: the session lasts until the tab closes
  }
}

export const session = {
  start(login: LoginResponse) {
    accessToken = login.accessToken;
    try {
      storage()?.setItem(REFRESH_KEY, login.refreshToken);
    } catch {
      /* ignore */
    }
  },
  clear() {
    accessToken = null;
    try {
      storage()?.removeItem(REFRESH_KEY);
    } catch {
      /* ignore */
    }
  },
  refreshToken(): string | null {
    try {
      return storage()?.getItem(REFRESH_KEY) ?? null;
    } catch {
      return null;
    }
  },
  hasAccessToken: () => accessToken !== null,
  /** Called when the session cannot be renewed (refresh token expired or revoked). */
  onExpired(listener: () => void): () => void {
    expiredListeners.add(listener);
    return () => expiredListeners.delete(listener);
  },
};

type Options = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** false for login, register and the other public endpoints */
  auth?: boolean;
  signal?: AbortSignal;
};

export async function api<T = void>(path: string, options: Options = {}): Promise<T> {
  const auth = options.auth ?? true;
  let res = await send(path, options, auth);
  if (res.status === 401 && auth && session.refreshToken()) {
    if (await refreshOnce()) {
      res = await send(path, options, auth);
    }
  }
  return read<T>(res);
}

function send(path: string, options: Options, auth: boolean): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && accessToken) headers.Authorization = `Bearer ${accessToken}`;
  return fetch(BASE + path, {
    method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  }).catch((e: unknown) => {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, 'NETWORK', 'Cannot reach the server');
  });
}

async function read<T>(res: Response): Promise<T> {
  const text = await res.text();
  const data = text ? safeJson(text) : undefined;
  if (!res.ok) {
    const body = (data ?? {}) as { code?: string; detail?: string; errors?: Record<string, string>; problems?: string[] };
    throw new ApiError(
      res.status,
      body.code ?? `HTTP_${res.status}`,
      body.detail ?? res.statusText,
      body.errors ?? {},
      Array.isArray(body.problems) ? body.problems : [],
    );
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Renews the tokens; parallel 401s share one refresh call. */
export function refreshOnce(): Promise<boolean> {
  if (!refreshing) {
    refreshing = doRefresh().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

async function doRefresh(): Promise<boolean> {
  const token = session.refreshToken();
  if (!token) return false;
  try {
    const res = await send('/api/auth/refresh', { method: 'POST', body: { refreshToken: token } }, false);
    if (!res.ok) {
      // 401 = expired / revoked: the user must log in again. Other errors (server down) keep
      // the refresh token, so the next try can still work.
      if (res.status === 400 || res.status === 401) {
        session.clear();
        expiredListeners.forEach((l) => l());
      }
      return false;
    }
    session.start((await res.json()) as LoginResponse);
    return true;
  } catch {
    return false;
  }
}
