import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import type { Packs } from '../api/billing';

/* Phase 8: credit packs, Paddle checkout with the user's id, waiting for the webhook's credits. */

const user = { id: 'user-1', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z', credits: 30 };
const login = { accessToken: 'A', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'R', refreshExpiresIn: 100, user };

let packs: Packs;
let balance: number;
let paddleCallback: ((e: { name: string }) => void) | undefined;
const open = vi.fn();

beforeEach(() => {
  localStorage.setItem('erkasa.refreshToken', 'R');
  balance = 30;
  packs = {
    enabled: true,
    environment: 'sandbox',
    clientToken: 'test_token',
    packs: [
      { id: 'starter', credits: 300, displayPrice: '$5', priceId: 'pri_s' },
      { id: 'creator', credits: 900, displayPrice: '$12', priceId: 'pri_c' },
      { id: 'pro', credits: 2700, displayPrice: '$29', priceId: 'pri_p' },
    ],
  };
  // Paddle.js is "already loaded": no script tag in tests
  window.Paddle = {
    Environment: { set: vi.fn() },
    Initialize: vi.fn((o: { eventCallback?: (e: { name: string }) => void }) => {
      paddleCallback = o.eventCallback;
    }),
    Checkout: { open },
  } as unknown as Window['Paddle'];
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input);
    const json = (b: unknown) => new Response(JSON.stringify(b), { status: 200, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/auth/refresh') return json(login);
    if (url === '/api/me') return json(user);
    if (url.startsWith('/api/billing/packs')) return json(packs);
    if (url.startsWith('/api/credits')) return json({ balance, transactions: [] });
    return json({});
  });
});

afterEach(() => {
  open.mockReset();
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

describe('buy credits', () => {
  it('shows the three packs with minutes and the price per minute', async () => {
    renderAt('/credits');
    const creator = await screen.findByRole('article', { name: 'Creator' });
    expect(creator).toHaveTextContent('$12');
    expect(creator).toHaveTextContent('900 credits');
    expect(creator).toHaveTextContent('about 15 min of video');
    expect(creator).toHaveTextContent('$0.80 per minute');
    expect(creator).toHaveTextContent('Most popular');
    expect(screen.getByRole('article', { name: 'Pro' })).toHaveTextContent('Best value');
    expect(screen.getByText(/Test mode: pay with card 4242/)).toBeInTheDocument();
  });

  it('opens the Paddle checkout for this account and waits for the credits', async () => {
    renderAt('/credits');
    await userEvent.click(await screen.findByRole('button', { name: 'Buy $12' }));

    expect(window.Paddle!.Environment.set).toHaveBeenCalledWith('sandbox');
    expect(open).toHaveBeenCalledOnce();
    const options = open.mock.calls[0][0];
    expect(options.items).toEqual([{ priceId: 'pri_c', quantity: 1 }]);
    expect(options.customData).toEqual({ user_id: 'user-1' });
    expect(options.customer).toEqual({ email: 'ergi@example.com' });

    // Paddle says paid; the webhook adds the credits a moment later
    balance = 930;
    act(() => paddleCallback!({ name: 'checkout.completed' }));
    expect(await screen.findByText('+900 credits added. Enjoy!', {}, { timeout: 4000 })).toBeInTheDocument();
  });

  it('says so when payments are not set up', async () => {
    packs = { ...packs, enabled: false, packs: [] };
    renderAt('/credits');
    expect(await screen.findByText(/Payments are not set up yet/)).toBeInTheDocument();
  });
});
