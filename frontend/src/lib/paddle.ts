/**
 * Paddle.js v2 (checkout overlay). Loaded only when someone opens "Buy credits", never on other
 * pages. Paddle is the merchant of record: card details never touch our servers.
 * Docs: https://developer.paddle.com/paddlejs/overview
 */

type PaddleEvent = { name?: string; data?: unknown };

type PaddleGlobal = {
  Environment: { set: (env: 'sandbox') => void };
  Initialize: (options: { token: string; eventCallback?: (e: PaddleEvent) => void }) => void;
  Checkout: {
    open: (options: {
      items: { priceId: string; quantity: number }[];
      customer?: { email: string };
      customData?: Record<string, string>;
      settings?: { displayMode?: 'overlay'; theme?: 'dark' | 'light'; locale?: string; allowLogout?: boolean };
    }) => void;
  };
};

declare global {
  interface Window {
    Paddle?: PaddleGlobal;
  }
}

const SCRIPT = 'https://cdn.paddle.com/paddle/v2/paddle.js';
let loading: Promise<PaddleGlobal> | null = null;
let listener: ((e: PaddleEvent) => void) | null = null;

/** Loads and initializes Paddle.js once; later calls reuse it. */
export function loadPaddle(token: string, environment: 'sandbox' | 'production'): Promise<PaddleGlobal> {
  if (!loading) {
    loading = new Promise<PaddleGlobal>((resolve, reject) => {
      const ready = () => {
        const paddle = window.Paddle;
        if (!paddle) return reject(new Error('Paddle.js did not load'));
        if (environment === 'sandbox') paddle.Environment.set('sandbox');
        paddle.Initialize({ token, eventCallback: (e) => listener?.(e) });
        resolve(paddle);
      };
      if (window.Paddle) return ready();
      const s = document.createElement('script');
      s.src = SCRIPT;
      s.async = true;
      s.onload = ready;
      s.onerror = () => {
        loading = null;
        reject(new Error('Paddle.js did not load'));
      };
      document.head.appendChild(s);
    });
  }
  return loading;
}

/** Paddle events (checkout.completed, checkout.closed, ...) go to the page that is open now. */
export function onPaddleEvent(fn: ((e: PaddleEvent) => void) | null) {
  listener = fn;
}
