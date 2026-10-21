import '@testing-library/jest-dom/vitest';
import { afterEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';
import i18n from '../i18n';

/*
 * jsdom has its own AbortSignal; Node's Request (used by React Router when it navigates)
 * accepts only Node's. On some Node versions that throws "Expected signal to be an instance
 * of AbortSignal" and the page never renders. Tests mock fetch anyway, so the router's
 * request does not need the signal.
 */
const NodeRequest = globalThis.Request;
globalThis.Request = class extends NodeRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    const { signal: _signal, ...rest } = init ?? {};
    super(input, rest);
  }
} as typeof Request;

// tests run in English unless they switch
void i18n.changeLanguage('en');

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});
