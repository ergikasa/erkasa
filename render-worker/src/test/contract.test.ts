import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ICONS } from '../icons';
import { MOODS } from '../music';
import { POSES } from '../poses';
import { SOUNDS } from '../sfx';
import { normalizeVisual } from '../spec';

/*
 * The worker's lists must match contracts/scene-catalog.json, which the backend writes from its
 * scene catalog (SceneContractTests). When this fails, a name changed in one place only: fix the
 * list here, or update the contract from the backend.
 */

type Contract = {
  version: number;
  types: Record<string, { fields: string[]; styles: string[] }>;
  styles: Record<string, string[]>;
  icons: string[];
  poses: string[];
  sounds: string[];
  musicMoods: string[];
};

const here = path.dirname(fileURLToPath(import.meta.url));
const contract: Contract = JSON.parse(fs.readFileSync(path.resolve(here, '../../../contracts/scene-catalog.json'), 'utf8'));
const sorted = (xs: Iterable<string>) => [...xs].sort();

describe('scene contract', () => {
  it('can draw every scene type of the contract', () => {
    for (const type of Object.keys(contract.types)) {
      // a type the worker does not know falls back to "image": that would be a broken scene
      expect(normalizeVisual({ type }).type, `normalizeVisual knows "${type}"`).toBe(type);
    }
  });

  it('has exactly the contract icons', () => {
    expect(sorted(Object.keys(ICONS))).toEqual(sorted(contract.icons));
  });

  it('has exactly the contract poses', () => {
    expect(sorted(Object.keys(POSES))).toEqual(sorted(contract.poses));
  });

  it('has exactly the contract sounds', () => {
    expect(sorted(Object.keys(SOUNDS))).toEqual(sorted(contract.sounds));
  });

  it('has a music folder name for every mood', () => {
    expect(sorted(MOODS)).toEqual(sorted(contract.musicMoods));
  });
});
