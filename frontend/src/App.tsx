import React from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { GoogleOAuthProvider } from '@react-oauth/google';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, GuestOnly, RequireAuth } from './auth/AuthContext';
import { AppLayout } from './components/layouts';
import { GOOGLE_CLIENT_ID } from './components/GoogleButton';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { ForgotPasswordPage, ResetPasswordPage, VerifyEmailPage } from './pages/PasswordPages';
import { AccountPage, ErrorPage, HomePage, NotFoundPage } from './pages/AppPages';
import { NewVideoPage } from './pages/NewVideoPage';
import { ProjectPage } from './pages/ProjectPage';
import { BuyCreditsPage } from './pages/BuyCreditsPage';

export const routes = [
  { path: '/login', element: <GuestOnly><LoginPage /></GuestOnly> },
  { path: '/register', element: <GuestOnly><RegisterPage /></GuestOnly> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  // the email links from the backend (AccountEmails): /verify-email?token= and /reset-password?token=
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/verify-email', element: <VerifyEmailPage /> },
  {
    path: '/',
    element: <RequireAuth><AppLayout /></RequireAuth>,
    errorElement: <ErrorPage />,
    children: [
      {
        // an error inside a page keeps the header and tab bar, so you can go on
        errorElement: <ErrorPage />,
        children: [
          { index: true, element: <HomePage /> },
      { path: 'new', element: <NewVideoPage /> },
      { path: 'projects/:id', element: <ProjectPage /> },
          { path: 'account', element: <AccountPage /> },
          { path: 'credits', element: <BuyCreditsPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
];

const router = createBrowserRouter(routes);

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

export const App: React.FC = () => {
  const app = (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  );
  return GOOGLE_CLIENT_ID ? <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>{app}</GoogleOAuthProvider> : app;
};
