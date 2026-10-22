import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from '../auth/AuthContext';
import { routes } from '../App';
import type { Snapshot } from '../api/events';
import type { Assets, Project, Visual } from '../api/projects';

/* 6.3: live progress (SSE), files per scene, redo a scene, render with %, watch + download. */

// the live stream is replaced by a function the test calls
let push: ((s: Snapshot) => void) | null = null;
vi.mock('../api/events', () => ({
  subscribeProject: (_id: string, onSnapshot: (s: Snapshot) => void) => {
    push = onSnapshot;
    return () => {
      push = null;
    };
  },
}));

const user = { id: 'u', email: 'ergi@example.com', displayName: 'Ergi', emailVerified: true, role: 'USER', createdAt: '2026-10-01T00:00:00Z' };
const login = { accessToken: 'A', tokenType: 'Bearer', expiresIn: 900, refreshToken: 'R', refreshExpiresIn: 100, user };
const V = (p: Partial<Visual>): Visual => ({
  type: 'image', title: '', subtitle: '', items: [], value: 0, prefix: '', suffix: '', label: '',
  leftTitle: '', rightTitle: '', left: [], right: [], code: '', language: '', text: '', handle: '', ...p,
});

const project = (status: Project['status']): Project => ({
  id: 'p1', status, idea: 'CI/CD', language: 'EN', durationSeconds: 30, aspectRatio: 'PORTRAIT', voiceId: null,
  title: 'CI/CD', estimatedCredits: 30, errorCode: null, errorMessage: null, createdAt: '2026-10-21T10:00:00Z',
  updatedAt: '2026-10-21T10:00:00Z', jobs: [{ id: 'j2', kind: 'ASSETS', status: 'RUNNING', errorCode: null, startedAt: null, finishedAt: null }],
  script: {
    title: 'CI/CD', musicMood: 'upbeat',
    scenes: [
      { narration: 'Code, build, test, deploy.', onScreenText: '', imagePrompt: '', visual: V({ type: 'flow', items: ['Code', 'Build'] }), sfx: [] },
      { narration: 'A server room at night.', onScreenText: '', imagePrompt: 'A server room', visual: V({ type: 'image' }), sfx: [] },
    ],
  },
});

const step = (status: 'PENDING' | 'RUNNING' | 'SUCCEEDED', url: string | null = null) => ({ status, attempts: 1, url, meta: null, errorCode: null, errorMessage: null });

const snapshot = (status: Project['status'], patch: Partial<Snapshot> = {}): Snapshot => ({
  projectId: 'p1', status, title: 'CI/CD', done: 0, total: 3, renderPercent: null, errorCode: null, errorMessage: null, at: '', ...patch,
});

type Call = { method: string; url: string; body: unknown };
let calls: Call[];
let state: { project: Project; assets: Assets };

beforeEach(() => {
  calls = [];
  push = null;
  localStorage.setItem('erkasa.refreshToken', 'R');
  URL.createObjectURL = vi.fn(() => 'blob:fake');
  URL.revokeObjectURL = vi.fn();
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const c: Call = { method: init?.method ?? 'GET', url: String(input), body: init?.body ? JSON.parse(String(init.body)) : undefined };
    calls.push(c);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    if (c.url === '/api/auth/refresh') return json(login);
    if (c.url === '/api/me') return json(user);
    if (c.url === '/api/projects/p1') return json(state.project);
    if (c.url === '/api/projects/p1/assets') return json(state.assets);
    if (c.url === '/api/projects/p1/render') {
      state.project = { ...state.project, status: 'RENDERING' };
      return json(state.project, 202);
    }
    if (c.url.startsWith('/api/projects/p1/scenes/')) {
      state.project = { ...state.project, status: 'ASSETS_PENDING' };
      return json(state.project, 202);
    }
    if (c.url === '/api/projects/p1/video') return new Response(new Blob(['mp4'], { type: 'video/mp4' }));
    return json({}, 404);
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
}

describe('live project page', () => {
  it('shows the files scene by scene and moves on when the server says so', async () => {
    state = {
      project: project('ASSETS_PENDING'),
      assets: {
        projectId: 'p1', done: 1, total: 3,
        scenes: [
          { scene: 0, image: null, voice: step('SUCCEEDED', '/api/projects/p1/assets/0/voice') },
          { scene: 1, image: step('RUNNING'), voice: step('PENDING') },
        ],
      },
    };
    renderAt('/projects/p1');
    expect(await screen.findByText('1 of 3 files ready')).toBeInTheDocument();
    const scene1 = screen.getByRole('article', { name: 'Scene 1' });
    expect(within(scene1).getByText('Motion graphic: no picture needed')).toBeInTheDocument();
    expect(within(scene1).getByRole('button', { name: 'Play voice of scene 1' })).toBeInTheDocument();
    expect(within(screen.getByRole('article', { name: 'Scene 2' })).getByText('Working…')).toBeInTheDocument();

    // the server finishes the files
    state.project = project('ASSETS_READY');
    state.assets = { ...state.assets, done: 3 };
    await waitFor(() => expect(push).not.toBeNull());
    act(() => push!(snapshot('ASSETS_READY', { done: 3 })));
    expect(await screen.findByRole('button', { name: 'Make the video' })).toBeInTheDocument();
    expect(await screen.findByText('3 of 3 files ready')).toBeInTheDocument();
  });

  it('"script ready" from the live stream before the script itself does not break the page', async () => {
    const pending = { ...project('SCRIPT_PENDING'), script: null, title: null };
    state = { project: pending, assets: { projectId: 'p1', done: 0, total: 0, scenes: [] } };
    // the next GET is slow: the live event arrives first
    let release: () => void = () => undefined;
    const slow = new Promise<void>((r) => (release = r));
    renderAt('/projects/p1');
    expect(await screen.findByText('Writing your script…')).toBeInTheDocument();
    const original = (globalThis.fetch as unknown as { getMockImplementation: () => (i: RequestInfo, n?: RequestInit) => Promise<Response> }).getMockImplementation();
    vi.mocked(globalThis.fetch).mockImplementation(async (input, init) => {
      if (String(input) === '/api/projects/p1') await slow;
      return original(input as RequestInfo, init);
    });
    state.project = project('SCRIPT_REVIEW');
    await waitFor(() => expect(push).not.toBeNull());
    act(() => push!(snapshot('SCRIPT_REVIEW')));
    // still "writing" (no crash) until the script is here
    expect(screen.getByText('Writing your script…')).toBeInTheDocument();
    release();
    expect(await screen.findByText('Check the script')).toBeInTheDocument();
  });

  it('redoes one scene with a new narration', async () => {
    state = {
      project: project('ASSETS_READY'),
      assets: {
        projectId: 'p1', done: 3, total: 3,
        scenes: [
          { scene: 0, image: null, voice: step('SUCCEEDED', '/api/projects/p1/assets/0/voice') },
          { scene: 1, image: step('SUCCEEDED', '/api/projects/p1/assets/1/image'), voice: step('SUCCEEDED', '/api/projects/p1/assets/1/voice') },
        ],
      },
    };
    renderAt('/projects/p1');
    const scene1 = await screen.findByRole('article', { name: 'Scene 1' });
    await userEvent.click(within(scene1).getByRole('button', { name: 'Redo' }));
    const text = within(scene1).getByLabelText('Voice says (optional change)');
    await userEvent.clear(text);
    await userEvent.type(text, 'Code, build, test and ship.');
    await userEvent.click(within(scene1).getByRole('button', { name: 'Redo this scene' }));

    await waitFor(() => expect(calls.some((c) => c.url === '/api/projects/p1/scenes/0/redo')).toBe(true));
    const redo = calls.find((c) => c.url === '/api/projects/p1/scenes/0/redo')!;
    expect(redo.body).toEqual({ voice: true, image: false, narration: 'Code, build, test and ship.' });
    expect(await screen.findByText('Making the voices and pictures…')).toBeInTheDocument();
  });

  it('renders with a live percentage, then shows the video to watch and download', async () => {
    state = { project: project('ASSETS_READY'), assets: { projectId: 'p1', done: 3, total: 3, scenes: [] } };
    renderAt('/projects/p1');
    await userEvent.click(await screen.findByRole('button', { name: 'Make the video' }));
    expect(await screen.findByText('Rendering the video…')).toBeInTheDocument();

    await waitFor(() => expect(push).not.toBeNull());
    act(() => push!(snapshot('RENDERING', { renderPercent: 40 })));
    await waitFor(() => expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '40'));

    state.project = project('DONE');
    act(() => push!(snapshot('DONE')));
    expect(await screen.findByText('Your video')).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector('video')).toHaveAttribute('src', 'blob:fake'));
    expect(screen.getByRole('link', { name: 'Download MP4' })).toHaveAttribute('download', 'erkasa-ci-cd.mp4');
    expect(calls.find((c) => c.url === '/api/projects/p1/video')).toBeTruthy();
  });
});
