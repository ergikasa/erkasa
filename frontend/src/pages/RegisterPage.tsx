import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { useTranslation } from 'react-i18next';
import { authApi } from '../api/auth';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { AuthLayout } from '../components/layouts';
import { GoogleButton } from '../components/GoogleButton';
import { Alert, Button, PasswordField, TextField, useErrorText } from '../components/ui';
import { isEmail, passwordOk } from './validate';

type Errors = { email?: string; password?: string; displayName?: string };

export const RegisterPage: React.FC = () => {
  const { t } = useTranslation();
  const auth = useAuth();
  const navigate = useNavigate();
  const errorText = useErrorText();

  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fieldErrors, setFieldErrors] = useState<Errors>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errors: Errors = {
      email: !email.trim() ? t('errors.required') : !isEmail(email) ? t('errors.email') : undefined,
      password: !passwordOk(password) ? t('errors.passwordLength') : undefined,
    };
    setFieldErrors(errors);
    if (errors.email || errors.password) return;
    setBusy(true);
    setError(null);
    try {
      await authApi.register(email.trim(), password, displayName.trim());
      // straight in: the "confirm your email" banner reminds them in the app
      await auth.login(email.trim(), password);
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.code === 'VALIDATION_FAILED') {
        setFieldErrors(err.fields as Errors);
      }
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const google = async (idToken: string) => {
    setError(null);
    try {
      await auth.loginWithGoogle(idToken);
      navigate('/', { replace: true });
    } catch (err) {
      setError(errorText(err));
    }
  };

  return (
    <AuthLayout title={t('auth.register.title')} subtitle={t('auth.register.subtitle')}>
      <form onSubmit={submit} noValidate className="space-y-4">
        {error ? <Alert>{error}</Alert> : null}
        <TextField
          label={t('auth.nameOptional')}
          autoComplete="name"
          maxLength={100}
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          error={fieldErrors.displayName}
        />
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
          autoComplete="new-password"
          hint={t('auth.passwordHint')}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
        />
        <Button type="submit" full loading={busy}>
          {t('auth.register.submit')}
        </Button>
      </form>
      <GoogleButton onToken={(tok) => void google(tok)} onError={() => setError(t('errors.INVALID_GOOGLE_TOKEN'))} />
      <p className="mt-6 text-center text-sm text-muted">
        {t('auth.register.haveAccount')}{' '}
        <Link to="/login" className="font-semibold text-accent hover:underline">
          {t('auth.register.login')}
        </Link>
      </p>
    </AuthLayout>
  );
};
