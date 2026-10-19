/**
 * Writes a demo spec that uses every motion-graphics template, with no AI files at all:
 * silent scenes, word timings estimated from the narration (2.6 words per second).
 *
 *   npm run demo                 -> samples/demo/spec.json (PORTRAIT)
 *   npm run demo -- LANDSCAPE    -> 16:9 version
 *   npm run render -- samples/demo/spec.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { AspectRatio, RenderSpec, SpecScene, Visual, Word } from './spec';

const WORDS_PER_SECOND = 2.6;

function scene(narration: string, visual: Visual): SpecScene {
  const parts = narration.split(/\s+/).filter(Boolean);
  const words: Word[] = parts.map((word, i) => ({
    word,
    startSeconds: +(0.2 + i / WORDS_PER_SECOND).toFixed(2),
    endSeconds: +(0.2 + (i + 0.85) / WORDS_PER_SECOND).toFixed(2),
  }));
  const audioSeconds = +(words[words.length - 1].endSeconds + 0.2).toFixed(2);
  return { audioSeconds, words, visual };
}

const aspect = (process.argv[2] as AspectRatio) || 'PORTRAIT';

const spec: RenderSpec = {
  aspectRatio: aspect,
  title: 'How CI/CD works',
  scenes: [
    scene('How does CI/CD actually work? Let me show you in 30 seconds.', {
      type: 'title',
      title: 'CI/CD',
      subtitle: 'Explained in 30 seconds',
    }),
    scene('Every push goes through four stages. Code, build, test, and deploy.', {
      type: 'flow',
      steps: ['Code', 'Build', 'Test', 'Deploy'],
    }),
    scene('Tests catch bugs early, every change is checked, and nobody deploys by hand.', {
      type: 'list',
      items: ['Tests catch bugs early', 'Every change is checked', 'Nobody deploys by hand'],
    }),
    scene('Teams with CI/CD deploy up to 200 times more often.', {
      type: 'stat',
      value: 200,
      suffix: 'x',
      label: 'more deployments',
    }),
    scene('Without it, releases are manual and scary. With it, they are automatic and boring, in a good way.', {
      type: 'compare',
      leftTitle: 'Without CI/CD',
      rightTitle: 'With CI/CD',
      left: ['Manual releases', 'Bugs found late'],
      right: ['Automatic releases', 'Bugs found in minutes'],
    }),
    scene('Here is a whole pipeline in GitHub Actions. A few lines of YAML.', {
      type: 'code',
      language: 'github-actions.yml',
      code: [
        '# runs on every push',
        'on: push',
        'jobs:',
        '  build:',
        '    runs-on: ubuntu-latest',
        '    steps:',
        '      - uses: actions/checkout@v4',
        '      - run: npm ci',
        '      - run: npm test',
        '      - run: npm run deploy',
      ].join('\n'),
    }),
    scene('Follow for more dev explained in a minute.', {
      type: 'cta',
      text: 'Follow for more',
      handle: '@erkasa',
    }),
  ],
};

const dir = path.join('samples', 'demo');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, aspect === 'PORTRAIT' ? 'spec.json' : `spec-${aspect.toLowerCase()}.json`);
fs.writeFileSync(file, JSON.stringify(spec, null, 2));
console.log(`Wrote ${file}: ${spec.scenes.length} scenes, ${aspect}`);
