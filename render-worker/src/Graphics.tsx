import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig, Easing } from 'remotion';
import { AspectRatio, Visual, Word, cueTimes } from './spec';
import { theme } from './theme';

type Props = { visual: Exclude<Visual, { type: 'image' }>; words: Word[]; audioSeconds: number; aspectRatio: AspectRatio };

/** Motion graphics for a scene that explains instead of showing a picture. */
export const Graphic: React.FC<Props> = (props) => {
  const portrait = props.aspectRatio === 'PORTRAIT';
  return (
    <AbsoluteFill>
      <Backdrop />
      {/* the stage leaves room at the bottom for the captions */}
      <AbsoluteFill
        style={{
          top: portrait ? 210 : 70,
          bottom: portrait ? 640 : 230,
          left: portrait ? 70 : 140,
          right: portrait ? 70 : 140,
          // AbsoluteFill sets width/height 100%; let top/bottom/left/right decide the size instead
          width: 'auto',
          height: 'auto',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <Body {...props} />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const Body: React.FC<Props> = ({ visual, words, audioSeconds, aspectRatio }) => {
  switch (visual.type) {
    case 'title':
      return <Title title={visual.title} subtitle={visual.subtitle} aspectRatio={aspectRatio} />;
    case 'flow':
      return <Flow steps={visual.steps} cues={cueTimes(visual.steps, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'list':
      return <List items={visual.items} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'stat':
      return <Stat {...visual} aspectRatio={aspectRatio} />;
    case 'compare':
      return <Compare {...visual} words={words} audioSeconds={audioSeconds} aspectRatio={aspectRatio} />;
    case 'code':
      return <Code code={visual.code} language={visual.language} audioSeconds={audioSeconds} aspectRatio={aspectRatio} />;
    case 'cta':
      return <Cta text={visual.text} handle={visual.handle} aspectRatio={aspectRatio} />;
    default:
      return null;
  }
};

// ---------------------------------------------------------------- helpers

function useAppear(atSeconds: number, damping = 14) {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - Math.round(atSeconds * fps), fps, config: { damping, stiffness: 140 } });
}

const panelStyle: React.CSSProperties = {
  background: theme.panel,
  border: `2px solid ${theme.panelBorder}`,
  borderRadius: 28,
  boxShadow: '0 20px 60px rgba(0,0,0,0.45)',
};

/**
 * Slowly moving glows + grid, so graphic scenes never look like a static slide.
 *
 * Speed (5.5): the gradients are drawn once and only MOVED with transform each frame. Changing
 * the gradient itself every frame made Chrome repaint the whole screen (2x slower renders and
 * bigger files).
 */
const Backdrop: React.FC = () => {
  const frame = useCurrentFrame();
  const { width, height } = useVideoConfig();
  const t = ((frame * 0.6) % 360) * (Math.PI / 180);
  const glow = (color: string, size: number): React.CSSProperties => ({
    position: 'absolute',
    width: size,
    height: size,
    left: -size / 2,
    top: -size / 2,
    borderRadius: '50%',
    background: `radial-gradient(circle, ${color}, transparent 70%)`,
    willChange: 'transform',
  });
  const big = Math.max(width, height) * 0.9;
  return (
    <AbsoluteFill style={{ background: `linear-gradient(160deg, ${theme.bg} 0%, ${theme.bg2} 100%)`, overflow: 'hidden' }}>
      <div
        style={{
          ...glow('rgba(34,211,238,0.18)', big),
          transform: `translate(${width * (0.5 + 0.3 * Math.cos(t))}px, ${height * (0.3 + 0.2 * Math.sin(t))}px)`,
        }}
      />
      <div
        style={{
          ...glow('rgba(167,139,250,0.18)', big * 1.1),
          transform: `translate(${width * (0.5 - 0.3 * Math.cos(t))}px, ${height * (0.75 - 0.15 * Math.sin(t))}px)`,
        }}
      />
      <div
        style={{
          position: 'absolute',
          left: 0,
          top: -60,
          width,
          height: height + 60,
          backgroundImage:
            'linear-gradient(rgba(148,163,184,0.07) 1px, transparent 1px), linear-gradient(90deg, rgba(148,163,184,0.07) 1px, transparent 1px)',
          backgroundSize: '60px 60px',
          transform: `translateY(${(frame * 0.5) % 60}px)`,
          willChange: 'transform',
        }}
      />
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------- title

const Title: React.FC<{ title: string; subtitle?: string; aspectRatio: AspectRatio }> = ({ title, subtitle, aspectRatio }) => {
  const enter = useAppear(0.1, 12);
  const line = useAppear(0.45, 20);
  const sub = useAppear(0.8, 18);
  const portrait = aspectRatio === 'PORTRAIT';
  return (
    <div style={{ textAlign: 'center', fontFamily: theme.font }}>
      <div
        style={{
          fontSize: portrait ? 132 : 120,
          fontWeight: 900,
          lineHeight: 1.0,
          letterSpacing: -3,
          color: theme.text,
          transform: `translateY(${(1 - enter) * 60}px) scale(${0.9 + 0.1 * enter})`,
          opacity: enter,
        }}
      >
        {title}
      </div>
      <div
        style={{
          margin: '34px auto 0',
          height: 12,
          width: `${line * 60}%`,
          borderRadius: 6,
          background: `linear-gradient(90deg, ${theme.accent}, ${theme.accent2})`,
          boxShadow: `0 0 30px ${theme.accent}`,
        }}
      />
      {subtitle ? (
        <div style={{ marginTop: 40, fontSize: portrait ? 52 : 46, color: theme.muted, opacity: sub, fontWeight: 600 }}>
          {subtitle}
        </div>
      ) : null}
    </div>
  );
};

// ---------------------------------------------------------------- flow

const Flow: React.FC<{ steps: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ steps, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const vertical = aspectRatio !== 'LANDSCAPE';
  const n = steps.length;
  const current = cues.reduce((acc, c, i) => (t >= c ? i : acc), -1);
  const boxSize = vertical ? Math.min(150, 760 / n) : Math.min(260, 1500 / n);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: vertical ? 'column' : 'row',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: theme.font,
      }}
    >
      {steps.map((step, i) => {
        const appear = spring({ frame: frame - Math.round(cues[i] * fps), fps, config: { damping: 13, stiffness: 150 } });
        const color = theme.palette[i % theme.palette.length];
        const active = i === current;
        const arrowGrow = i < n - 1
          ? interpolate(t, [cues[i + 1] - 0.35, cues[i + 1]], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' })
          : 0;
        return (
          <React.Fragment key={i}>
            <div
              style={{
                ...panelStyle,
                borderColor: active ? color : theme.panelBorder,
                boxShadow: active ? `0 0 50px ${color}66, 0 20px 60px rgba(0,0,0,0.45)` : panelStyle.boxShadow,
                minWidth: vertical ? 640 : boxSize,
                maxWidth: vertical ? 860 : boxSize + 60,
                minHeight: vertical ? boxSize * 0.62 : boxSize * 0.8,
                padding: vertical ? '18px 34px' : '22px 18px',
                display: 'flex',
                alignItems: 'center',
                gap: 24,
                justifyContent: vertical ? 'flex-start' : 'center',
                flexDirection: vertical ? 'row' : 'column',
                opacity: appear,
                transform: `scale(${0.6 + 0.4 * appear}) translateY(${(1 - appear) * 30}px)`,
              }}
            >
              <div
                style={{
                  width: 64,
                  height: 64,
                  flex: '0 0 64px',
                  borderRadius: 32,
                  background: color,
                  color: theme.bg,
                  fontWeight: 900,
                  fontSize: 34,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {i + 1}
              </div>
              <div
                style={{
                  color: theme.text,
                  fontWeight: 800,
                  fontSize: vertical ? 54 : 42,
                  lineHeight: 1.1,
                  textAlign: vertical ? 'left' : 'center',
                }}
              >
                {step}
              </div>
            </div>
            {i < n - 1 ? <Arrow vertical={vertical} grow={arrowGrow} color={theme.palette[(i + 1) % theme.palette.length]} /> : null}
          </React.Fragment>
        );
      })}
    </div>
  );
};

const Arrow: React.FC<{ vertical: boolean; grow: number; color: string }> = ({ vertical, grow, color }) => {
  const len = vertical ? 54 : 70;
  return (
    <div
      style={{
        width: vertical ? 12 : len,
        height: vertical ? len : 12,
        margin: vertical ? '8px 0' : '0 10px',
        display: 'flex',
        flexDirection: vertical ? 'column' : 'row',
        alignItems: 'center',
      }}
    >
      <div
        style={{
          background: color,
          borderRadius: 6,
          width: vertical ? 8 : `${grow * 100}%`,
          height: vertical ? `${grow * 100}%` : 8,
          boxShadow: `0 0 18px ${color}`,
        }}
      />
      <div
        style={{
          width: 0,
          height: 0,
          opacity: grow > 0.9 ? 1 : 0,
          borderLeft: vertical ? '14px solid transparent' : `18px solid ${color}`,
          borderRight: vertical ? '14px solid transparent' : 'none',
          borderTop: vertical ? `18px solid ${color}` : '14px solid transparent',
          borderBottom: vertical ? 'none' : '14px solid transparent',
        }}
      />
    </div>
  );
};

// ---------------------------------------------------------------- list

const List: React.FC<{ items: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ items, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const portrait = aspectRatio === 'PORTRAIT';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: portrait ? 30 : 24, width: '100%', fontFamily: theme.font }}>
      {items.map((item, i) => {
        const appear = spring({ frame: frame - Math.round(cues[i] * fps), fps, config: { damping: 14, stiffness: 150 } });
        const color = theme.palette[i % theme.palette.length];
        return (
          <div
            key={i}
            style={{
              ...panelStyle,
              display: 'flex',
              alignItems: 'center',
              gap: 28,
              padding: portrait ? '26px 36px' : '20px 32px',
              opacity: appear,
              transform: `translateX(${(1 - appear) * -120}px)`,
              borderLeft: `10px solid ${color}`,
            }}
          >
            <div
              style={{
                width: 60,
                height: 60,
                flex: '0 0 60px',
                borderRadius: 30,
                border: `4px solid ${color}`,
                color,
                fontSize: 36,
                fontWeight: 900,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              ✓
            </div>
            <div style={{ color: theme.text, fontSize: portrait ? 52 : 46, fontWeight: 750, lineHeight: 1.15 }}>{item}</div>
          </div>
        );
      })}
    </div>
  );
};

// ---------------------------------------------------------------- stat

const Stat: React.FC<{ value: number; prefix?: string; suffix?: string; label: string; aspectRatio: AspectRatio }> = ({
  value,
  prefix,
  suffix,
  label,
  aspectRatio,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const count = interpolate(frame, [0.2 * fps, 1.6 * fps], [0, value], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.out(Easing.cubic),
  });
  const decimals = Number.isInteger(value) ? 0 : 1;
  const done = frame > 1.6 * fps;
  const pulse = done ? 1 + 0.03 * Math.sin((frame - 1.6 * fps) / 5) : 1;
  const labelIn = useAppear(1.0, 16);
  const portrait = aspectRatio === 'PORTRAIT';
  return (
    <div style={{ textAlign: 'center', fontFamily: theme.font }}>
      <div
        style={{
          fontSize: portrait ? 240 : 220,
          fontWeight: 900,
          letterSpacing: -6,
          lineHeight: 1,
          backgroundImage: `linear-gradient(90deg, ${theme.accent}, ${theme.accent2})`,
          WebkitBackgroundClip: 'text',
          color: 'transparent',
          transform: `scale(${pulse})`,
          filter: `drop-shadow(0 0 40px ${theme.accent}55)`,
        }}
      >
        {prefix ?? ''}
        {count.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}
        {suffix ?? ''}
      </div>
      <div
        style={{
          marginTop: 30,
          color: theme.text,
          fontSize: portrait ? 56 : 50,
          fontWeight: 700,
          opacity: labelIn,
          transform: `translateY(${(1 - labelIn) * 30}px)`,
        }}
      >
        {label}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------- compare

const Compare: React.FC<{
  leftTitle: string;
  rightTitle: string;
  left: string[];
  right: string[];
  words: Word[];
  audioSeconds: number;
  aspectRatio: AspectRatio;
}> = ({ leftTitle, rightTitle, left, right, words, audioSeconds, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const portrait = aspectRatio === 'PORTRAIT';
  // left side during the first half of the voice, right side during the second
  const leftCues = cueTimes([leftTitle, ...left], words, audioSeconds * 0.5);
  const rightCues = cueTimes([rightTitle, ...right], words.filter((w) => w.startSeconds >= audioSeconds * 0.4), audioSeconds)
    .map((c) => Math.max(c, audioSeconds * 0.45));

  const column = (title: string, rows: string[], cues: number[], color: string, mark: string) => {
    const head = spring({ frame: frame - Math.round(cues[0] * fps), fps, config: { damping: 14 } });
    return (
      <div style={{ ...panelStyle, flex: 1, padding: portrait ? 30 : 34, borderTop: `10px solid ${color}`, opacity: head, transform: `translateY(${(1 - head) * 40}px)` }}>
        <div style={{ color, fontWeight: 900, fontSize: portrait ? 50 : 52, marginBottom: 22 }}>{title}</div>
        {rows.map((row, i) => {
          const a = spring({ frame: frame - Math.round(cues[i + 1] * fps), fps, config: { damping: 15 } });
          return (
            <div key={i} style={{ display: 'flex', gap: 16, alignItems: 'flex-start', marginTop: 16, opacity: a, transform: `translateX(${(1 - a) * 40}px)` }}>
              <span style={{ color, fontWeight: 900, fontSize: portrait ? 44 : 42 }}>{mark}</span>
              <span style={{ color: theme.text, fontWeight: 650, fontSize: portrait ? 42 : 40, lineHeight: 1.2 }}>{row}</span>
            </div>
          );
        })}
      </div>
    );
  };

  return (
    <div style={{ display: 'flex', flexDirection: portrait ? 'column' : 'row', gap: 36, width: '100%', fontFamily: theme.font }}>
      {column(leftTitle, left, leftCues, theme.bad, '✗')}
      {column(rightTitle, right, rightCues, theme.good, '✓')}
    </div>
  );
};

// ---------------------------------------------------------------- code

const KEYWORDS = /\b(const|let|var|function|return|if|else|for|while|import|from|export|class|public|private|new|await|async|def|true|false|null)\b/g;

/** Very small highlighter: comments, strings, YAML keys, keywords. Good enough for short snippets. */
function highlight(line: string): React.ReactNode[] {
  const comment = line.match(/^(\s*)(#|\/\/)(.*)$/);
  if (comment) {
    return [<span key="c" style={{ color: '#64748B', fontStyle: 'italic' }}>{line}</span>];
  }
  const parts: React.ReactNode[] = [];
  const yamlKey = line.match(/^(\s*-?\s*)([\w.-]+)(:)(.*)$/);
  let rest = line;
  if (yamlKey) {
    parts.push(<span key="i">{yamlKey[1]}</span>, <span key="k" style={{ color: theme.accent }}>{yamlKey[2]}</span>, <span key="p">{yamlKey[3]}</span>);
    rest = yamlKey[4];
  }
  const tokens = rest.split(/("[^"]*"|'[^']*')/);
  tokens.forEach((tok, i) => {
    if (/^["'].*["']$/.test(tok)) {
      parts.push(<span key={`s${i}`} style={{ color: theme.good }}>{tok}</span>);
    } else {
      const pieces = tok.split(KEYWORDS);
      pieces.forEach((p, j) => {
        const isKw = j % 2 === 1;
        parts.push(<span key={`t${i}-${j}`} style={isKw ? { color: theme.accent2, fontWeight: 700 } : undefined}>{p}</span>);
      });
    }
  });
  return parts;
}

const Code: React.FC<{ code: string; language?: string; audioSeconds: number; aspectRatio: AspectRatio }> = ({
  code,
  language,
  audioSeconds,
  aspectRatio,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const lines = code.replace(/\t/g, '  ').split('\n').slice(0, 14);
  const total = lines.reduce((s, l) => s + l.length + 1, 0);
  // type everything during the first 70% of the voice
  const typed = Math.floor(
    interpolate(frame, [0.3 * fps, Math.max(0.6, audioSeconds * 0.7) * fps], [0, total], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' }),
  );
  const win = useAppear(0, 16);
  const portrait = aspectRatio === 'PORTRAIT';
  let left = typed;
  const cursorOn = Math.floor(frame / 15) % 2 === 0;
  return (
    <div style={{ ...panelStyle, width: '100%', padding: 0, overflow: 'hidden', opacity: win, transform: `scale(${0.92 + 0.08 * win})` }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '18px 26px', background: 'rgba(255,255,255,0.05)' }}>
        {['#F87171', '#FBBF24', '#34D399'].map((c) => (
          <div key={c} style={{ width: 20, height: 20, borderRadius: 10, background: c }} />
        ))}
        <div style={{ marginLeft: 16, color: theme.muted, fontFamily: theme.mono, fontSize: 28 }}>{language ?? 'code'}</div>
      </div>
      <div style={{ padding: portrait ? '26px 32px' : '26px 40px', fontFamily: theme.mono, fontSize: portrait ? 34 : 34, lineHeight: 1.45, color: theme.text, whiteSpace: 'pre' }}>
        {lines.map((line, i) => {
          const visible = line.slice(0, Math.max(0, left));
          const isTyping = left > 0 && left <= line.length;
          left -= line.length + 1;
          return (
            <div key={i} style={{ minHeight: '1.45em' }}>
              <span style={{ color: '#475569', marginRight: 24 }}>{String(i + 1).padStart(2, ' ')}</span>
              {highlight(visible)}
              {isTyping && cursorOn ? <span style={{ background: theme.accent, color: theme.bg }}> </span> : null}
            </div>
          );
        })}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------- call to action

const Cta: React.FC<{ text: string; handle?: string; aspectRatio: AspectRatio }> = ({ text, handle, aspectRatio }) => {
  const frame = useCurrentFrame();
  const enter = useAppear(0.1, 11);
  const btn = useAppear(0.6, 10);
  const pulse = 1 + 0.04 * Math.sin(frame / 6);
  const portrait = aspectRatio === 'PORTRAIT';
  return (
    <div style={{ textAlign: 'center', fontFamily: theme.font }}>
      <div style={{ fontSize: portrait ? 96 : 88, fontWeight: 900, color: theme.text, lineHeight: 1.05, opacity: enter, transform: `scale(${0.8 + 0.2 * enter})` }}>
        {text}
      </div>
      {handle ? (
        <div
          style={{
            display: 'inline-block',
            marginTop: 50,
            padding: '24px 56px',
            borderRadius: 999,
            background: `linear-gradient(90deg, ${theme.accent}, ${theme.accent2})`,
            color: theme.bg,
            fontSize: portrait ? 54 : 48,
            fontWeight: 900,
            transform: `scale(${btn * pulse})`,
            boxShadow: `0 0 60px ${theme.accent}88`,
          }}
        >
          {handle}
        </div>
      ) : null}
    </div>
  );
};
