import { describe, expect, it, vi } from 'vitest';
import { ApiError, api, session } from '../api/client';

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const login = (access: string, refresh: string) => ({
  accessToken: access,
  tokenType: 'Bearer',
  expiresIn: 900,
  refreshToken: refresh,
  refreshExpiresIn: 2592000,
  user: { id: 'u1', email: 'a@b.c', displayName: null, emailVerified: true, role: 'USER' as const, createdAt: '2026-10-01T00:00:00Z' },
});

describe('api client', () => {
  it('sends the access token', async () => {
    session.start(login('A1', 'R1'));
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(200, { ok: true }));
    await api('/api/me');
    const headers = fetchMock.mock.calls[0][1]!.headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer A1');
  });

  it('on 401 refreshes once and repeats the call', async () => {
    session.start(login('OLD', 'R1'));
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(401, { code: 'UNAUTHORIZED' }))
      .mockResolvedValueOnce(json(200, login('NEW', 'R2')))
      .mockResolvedValueOnce(json(200, { id: 'p1' }));

    await expect(api<{ id: string }>('/api/projects/p1')).resolves.toEqual({ id: 'p1' });
    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh');
    expect(JSON.parse(fetchMock.mock.calls[1][1]!.body as string)).toEqual({ refreshToken: 'R1' });
    expect((fetchMock.mock.calls[2][1]!.headers as Record<string, string>).Authorization).toBe('Bearer NEW');
    expect(session.refreshToken()).toBe('R2');
  });

  it('parallel 401s share one refresh', async () => {
    session.start(login('OLD', 'R1'));
    let refreshes = 0;
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, init) => {
      if (url === '/api/auth/refresh') {
        refreshes++;
        return json(200, login('NEW', 'R2'));
      }
      const auth = (init!.headers as Record<string, string>).Authorization;
      return auth === 'Bearer NEW' ? json(200, {}) : json(401, {});
    });
    await Promise.all([api('/api/a'), api('/api/b'), api('/api/c')]);
    expect(refreshes).toBe(1);
  });

  it('an expired refresh token ends the session', async () => {
    session.start(login('OLD', 'R1'));
    const expired = vi.fn();
    const off = session.onExpired(expired);
    vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(json(401, {}))
      .mockResolvedValueOnce(json(401, { code: 'INVALID_REFRESH_TOKEN' }));

    await expect(api('/api/me')).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
    expect(session.refreshToken()).toBeNull();
    off();
  });

  it('turns ProblemDetail answers into ApiError with code and field errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      json(400, { code: 'VALIDATION_FAILED', detail: 'Some fields are not valid', errors: { email: 'Email is not valid' } }),
    );
    const err = await api('/api/auth/register', { body: {}, auth: false }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 400, code: 'VALIDATION_FAILED', fields: { email: 'Email is not valid' } });
  });

  it('a network failure is a NETWORK error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(api('/api/me')).rejects.toMatchObject({ code: 'NETWORK' });
  });
});
