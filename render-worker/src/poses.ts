/**
 * Catalog pose -> Open Peeps body + face + framing (9.4). The names are the contract with the
 * backend (SceneCatalog.POSES); src/test/contract.test.ts checks them against
 * contracts/scene-catalog.json. Kept free of React so tests can import it.
 */

export type Shot = 'full' | 'sitting' | 'bust';

export const POSES: Record<string, { body: string; face: string; shot: Shot }> = {
  standing: { body: 'ShirtPantsWB', face: 'Smile', shot: 'full' },
  waving: { body: 'EasingWB', face: 'SmileBig', shot: 'full' },
  pointing: { body: 'PointingFingerWB', face: 'Explaining', shot: 'full' },
  presenting: { body: 'Explaining', face: 'Explaining', shot: 'bust' },
  idea: { body: 'PointingUp', face: 'SmileBig', shot: 'bust' },
  thinking: { body: 'CrossedArmsWB', face: 'Serious', shot: 'full' },
  confused: { body: 'RestingWB', face: 'Concerned', shot: 'full' },
  surprised: { body: 'EasingWB', face: 'Awe', shot: 'full' },
  happy: { body: 'EasingWB', face: 'SmileBig', shot: 'full' },
  celebrating: { body: 'RoboDanceWB', face: 'SmileLol', shot: 'full' },
  worried: { body: 'CrossedArmsWB', face: 'ConcernedFear', shot: 'full' },
  working: { body: 'Geek', face: 'Driven', shot: 'bust' },
  phone: { body: 'Device', face: 'Calm', shot: 'bust' },
  walking: { body: 'WalkingWB', face: 'Smile', shot: 'full' },
  sitting: { body: 'ClosedLegWB', face: 'Calm', shot: 'sitting' },
};
