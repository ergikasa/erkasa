import React from 'react';
import { GoogleLogin } from '@react-oauth/google';
import { useTranslation } from 'react-i18next';

export const GOOGLE_CLIENT_ID = (import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined) ?? '';

/** "Continue with Google". Hidden when VITE_GOOGLE_CLIENT_ID is not set. */
export const GoogleButton: React.FC<{ onToken: (idToken: string) => void; onError: () => void }> = ({ onToken, onError }) => {
  const { t } = useTranslation();
  if (!GOOGLE_CLIENT_ID) return null;
  return (
    <>
      <div className="my-5 flex items-center gap-3 text-xs uppercase tracking-wider text-muted">
        <span className="h-px flex-1 bg-line" />
        {t('auth.or')}
        <span className="h-px flex-1 bg-line" />
      </div>
      <div className="flex justify-center">
        <GoogleLogin
          onSuccess={(r) => (r.credential ? onToken(r.credential) : onError())}
          onError={onError}
          theme="filled_black"
          shape="pill"
          size="large"
          width="320"
          text="continue_with"
        />
      </div>
    </>
  );
};
