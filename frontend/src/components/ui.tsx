import React, { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../api/client';

/* Small building blocks. Mobile first: every tap target is at least 44 px high. */

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  loading?: boolean;
  full?: boolean;
};

export const Button: React.FC<ButtonProps> = ({ variant = 'primary', loading, full, className = '', children, disabled, ...rest }) => {
  const styles = {
    primary: 'bg-gradient-to-r from-accent to-accent-2 text-bg font-semibold hover:brightness-110',
    secondary: 'border border-line bg-surface-2 text-text hover:border-accent/60',
    ghost: 'text-muted hover:text-text hover:bg-surface-2',
    danger: 'border border-danger/40 text-danger hover:bg-danger/10',
  }[variant];
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-4 text-[15px] transition active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-60 ${styles} ${full ? 'w-full' : ''} ${className}`}
    >
      {loading ? <span className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent" /> : null}
      {children}
    </button>
  );
};

type FieldProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: string;
  error?: string;
  hint?: string;
};

export const TextField: React.FC<FieldProps> = ({ label, error, hint, className = '', ...rest }) => {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-muted">
        {label}
      </label>
      <input
        id={id}
        {...rest}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`block min-h-12 w-full rounded-xl border bg-surface px-4 text-text placeholder:text-muted/60 transition focus:border-accent focus:outline-none ${error ? 'border-danger' : 'border-line'}`}
      />
      {error ? (
        <p id={`${id}-error`} className="mt-1.5 text-sm text-danger">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1.5 text-sm text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
};

export const PasswordField: React.FC<FieldProps> = (props) => {
  const { t } = useTranslation();
  const [visible, setVisible] = useState(false);
  return (
    <div className="relative">
      <TextField {...props} type={visible ? 'text' : 'password'} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? t('auth.hidePassword') : t('auth.showPassword')}
        className="absolute right-1 top-[30px] flex size-11 items-center justify-center rounded-lg text-muted hover:text-text"
      >
        {visible ? <EyeOff /> : <Eye />}
      </button>
    </div>
  );
};

export const Alert: React.FC<{ tone?: 'error' | 'success' | 'info'; children: React.ReactNode }> = ({ tone = 'error', children }) => {
  const styles = {
    error: 'border-danger/40 bg-danger/10 text-danger',
    success: 'border-success/40 bg-success/10 text-success',
    info: 'border-accent/40 bg-accent/10 text-accent',
  }[tone];
  return (
    <div role={tone === 'error' ? 'alert' : 'status'} className={`rounded-xl border px-4 py-3 text-sm ${styles}`}>
      {children}
    </div>
  );
};

/** The translated text for an error from the API (by its code), or the generic one. */
export function useErrorText() {
  const { t, i18n } = useTranslation();
  return (e: unknown): string => {
    if (e instanceof ApiError) {
      const key = `errors.${e.code}`;
      if (i18n.exists(key)) return t(key);
      if (e.status === 429) return t('errors.RATE_LIMITED');
    }
    return t('errors.generic');
  };
}

const Eye = () => (
  <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
    <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const EyeOff = () => (
  <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
    <path d="M3 3l18 18M10.6 5.1A10.8 10.8 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a10 10 0 0 0 5.4-1.6" />
    <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
  </svg>
);
