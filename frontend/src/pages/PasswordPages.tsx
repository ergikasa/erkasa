import React, { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useTranslation } from 'react-i18next';
import { authApi } from '../api/auth';
import { useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/layouts';
import { Alert, Button, PasswordField, TextField, useErrorText } from '../components/ui';
import { isEmail, passwordOk } from './validate';

export const ForgotPasswordPage: React.FC = () => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const [email, setEmail] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isEmail(email)) return setFieldError(t('errors.email'));
    setFieldError(undefined);
    setBusy(true);
    setError(null);
    try {
      await authApi.forgotPassword(email.trim());
      setSentTo(email.trim());
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={t('auth.forgot.title')} subtitle={sentTo ? undefined : t('auth.forgot.subtitle')}>
      {sentTo ? (
        <Alert tone="success">{t('auth.forgot.sent', { email: sentTo })}</Alert>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-4">
          {error ? <Alert>{error}</Alert> : null}
          <TextField
            label={t('auth.email')}
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={fieldError}
          />
          <Button type="submit" full loading={busy}>
            {t('auth.forgot.submit')}
          </Button>
        </form>
      )}
      <p className="mt-6 text-center text-sm">
        <Link to="/login" className="font-semibold text-accent hover:underline">
          {t('auth.forgot.back')}
        </Link>
      </p>
    </AuthLayout>
  );
};

/** Opened from the email: /reset-password?token=... */
export const ResetPasswordPage: React.FC = () => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [fieldError, setFieldError] = useState<string>();
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!passwordOk(password)) return setFieldError(t('errors.passwordLength'));
    setFieldError(undefined);
    setBusy(true);
    setError(null);
    try {
      await authApi.resetPassword(token, password);
      setDone(true);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout title={t('auth.reset.title')}>
      {!token ? (
        <Alert>{t('auth.reset.noToken')}</Alert>
      ) : done ? (
        <div className="space-y-4">
          <Alert tone="success">{t('auth.reset.done')}</Alert>
          <Link to="/login" className="block">
            <Button full>{t('auth.login.submit')}</Button>
          </Link>
        </div>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-4">
          {error ? <Alert>{error}</Alert> : null}
          <PasswordField
            label={t('auth.newPassword')}
            autoComplete="new-password"
            hint={t('auth.passwordHint')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={fieldError}
          />
          <Button type="submit" full loading={busy}>
            {t('auth.reset.submit')}
          </Button>
        </form>
      )}
    </AuthLayout>
  );
};

/** Opened from the email: /verify-email?token=... Works logged in or not. */
export const VerifyEmailPage: React.FC = () => {
  const { t } = useTranslation();
  const errorText = useErrorText();
  const auth = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') ?? '';
  const [state, setState] = useState<{ done: boolean; error: string | null }>({ done: false, error: null });
  const started = useRef(false); // React StrictMode runs effects twice in dev; the token works once

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (!token) {
      setState({ done: false, error: t('auth.reset.noToken') });
      return;
    }
    authApi
      .verifyEmail(token)
      .then(() => auth.reload().catch(() => undefined))
      .then(() => setState({ done: true, error: null }))
      .catch((err) => setState({ done: false, error: errorText(err) }));
  }, [token, auth, t, errorText]);

  return (
    <AuthLayout title={state.done ? t('auth.verify.done') : t('auth.verify.title')}>
      {state.error ? <Alert>{state.error}</Alert> : null}
      {state.done || state.error ? (
        <Button full className="mt-4" onClick={() => navigate('/', { replace: true })}>
          {t('auth.verify.continue')}
        </Button>
      ) : (
        <div className="flex justify-center py-6">
          <span className="size-8 animate-spin rounded-full border-2 border-line border-t-accent" />
        </div>
      )}
    </AuthLayout>
  );
};
