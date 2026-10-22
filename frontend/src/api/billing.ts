import { api } from './client';

/** GET /api/billing/packs (backend BillingController). */
export type Pack = { id: string; credits: number; displayPrice: string; priceId: string };
export type Packs = { enabled: boolean; environment: 'sandbox' | 'production'; clientToken: string; packs: Pack[] };

export const billingApi = {
  packs: () => api<Packs>('/api/billing/packs'),
};
