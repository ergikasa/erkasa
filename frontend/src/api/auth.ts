import { LoginResponse, User, api, refreshOnce, session } from './client';

/** The /api/auth and /api/me endpoints of the backend. */
export const authApi = {
  register: (email: string, password: string, displayName?: string) =>
    api<User>('/api/auth/register', { body: { email, password, displayName: displayName || null }, auth: false }),

  async login(email: string, password: string): Promise<User> {
    const res = await api<LoginResponse>('/api/auth/login', { body: { email, password }, auth: false });
    session.start(res);
    return res.user;
  },

  async google(idToken: string): Promise<User> {
    const res = await api<LoginResponse>('/api/auth/google', { body: { idToken }, auth: false });
    session.start(res);
    return res.user;
  },

  /** Back to a logged-in state after a reload, using the stored refresh token. */
  async resume(): Promise<User | null> {
    if (!session.refreshToken()) return null;
    if (!(await refreshOnce())) return null;
    return api<User>('/api/me');
  },

  me: () => api<User>('/api/me'),

  async logout(): Promise<void> {
    const token = session.refreshToken();
    session.clear();
    if (token) {
      await api('/api/auth/logout', { body: { refreshToken: token }, auth: false }).catch(() => undefined);
    }
  },

  async logoutEverywhere(): Promise<void> {
    await api('/api/auth/logout-all', { method: 'POST' }).catch(() => undefined);
    session.clear();
  },

  verifyEmail: (token: string) => api('/api/auth/verify-email', { body: { token }, auth: false }),
  resendVerification: () => api('/api/auth/resend-verification', { method: 'POST' }),
  forgotPassword: (email: string) => api('/api/auth/forgot-password', { body: { email }, auth: false }),
  resetPassword: (token: string, newPassword: string) =>
    api('/api/auth/reset-password', { body: { token, newPassword }, auth: false }),
};
