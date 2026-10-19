import React from 'react';
import { AbsoluteFill, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { AspectRatio, Word } from './spec';

/** At most this many words on screen at once; a chunk also ends after . ! ? , : ; */
const WORDS_PER_CHUNK = 3;

type Chunk = { words: Word[]; start: number; end: number };

/** Groups word timings into short lines like "THIS IS ERKASA" -> "YOUR IDEAS," -> ... */
export function chunk(words: Word[]): Chunk[] {
  const chunks: Chunk[] = [];
  let current: Word[] = [];
  const flush = () => {
    if (current.length) {
      chunks.push({ words: current, start: current[0].startSeconds, end: current[current.length - 1].endSeconds });
      current = [];
    }
  };
  for (const w of words) {
    current.push(w);
    if (current.length >= WORDS_PER_CHUNK || /[.!?,:;]$/.test(w.word)) {
      flush();
    }
  }
  flush();
  return chunks;
}

/**
 * Word-by-word subtitles: the current line stays on screen, the word being spoken right now
 * is yellow and pops a little. Works for Latin and Cyrillic (system fonts).
 */
export const Captions: React.FC<{ words: Word[]; aspectRatio: AspectRatio }> = ({ words, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const chunks = React.useMemo(() => chunk(words ?? []), [words]);

  // the line being spoken, or the previous one during short pauses (no flicker between lines)
  let active: Chunk | undefined;
  for (const c of chunks) {
    if (c.start <= t + 0.05) {
      active = c;
    }
  }
  if (!active || t > active.end + 0.6) {
    return null;
  }

  const portrait = aspectRatio === 'PORTRAIT';
  const lineStartFrame = Math.round(active.start * fps);
  const pop = spring({ frame: frame - lineStartFrame, fps, config: { damping: 12, stiffness: 180 } });

  return (
    <AbsoluteFill
      style={{
        justifyContent: 'flex-end',
        alignItems: 'center',
        paddingBottom: portrait ? 420 : 120,
      }}
    >
      <div
        style={{
          maxWidth: '88%',
          textAlign: 'center',
          fontFamily: 'Inter, "Segoe UI", Arial, sans-serif',
          fontWeight: 900,
          fontSize: portrait ? 78 : 64,
          lineHeight: 1.15,
          textTransform: 'uppercase',
          transform: `scale(${0.85 + 0.15 * pop})`,
        }}
      >
        {active.words.map((w, i) => {
          const speaking = t >= w.startSeconds && t < w.endSeconds + 0.05;
          return (
            <React.Fragment key={i}>
            {i > 0 ? ' ' : null}
            <span
              style={{
                display: 'inline-block',
                margin: '0 0.2em',
                color: speaking ? '#FFE14D' : '#FFFFFF',
                WebkitTextStroke: '3px #000',
                paintOrder: 'stroke fill',
                textShadow: '0 4px 14px rgba(0,0,0,0.65)',
                transform: speaking ? 'scale(1.07)' : 'scale(1)',
              }}
            >
              {w.word}
            </span>
            </React.Fragment>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};