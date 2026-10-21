/**
 * Writes a demo spec that uses every motion-graphics template, with no AI files at all:
 * silent scenes, word timings estimated from the narration (2.6 words per second).
 *
 *   npm run demo                 -> samples/demo/spec.json (PORTRAIT)
 *   npm run demo -- LANDSCAPE    -> 16:9 version
 *   npm run demo -- PORTRAIT 60  -> ~60 s version (extra scenes), for speed tests (5.5)
 *   npm run render -- samples/demo/spec.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { AspectRatio, RenderSpec, SoundCue, SpecScene, Visual, Word } from './spec';

const WORDS_PER_SECOND = 2.6;

function scene(narration: string, visual: Visual, sfx: SoundCue[] = []): SpecScene {
  const parts = narration.split(/\s+/).filter(Boolean);
  const words: Word[] = parts.map((word, i) => ({
    word,
    startSeconds: +(0.2 + i / WORDS_PER_SECOND).toFixed(2),
    endSeconds: +(0.2 + (i + 0.85) / WORDS_PER_SECOND).toFixed(2),
  }));
  const audioSeconds = +(words[words.length - 1].endSeconds + 0.2).toFixed(2);
  return { audioSeconds, words, visual, sfx };
}

const aspect = (process.argv[2] as AspectRatio) || 'PORTRAIT';
const long = process.argv[3] === '60';

/** Extra scenes for the ~60 s version, inserted before the call to action. */
const extra: SpecScene[] = [
  scene('Continuous integration means every developer merges small changes into main, many times a day.', {
    type: 'title',
    title: 'Continuous Integration',
    subtitle: 'Small changes, merged often',
  }),
  scene('A failing test blocks the merge, the author gets a message, fixes it, and pushes again.', {
    type: 'flow',
    steps: ['Push', 'Test fails', 'Fix', 'Push again'],
  }),
  scene('Continuous delivery adds three things. Staging environments, automatic rollbacks, and release notes.', {
    type: 'list',
    items: ['Staging environments', 'Automatic rollbacks', 'Release notes'],
  }),
  scene('Most pipelines finish in under 10 minutes, so feedback comes while the code is still fresh.', {
    type: 'stat',
    value: 10,
    prefix: '<',
    suffix: ' min',
    label: 'from push to feedback',
  }),
];

const spec: RenderSpec = {
  aspectRatio: aspect,
  title: 'How CI/CD works',
  scenes: [
    scene('How does CI/CD actually work? Let me show you in 30 seconds.', {
      type: 'title',
      title: 'CI/CD',
      subtitle: 'Explained in 30 seconds',
    }, [{ sound: 'impact', onWord: '' }]),
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
    }, [{ sound: 'ding', onWord: '200' }]),
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
    }, [{ sound: 'typing', onWord: 'YAML.' }]),
    scene('Follow for more dev explained in a minute.', {
      type: 'cta',
      text: 'Follow for more',
      handle: '@erkasa',
    }, [{ sound: 'success', onWord: 'Follow' }]),
  ],
};

if (long) {
  spec.title = 'CI/CD in one minute';
  spec.scenes.splice(spec.scenes.length - 1, 0, ...extra);
}

const dir = path.join('samples', 'demo');
fs.mkdirSync(dir, { recursive: true });
const file = path.join(dir, (aspect === 'PORTRAIT' ? 'spec' : `spec-${aspect.toLowerCase()}`) + (long ? '-60' : '') + '.json');
fs.writeFileSync(file, JSON.stringify(spec, null, 2));
const seconds = spec.scenes.reduce((t, sc) => t + sc.audioSeconds, 0);
console.log(`Wrote ${file}: ${spec.scenes.length} scenes, ~${Math.round(seconds)} s, ${aspect}`);
