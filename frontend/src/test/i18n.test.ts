import { describe, expect, it } from 'vitest';
import { en } from '../i18n/en';
import { sq } from '../i18n/sq';

function keys(o: object, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) => (typeof v === 'object' ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]));
}

describe('translations', () => {
  it('Albanian has exactly the English keys', () => {
    expect(keys(sq).sort()).toEqual(keys(en).sort());
  });

  it('no text is empty and placeholders match', () => {
    const flat = (o: object) => Object.fromEntries(keys(o).map((k) => [k, k.split('.').reduce<any>((x, p) => x[p], o)]));
    const e = flat(en);
    const s = flat(sq);
    for (const k of Object.keys(e)) {
      expect(s[k].trim(), k).not.toBe('');
      expect(s[k].match(/{{\w+}}/g) ?? [], k).toEqual(e[k].match(/{{\w+}}/g) ?? []);
    }
  });
});
