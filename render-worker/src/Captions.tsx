import React from 'react';
import { AbsoluteFill, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { AspectRatio, Word } from './spec';
import { theme } from './theme';

/** A caption line never has more than this many words or letters (it must stay one or two lines). */
const MAX_WORDS = 5;
const MAX_CHARS = 26;

type Chunk = { words: Word[]; start: number; end: number };

const SENTENCE_END = /[.!?…]["')»]*$/;
const PAUSE = /[,:;—–]["')»]*$/;
/** Words that belong to the next word: articles, prepositions, "your"... (EN, SQ and 1-2 letter words). */
const GLUE = new Set([
  'the', 'and', 'for', 'with', 'from', 'your', 'our', 'their', 'its', 'into', 'than', 'that', 'this', 'what', 'when',
  'një', 'dhe', 'për', 'nga', 'që', 'më', 'tek', 'sa', 'kur', 'çfarë',
]);
/** Never end a line on a glue word ("a", "the", "to", "në", "të"...). */
const isGlue = (w: string) => {
  if (PAUSE.test(w) || SENTENCE_END.test(w)) return false;
  const bare = w.replace(/[^\p{L}]/gu, '').toLowerCase();
  return bare.length <= 2 || GLUE.has(bare);
};

/**
 * Groups word timings into caption lines that read as phrases (9.4b):
 *  - a line ends at the end of a sentence, and at a comma / colon once it has 2+ words;
 *  - otherwise at MAX_WORDS / MAX_CHARS, but never right after a short word like "a" or "the";
 *  - a single word is never left alone on a line: it joins the line before or after it.
 */
export function chunk(words: Word[]): Chunk[] {
  const lines: Word[][] = [];
  let current: Word[] = [];
  const chars = (ws: Word[]) => ws.reduce((n, w) => n + w.word.length + 1, 0);
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    current.push(w);
    const next = words[i + 1];
    const full = current.length >= MAX_WORDS || chars(current) >= MAX_CHARS;
    const end =
      SENTENCE_END.test(w.word) ||
      (PAUSE.test(w.word) && (current.length >= 2 || lines.length === 0 || SENTENCE_END.test(lines[lines.length - 1].slice(-1)[0].word))) ||
      (full && !isGlue(w.word)) ||
      // a long pause in the voice also ends the line
      (next != null && next.startSeconds - w.endSeconds > 0.45 && current.length >= 2);
    if (end) {
      lines.push(current);
      current = [];
    }
  }
  if (current.length) lines.push(current);

  // no lonely words: join them to a neighbour that has room. An intro word that ends with a comma
  // ("First,", "So,") may stay alone: it reads as a beat, not as a broken phrase.
  for (let i = 0; i < lines.length; i++) {
    const w = lines[i][0];
    if (lines[i].length !== 1 || lines.length === 1 || PAUSE.test(w.word)) continue;
    const prev = lines[i - 1];
    const next = lines[i + 1];
    const prevOpen = prev && !SENTENCE_END.test(prev[prev.length - 1].word);
    // the last word of a sentence goes back to its sentence (one extra word is fine), never forward
    if (SENTENCE_END.test(w.word)) {
      if (prevOpen && prev.length <= MAX_WORDS) {
        prev.push(w);
        lines.splice(i--, 1);
      }
      continue;
    }
    if (prevOpen && prev.length < MAX_WORDS) {
      prev.push(w);
      lines.splice(i--, 1);
    } else if (next && next.length < MAX_WORDS) {
      next.unshift(w);
      lines.splice(i--, 1);
    } else if (prev && prev.length < MAX_WORDS) {
      prev.push(w);
      lines.splice(i--, 1);
    }
  }
  return lines.map((ws) => ({ words: ws, start: ws[0].startSeconds, end: ws[ws.length - 1].endSeconds }));
}

/**
 * Word-by-word subtitles: the current line stays on screen, the word being spoken right now
 * is yellow and pops a little. Works for Latin and Cyrillic (system fonts).
 */
export const Captions: React.FC<{ words: Word[]; aspectRatio: AspectRatio; onGraphic?: boolean }> = ({ words, aspectRatio, onGraphic }) => {
  // over graphics the colours follow the theme (dark text on LIGHT); over photos always white
  const colors = onGraphic
    ? { text: theme.captionText, stroke: theme.captionStroke, active: theme.captionActive }
    : { text: '#FFFFFF', stroke: '#000', active: '#FFE14D' };
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
                color: speaking ? colors.active : colors.text,
                WebkitTextStroke: `3px ${colors.stroke}`,
                paintOrder: 'stroke fill',
                textShadow: onGraphic && colors.stroke !== '#000000' ? 'none' : '0 4px 14px rgba(0,0,0,0.65)',
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