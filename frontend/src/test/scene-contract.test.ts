import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { PAIRED_TYPES, VISUAL_TYPES } from '../api/projects';
import { en } from '../i18n/en';
import { sq } from '../i18n/sq';

/*
 * The frontend's scene types must match contracts/scene-catalog.json, which the backend writes
 * from its scene catalog (SceneContractTests). When this fails, a scene type was added or renamed
 * in one place only.
 */

type Contract = { version: number; types: Record<string, { fields: string[]; styles: string[] }> };

const here = path.dirname(fileURLToPath(import.meta.url));
const contract: Contract = JSON.parse(fs.readFileSync(path.resolve(here, '../../../contracts/scene-catalog.json'), 'utf8'));
const contractTypes = Object.keys(contract.types).sort();

describe('scene contract', () => {
  it('knows exactly the contract scene types', () => {
    expect([...VISUAL_TYPES].sort()).toEqual(contractTypes);
  });

  it('has a name for every scene type in both languages', () => {
    for (const type of contractTypes) {
      expect((en.visual as Record<string, string>)[type], `en visual.${type}`).toBeTruthy();
      expect((sq.visual as Record<string, string>)[type], `sq visual.${type}`).toBeTruthy();
    }
  });

  it('pairs items only for types that have a list beside them', () => {
    for (const type of PAIRED_TYPES) {
      const fields = contract.types[type]?.fields ?? [];
      expect(fields, type).toContain('items');
      expect(fields.some((f) => ['values', 'labels', 'icons', 'messages'].includes(f)), type).toBe(true);
    }
  });
});
