import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import type { Project, Visual } from '../api/projects';

/* 6.2: new video form, script review (edit, save, problems, approve). The backend is faked. */

const user = { id: 'u', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z' };
const login = { accessToken: 'A', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'R', refreshExpiresIn: 100, user };

const visual = (patch: Partial<Visual>): Visual => ({
  type: 'image', title: '', subtitle: '', items: [], value: 0, prefix: '', suffix: '', label: '',
  leftTitle: '', rightTitle: '', left: [], right: [], code: '', language: '', text: '', handle: '',
  ...patch,
});

const reviewProject = (): Project => ({
  id: 'p1', status: 'SCRIPT_REVIEW', idea: 'How CI/CD works', language: 'EN', durationSeconds: 30,
  aspectRatio: 'PORTRAIT', voiceId: null, title: 'CI/CD in 30 seconds', estimatedCredits: 30,
  errorCode: null, errorMessage: null, createdAt: '2026-10-21T10:00:00Z', updatedAt: '2026-10-21T10:00:00Z', jobs: [],
  script: {
    title: 'CI/CD in 30 seconds',
    musicMood: 'upbeat',
    scenes: [
      { narration: 'Every push goes through four stages.', onScreenText: '', imagePrompt: '',
        visual: visual({ type: 'list', items: ['Build', 'Test'] }), sfx: [{ sound: 'ding', onWord: 'four' }] },
      { narration: 'A server room at night hums along.', onScreenText: 'Servers', imagePrompt: 'A server room at night', visual: visual({ type: 'image' }), sfx: [] },
    ],
  },
});

type Call = { method: string; url: string; body: unknown };
let calls: Call[];
let handler: (c: Call) => { status: number; body: unknown } | undefined;

beforeEach(() => {
  calls = [];
  handler = () => undefined;
  localStorage.setItem('erkasa.refreshToken', 'R');
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const c: Call = { method: init?.method ?? 'GET', url: String(input), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(c);
    const answer =
      c.url === '/api/auth/refresh' ? { status: 200, body: login } : c.url === '/api/me' ? { status: 200, body: user } : handler(c);
    if (!answer) return new Response('{}', { status: 404 });
    return new Response(JSON.stringify(answer.body), { status: answer.status, headers: { 'Content-Type': 'application/json' } });
  });
});

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return router;
}

describe('new video', () => {
  it('sends the idea with language, length and format, then shows the script being written', async () => {
    const pending = { ...reviewProject(), status: 'SCRIPT_PENDING', script: null, title: null, durationSeconds: 60, aspectRatio: 'LANDSCAPE' };
    handler = (c) => (c.method === 'POST' && c.url === '/api/projects' ? { status: 201, body: pending } : c.url === '/api/projects/p1' ? { status: 200, body: pending } : undefined);
    const router = renderAt('/new');

    await userEvent.type(await screen.findByLabelText('Your idea'), 'How CI/CD works');
    await userEvent.click(screen.getByRole('radio', { name: '60 s' }));
    await userEvent.click(screen.getByRole('radio', { name: /Horizontal 16:9/ }));
    expect(screen.getByText('About 60 credits')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Write the script' }));

    expect(await screen.findByText('Writing your script…')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/projects/p1');
    const create = calls.find((c) => c.method === 'POST' && c.url === '/api/projects')!;
    expect(create.body).toEqual({ idea: 'How CI/CD works', language: 'EN', durationSeconds: 60, aspectRatio: 'LANDSCAPE' });
  });

  it('needs an idea', async () => {
    renderAt('/new');
    await userEvent.click(await screen.findByRole('button', { name: 'Write the script' }));
    expect(screen.getByText('Required')).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/projects')).toBe(false);
  });
});

describe('script review', () => {
  it('edits the texts and saves a clean script', async () => {
    let saved: Project | null = null;
    handler = (c) => {
      if (c.method === 'PUT') {
        saved = { ...reviewProject(), script: (c.body as { script: Project['script'] }).script };
        return { status: 200, body: saved };
      }
      if (c.url === '/api/projects/p1') return { status: 200, body: saved ?? reviewProject() };
    };
    renderAt('/projects/p1');

    const scene1 = await screen.findByRole('article', { name: 'Scene 1' });
    const narration = within(scene1).getByLabelText('Voice says');
    await userEvent.clear(narration);
    await userEvent.type(narration, 'Every push is built and tested.  ');
    const items = within(scene1).getByLabelText('Points (one per line)');
    await userEvent.clear(items);
    await userEvent.type(items, 'Build{enter}{enter}Test{enter}Deploy');
    await userEvent.click(within(scene1).getByRole('button', { name: 'Remove ding' }));

    await userEvent.click(screen.getByRole('button', { name: /Save changes/ }));
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true));

    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toBe('/api/projects/p1/script');
    const scene = (put.body as { script: NonNullable<Project['script']> }).script.scenes[0];
    expect(scene.narration).toBe('Every push is built and tested.');
    expect(scene.visual?.items).toEqual(['Build', 'Test', 'Deploy']);
    expect(scene.sfx).toEqual([]);
    expect(await screen.findAllByText('Saved')).not.toHaveLength(0);
  });

  it('shows what the backend says is wrong', async () => {
    handler = (c) =>
      c.method === 'PUT'
        ? { status: 422, body: { code: 'INVALID_SCRIPT', detail: 'The script is not valid', problems: ['Scene 1 has no narration.'] } }
        : c.url === '/api/projects/p1'
          ? { status: 200, body: reviewProject() }
          : undefined;
    renderAt('/projects/p1');
    const scene1 = await screen.findByRole('article', { name: 'Scene 1' });
    await userEvent.clear(within(scene1).getByLabelText('Voice says'));
    await userEvent.click(screen.getByRole('button', { name: /Save changes/ }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The script needs a few fixes.');
    expect(alert).toHaveTextContent('Scene 1 has no narration.');
  });

  it('approve saves unsaved changes first, then starts the files', async () => {
    let status: Project['status'] = 'SCRIPT_REVIEW';
    handler = (c) => {
      if (c.method === 'PUT') return { status: 200, body: reviewProject() };
      if (c.url === '/api/projects/p1/approve') {
        status = 'ASSETS_PENDING';
        return { status: 200, body: { ...reviewProject(), status } };
      }
      if (c.url === '/api/projects/p1') return { status: 200, body: { ...reviewProject(), status } };
    };
    renderAt('/projects/p1');
    const scene2 = await screen.findByRole('article', { name: 'Scene 2' });
    await userEvent.type(within(scene2).getByLabelText('Picture description (English)'), ', rain outside');
    await userEvent.click(screen.getByRole('button', { name: /Approve and make the video/ }));

    expect(await screen.findByText('Making the voices and pictures…')).toBeInTheDocument();
    const order = calls.filter((c) => c.method !== 'GET').map((c) => `${c.method} ${c.url}`);
    expect(order.filter((x) => x.includes('/projects/'))).toEqual(['PUT /api/projects/p1/script', 'POST /api/projects/p1/approve']);
  });
});
