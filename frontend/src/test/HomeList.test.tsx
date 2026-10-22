import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import type { Project } from '../api/projects';

/* 6.4: "Your videos" lists the projects, newest first, and opens one. */

const user = { id: 'u', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z' };
const login = { accessToken: 'A', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'R', refreshExpiresIn: 100, user };

const brief = (id: string, patch: Partial<Project>): Project => ({
  id, status: 'DONE', idea: 'idea ' + id, language: 'EN', durationSeconds: 30, aspectRatio: 'PORTRAIT', voiceId: null,
  title: null, script: null, estimatedCredits: 30, errorCode: null, errorMessage: null,
  createdAt: new Date(Date.now() - 5 * 60_000).toISOString(), updatedAt: '', jobs: [], ...patch,
});

let list: Project[];
let urls: string[];

beforeEach(() => {
  urls = [];
  localStorage.setItem('erkasa.refreshToken', 'R');
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input);
    urls.push(url);
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/auth/refresh') return json(login);
    if (url === '/api/me') return json(user);
    if (url.startsWith('/api/projects?')) return json(list);
    if (url.startsWith('/api/projects/')) return json(list.find((p) => url.endsWith(p.id)) ?? {});
    return json({});
  });
});

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return router;
}

describe('your videos', () => {
  it('shows each project with its status and opens it', async () => {
    list = [
      brief('p2', { title: 'How CI/CD works', status: 'DONE', aspectRatio: 'LANDSCAPE', language: 'SQ' }),
      brief('p1', { title: null, idea: 'Gold vs USDT', status: 'ASSETS_PENDING' }),
    ];
    const router = renderAt('/');
    const first = await screen.findByRole('link', { name: /How CI\/CD works/ });
    expect(within(first).getByText('Done')).toBeInTheDocument();
    expect(within(first).getByText(/Albanian · 30 s · 16:9 · 5 min ago/)).toBeInTheDocument();
    // no title yet: the idea is shown
    expect(within(screen.getByRole('link', { name: /Gold vs USDT/ })).getByText('Making files')).toBeInTheDocument();

    await userEvent.click(first);
    expect(router.state.location.pathname).toBe('/projects/p2');
  });

  it('shows the empty state for a new account', async () => {
    list = [];
    renderAt('/');
    expect(await screen.findByText('No videos yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Create your first video' })).toHaveAttribute('href', '/new');
  });

  it('loads more when the first page is full', async () => {
    list = Array.from({ length: 20 }, (_, i) => brief(`p${i}`, { title: `Video ${i}` }));
    renderAt('/');
    await userEvent.click(await screen.findByRole('button', { name: 'Show more' }));
    expect(urls).toContain('/api/projects?limit=50');
  });
});
