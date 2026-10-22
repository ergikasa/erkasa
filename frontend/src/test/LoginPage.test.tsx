import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import { setLanguage } from '../i18n';

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

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

describe('login page', () => {
  it('checks the fields before calling the API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch');
    renderAt('/login');
    await userEvent.click(await screen.findByRole('button', { name: 'Log in' }));
    expect(screen.getAllByText('Required')).toHaveLength(2);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows the translated error for wrong credentials', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(401, { code: 'INVALID_CREDENTIALS', detail: 'x' }));
    renderAt('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'ergi@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'wrongpass');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password.');
  });

  it('logs in and opens the app', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) =>
      String(input).startsWith('/api/projects') ? json(200, []) : json(200, {
        accessToken: 'A',
        tokenType: 'Bearer',
        expiresIn: 900,
        refreshToken: 'R',
        refreshExpiresIn: 100,
        user: { id: 'u', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z' },
      }),
    );

    const router = renderAt('/login');
    await userEvent.type(await screen.findByLabelText('Email'), 'ergi@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'secret123');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByText('Hi Ergi')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });

  it('guests are sent to login', async () => {
    const router = renderAt('/account');
    await screen.findByRole('button', { name: 'Log in' });
    expect(router.state.location.pathname).toBe('/login');
  });

  it('speaks Albanian', async () => {
    setLanguage('sq');
    renderAt('/login');
    expect(await screen.findByRole('heading', { name: 'Mirë se erdhe' })).toBeInTheDocument();
    setLanguage('en');
  });
});
