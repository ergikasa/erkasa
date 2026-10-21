import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { en } from './en';
import { sq } from './sq';

/** Same shape as en.ts, but any string (so sq.ts can have its own words). */
type Widen<T> = { [K in keyof T]: T[K] extends string ? string : Widen<T[K]> };
export type Messages = Widen<typeof en>;

export const LANGUAGES = [
  { code: 'en', label: 'English', short: 'EN' },
  { code: 'sq', label: 'Shqip', short: 'SQ' },
] as const;
export type LanguageCode = (typeof LANGUAGES)[number]['code'];

const KEY = 'erkasa.language';

/** Saved choice, else the phone / browser language, else English. */
export function initialLanguage(): LanguageCode {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === 'en' || saved === 'sq') return saved;
  } catch {
    /* storage blocked */
  }
  const browser = typeof navigator !== 'undefined' ? navigator.language.toLowerCase() : 'en';
  return browser.startsWith('sq') ? 'sq' : 'en';
}

export function setLanguage(code: LanguageCode) {
  void i18n.changeLanguage(code);
  try {
    localStorage.setItem(KEY, code);
  } catch {
    /* storage blocked */
  }
}

i18n.on('languageChanged', (lng) => {
  if (typeof document !== 'undefined') document.documentElement.lang = lng;
});

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, sq: { translation: sq } },
  lng: initialLanguage(),
  fallbackLng: 'en',
  interpolation: { escapeValue: false }, // React escapes already
  returnNull: false,
  showSupportNotice: false,
});

export default i18n;
