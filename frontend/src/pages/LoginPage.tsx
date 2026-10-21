import React, { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/layouts';
import { GoogleButton } from '../components/GoogleButton';
import { Alert, Button, PasswordField, TextField, useErrorText } from '../components/ui';
import { isEmail } from './validate';

export const LoginPage: React.FC = () => {
  const { t } = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const errorText = useErrorText();
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errors = {
      email: !email.trim() ? t('errors.required') : !isEmail(email) ? t('errors.email') : undefined,
      password: !password ? t('errors.required') : undefined,
    };
    setFieldErrors(errors);
    if (errors.email || errors.password) return;
    setBusy(true);
    setError(null);
    try {
      await auth.login(email.trim(), password);
      navigate(from, { replace: true });
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const google = async (idToken: string) => {
    setError(null);
    try {
      await auth.loginWithGoogle(idToken);
      navigate(from, { replace: true });
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <AuthLayout title={t('auth.login.title')} subtitle={t('auth.login.subtitle')}>
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
          error={fieldErrors.email}
        />
        <PasswordField
          label={t('auth.password')}
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
        />
        <div className="flex justify-end">
          <Link to="/forgot-password" className="py-2 text-sm text-accent hover:underline">
            {t('auth.login.forgot')}
          </Link>
        </div>
        <Button type="submit" full loading={busy}>
          {t('auth.login.submit')}
        </Button>
      </form>
      <GoogleButton onToken={(tok) => void google(tok)} onError={() => setError(t('errors.INVALID_GOOGLE_TOKEN'))} />
      <p className="mt-6 text-center text-sm text-muted">
        {t('auth.login.noAccount')}{' '}
        <Link to="/register" className="font-semibold text-accent hover:underline">
          {t('auth.login.register')}
        </Link>
      </p>
    </AuthLayout>
  );
};
