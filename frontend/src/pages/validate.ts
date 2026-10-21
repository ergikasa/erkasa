/** The same rules as the backend (RegisterRequest / LoginRequest), checked before sending. */
export const isEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.trim());
export const passwordOk = (v: string) => v.length >= 8 && new TextEncoder().encode(v).length <= 72;
