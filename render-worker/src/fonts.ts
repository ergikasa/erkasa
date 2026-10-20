/**
 * Fonts shipped inside the bundle (npm @fontsource), so every video looks the same on any
 * machine and server, with Albanian (ë, ç) and Cyrillic (MK, SR, RU) letters.
 *
 * The render waits until they are loaded: a frame is never drawn with a fallback font.
 */
import { continueRender, delayRender } from 'remotion';

import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import '@fontsource/inter/900.css';
import '@fontsource/jetbrains-mono/400.css';
import '@fontsource/jetbrains-mono/700.css';

const SAMPLE = 'AaËëÇçЖжЉљ0123';

const waiting = delayRender('Loading fonts');
Promise.all(
  ['600', '700', '800', '900'].map((w) => document.fonts.load(`${w} 40px Inter`, SAMPLE))
    .concat([document.fonts.load('400 40px "JetBrains Mono"', SAMPLE), document.fonts.load('700 40px "JetBrains Mono"', SAMPLE)]),
)
  .then(() => continueRender(waiting))
  .catch((e) => {
    // A missing font must not block the render forever: continue with the fallback.
    console.warn('Font loading failed', e);
    continueRender(waiting);
  });
