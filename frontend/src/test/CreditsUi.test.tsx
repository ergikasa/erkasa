import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import type { Credits } from '../api/credits';

/* Phase 7: the balance in the header, a warning on "New video", the history on Account. */

const user = { id: 'u', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z', credits: 30 };
const login = { accessToken: 'A', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'R', refreshExpiresIn: 100, user };
let credits: Credits;

beforeEach(() => {
  localStorage.setItem('erkasa.refreshToken', 'R');
  credits = {
    balance: 30,
    transactions: [
      { id: 't2', amount: -45, balanceAfter: 30, reason: 'VIDEO_CHARGE', projectId: 'p1', note: null, createdAt: '2026-10-21T10:00:00Z' },
      { id: 't1', amount: 75, balanceAfter: 75, reason: 'ADMIN_GRANT', projectId: null, note: 'beta', createdAt: '2026-10-20T10:00:00Z' },
    ],
  };
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input);
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/auth/refresh') return json(login);
    if (url === '/api/me') return json(user);
    if (url.startsWith('/api/credits')) return json(credits);
    if (url.startsWith('/api/projects?')) return json([]);
    return json({});
  });
});

function renderAt(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('credits in the app', () => {
  it('shows the balance in the header', async () => {
    renderAt('/');
    expect(await screen.findByRole('link', { name: '30 credits' })).toHaveAttribute('href', '/account');
  });

  it('warns on "New video" when the chosen length costs more than the balance', async () => {
    renderAt('/new');
    expect(await screen.findByText(/You have 30 credits/)).toBeInTheDocument();
    expect(screen.queryByText(/This video needs/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: '60 s' }));
    expect(screen.getByText('This video needs 60 credits; you have 30. Choose a shorter length.')).toBeInTheDocument();
  });

  it('lists the history on the account page', async () => {
    renderAt('/account');
    const history = (await screen.findByText('History')).parentElement!;
    // the list arrives a moment after the heading
    expect(await within(history).findByText(/^Video/)).toBeInTheDocument();
    expect(within(history).getByText('Gift')).toBeInTheDocument();
    expect(within(history).getByText('+75')).toBeInTheDocument();
    expect(within(history).getByText('−45')).toBeInTheDocument();
  });
});
