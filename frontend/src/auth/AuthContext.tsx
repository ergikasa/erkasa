import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { Navigate, useLocation } from 'react-router';
import { authApi } from '../api/auth';
import { User, session } from '../api/client';

type AuthState =
  | { status: 'loading'; user: null }
  | { status: 'guest'; user: null }
  | { status: 'user'; user: User };

type AuthContextValue = AuthState & {
  login: (email: string, password: string) => Promise<void>;
  loginWithGoogle: (idToken: string) => Promise<void>;
  logout: () => Promise<void>;
  logoutEverywhere: () => Promise<void>;
  /** reload the user from /api/me (e.g. after the email was confirmed) */
  reload: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [state, setState] = useState<AuthState>({ status: 'loading', user: null });

  useEffect(() => {
    let alive = true;
    authApi
      .resume()
      .then((user) => alive && setState(user ? { status: 'user', user } : { status: 'guest', user: null }))
      .catch(() => alive && setState({ status: 'guest', user: null }));
    const off = session.onExpired(() => setState({ status: 'guest', user: null }));
    return () => {
      alive = false;
      off();
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const user = await authApi.login(email, password);
    setState({ status: 'user', user });
  }, []);

  const loginWithGoogle = useCallback(async (idToken: string) => {
    const user = await authApi.google(idToken);
    setState({ status: 'user', user });
  }, []);

  const logout = useCallback(async () => {
    await authApi.logout();
    setState({ status: 'guest', user: null });
  }, []);

  const logoutEverywhere = useCallback(async () => {
    await authApi.logoutEverywhere();
    setState({ status: 'guest', user: null });
  }, []);

  const reload = useCallback(async () => {
    if (!session.hasAccessToken() && !session.refreshToken()) return;
    const user = await authApi.me();
    setState({ status: 'user', user });
  }, []);

  const value = useMemo(
    () => ({ ...state, login, loginWithGoogle, logout, logoutEverywhere, reload }),
    [state, login, loginWithGoogle, logout, logoutEverywhere, reload],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Pages for logged-in users: guests go to /login and come back here afterwards. */
export const RequireAuth: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const auth = useAuth();
  const location = useLocation();
  if (auth.status === 'loading') return <FullScreenSpinner />;
  if (auth.status === 'guest') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  return <>{children}</>;
};

/** Login / register: a logged-in user goes straight to the app. */
export const GuestOnly: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const auth = useAuth();
  if (auth.status === 'loading') return <FullScreenSpinner />;
  if (auth.status === 'user') return <Navigate to="/" replace />;
  return <>{children}</>;
};

export const FullScreenSpinner: React.FC = () => (
  <div className="flex min-h-dvh items-center justify-center" role="status" aria-label="Loading">
    <span className="size-8 animate-spin rounded-full border-2 border-line border-t-accent" />
  </div>
);
