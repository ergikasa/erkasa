import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Pack, billingApi } from '../api/billing';
import { CREDITS_KEY, creditsApi } from '../api/credits';
import { useAuth } from '../auth/AuthContext';
import { useCredits } from '../hooks/useCredits';
import { loadPaddle, onPaddleEvent } from '../lib/paddle';
import { Alert, Button, Spinner } from '../components/ui';

/** How long we wait for Paddle's webhook to add the credits before saying "it can take a minute". */
const WAIT_MS = 60_000;

/**
 * Credit packs, paid once with Paddle's checkout overlay. The credits are added by the server
 * when Paddle confirms the payment (webhook), never by the browser; this page only waits for them.
 */
export const BuyCreditsPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const auth = useAuth();
  const queryClient = useQueryClient();
  const credits = useCredits();
  const packs = useQuery({ queryKey: ['packs'], queryFn: billingApi.packs, staleTime: 5 * 60_000 });
  const [opening, setOpening] = useState<string | null>(null);
  const [state, setState] = useState<{ kind: 'idle' | 'processing' | 'done' | 'slow' | 'error'; added?: number }>({ kind: 'idle' });
  const before = useRef<number>(0);

  // Paddle tells us when the payment went through; then we wait for the server to add the credits
  useEffect(() => {
    onPaddleEvent((e) => {
      if (e.name !== 'checkout.completed') return;
      setState({ kind: 'processing' });
      const start = Date.now();
      const poll = async () => {
        const now = await creditsApi.get(5).catch(() => null);
        if (now && now.balance > before.current) {
          queryClient.setQueryData(CREDITS_KEY, now);
          setState({ kind: 'done', added: now.balance - before.current });
          return;
        }
        if (Date.now() - start > WAIT_MS) return setState({ kind: 'slow' });
        window.setTimeout(() => void poll(), 2000);
      };
      void poll();
    });
    return () => onPaddleEvent(null);
  }, [queryClient]);

  const buy = async (pack: Pack) => {
    if (!packs.data || auth.status !== 'user') return;
    setOpening(pack.id);
    setState({ kind: 'idle' });
    before.current = credits.data?.balance ?? 0;
    try {
      const paddle = await loadPaddle(packs.data.clientToken, packs.data.environment);
      paddle.Checkout.open({
        items: [{ priceId: pack.priceId, quantity: 1 }],
        customer: { email: auth.user.email },
        // the server gives the credits to this account when Paddle confirms the payment
        customData: { user_id: auth.user.id },
        settings: { displayMode: 'overlay', theme: 'dark', locale: i18n.resolvedLanguage === 'sq' ? 'en' : (i18n.resolvedLanguage ?? 'en'), allowLogout: false },
      });
    } catch {
      setState({ kind: 'error' });
    } finally {
      setOpening(null);
    }
  };

  const list = packs.data?.packs ?? [];
  // the pack with the most credits per dollar
  const best = list.reduce<Pack | null>((b, p) => (!b || perCredit(p) < perCredit(b) ? p : b), null);

  return (
    <section className="mx-auto max-w-4xl">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{t('buy.title')}</h1>
      <p className="mt-2 text-muted">{t('buy.subtitle')}</p>
      {credits.data ? <p className="mt-1 text-sm text-text">{t('credits.youHave', { n: credits.data.balance })}</p> : null}

      <div className="mt-5 space-y-3" aria-live="polite">
        {state.kind === 'processing' ? (
          <Alert tone="info">
            <span className="inline-flex items-center gap-2">
              <Spinner className="size-4" /> {t('buy.processing')}
            </span>
          </Alert>
        ) : null}
        {state.kind === 'done' ? <Alert tone="success">{t('buy.done', { n: state.added })}</Alert> : null}
        {state.kind === 'slow' ? <Alert tone="info">{t('buy.slow')}</Alert> : null}
        {state.kind === 'error' ? <Alert>{t('buy.loadFailed')}</Alert> : null}
      </div>

      {packs.isPending ? (
        <div className="flex justify-center py-16">
          <Spinner />
        </div>
      ) : !packs.data?.enabled || list.length === 0 ? (
        <div className="mt-6">
          <Alert tone="info">{t('buy.notReady')}</Alert>
        </div>
      ) : (
        <>
          <ul className="mt-6 grid gap-3 sm:grid-cols-3">
            {list.map((p, i) => {
              const popular = i === 1 && list.length === 3;
              const isBest = best?.id === p.id && list.length > 1;
              return (
                <li key={p.id} className="min-w-0">
                  <article
                    aria-label={t(`buy.pack.${p.id}`, { defaultValue: p.id })}
                    className={`relative flex h-full flex-col rounded-2xl border bg-surface p-5 ${popular ? 'border-accent shadow-lg shadow-accent/10' : 'border-line'}`}
                  >
                    {popular || isBest ? (
                      <span className={`absolute -top-2.5 left-5 rounded-full px-2.5 py-0.5 text-xs font-semibold ${popular ? 'bg-accent text-bg' : 'bg-accent-2 text-bg'}`}>
                        {popular ? t('buy.popular') : t('buy.bestValue')}
                      </span>
                    ) : null}
                    <h2 className="text-sm font-semibold uppercase tracking-wider text-muted">{t(`buy.pack.${p.id}`, { defaultValue: p.id })}</h2>
                    <p className="mt-2 text-4xl font-black tracking-tight">{p.displayPrice}</p>
                    <p className="mt-3 text-lg font-semibold text-accent">{t('buy.credits', { n: p.credits })}</p>
                    <p className="text-sm text-muted">{t('buy.minutes', { n: Math.round(p.credits / 60) })}</p>
                    {perMinute(p) ? <p className="mt-1 text-xs text-muted">{t('buy.perMinute', { price: perMinute(p) })}</p> : null}
                    <div className="mt-auto pt-5">
                      <Button full variant={popular ? 'primary' : 'secondary'} loading={opening === p.id} disabled={opening !== null || state.kind === 'processing'} onClick={() => void buy(p)}>
                        {t('buy.buy')} {p.displayPrice}
                      </Button>
                    </div>
                  </article>
                </li>
              );
            })}
          </ul>
          <p className="mt-5 text-center text-xs text-muted">{t('buy.secure')}</p>
          {packs.data.environment === 'sandbox' ? <p className="mt-2 text-center text-xs text-accent">{t('buy.sandbox')}</p> : null}
        </>
      )}
    </section>
  );
};

/** "$12" -> 12; null when the price is not a plain number. */
function amount(p: Pack): number | null {
  const n = Number(p.displayPrice.replace(/[^0-9.,]/g, '').replace(',', '.'));
  return Number.isFinite(n) && n > 0 ? n : null;
}

function perCredit(p: Pack): number {
  const a = amount(p);
  return a == null ? Number.POSITIVE_INFINITY : a / p.credits;
}

/** "$0.80" per minute of video, with the same currency sign as the pack. */
function perMinute(p: Pack): string | null {
  const a = amount(p);
  if (a == null) return null;
  const sign = p.displayPrice.replace(/[0-9.,\s]/g, '');
  return `${sign}${((a / p.credits) * 60).toFixed(2)}`;
}
