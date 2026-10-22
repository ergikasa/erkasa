import { api } from './client';

/** GET /api/credits (backend CreditController). 1 credit = 1 second of video. */
export type CreditReason = 'SIGNUP_BONUS' | 'VIDEO_CHARGE' | 'VIDEO_REFUND' | 'ADMIN_GRANT' | 'PURCHASE' | 'PURCHASE_REFUND';
export type CreditTransaction = {
  id: string;
  amount: number;
  balanceAfter: number;
  reason: CreditReason;
  projectId: string | null;
  note: string | null;
  createdAt: string;
};
export type Credits = { balance: number; transactions: CreditTransaction[] };

export const creditsApi = {
  get: (limit = 20) => api<Credits>(`/api/credits?limit=${limit}`),
};

/** React Query key: invalidate it after anything that can change the balance. */
export const CREDITS_KEY = ['credits'] as const;
