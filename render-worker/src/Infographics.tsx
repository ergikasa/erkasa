import React from 'react';
import { Easing, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { ICONS } from './icons';
import { AspectRatio, Visual, Word, cueTimes } from './spec';
import { panelStyle, theme } from './theme';

/*
 * The INFOGRAPHIC template scenes (9.3): bar chart, timeline, cycle and icon grid.
 * Like the other graphics, every item appears when the voice says it (cueTimes), and the
 * colours come from the active theme. Nothing here is an AI image.
 */

type InfoVisual = Extract<Visual, { type: 'bars' | 'timeline' | 'cycle' | 'iconGrid' }>;
type Props = { visual: InfoVisual; words: Word[]; audioSeconds: number; aspectRatio: AspectRatio };

const INFO_TYPES = new Set(['bars', 'timeline', 'cycle', 'iconGrid']);

export function isInfographic(v: Visual): v is InfoVisual {
  return INFO_TYPES.has(v.type);
}

export const Infographic: React.FC<Props> = ({ visual, words, audioSeconds, aspectRatio }) => {
  switch (visual.type) {
    case 'bars':
      return <Bars {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'timeline':
      return <Timeline {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'cycle':
      return <Cycle {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
    case 'iconGrid':
      return <IconGrid {...visual} cues={cueTimes(visual.items, words, audioSeconds)} aspectRatio={aspectRatio} />;
  }
};

// ---------------------------------------------------------------- helpers

/** 0 -> 1 spring that starts at `atSeconds`. */
function useSpringAt(atSeconds: number, damping = 14): number {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - Math.round(atSeconds * fps), fps, config: { damping, stiffness: 140 } });
}

function springAt(frame: number, fps: number, atSeconds: number, damping = 14): number {
  return spring({ frame: frame - Math.round(atSeconds * fps), fps, config: { damping, stiffness: 140 } });
}

/** A Lucide icon from the catalog; an unknown name becomes a plain dot (never a broken render). */
export const Icon: React.FC<{ name: string; size: number; color: string; strokeWidth?: number }> = ({ name, size, color, strokeWidth = 2 }) => {
  const Svg = ICONS[name];
  if (!Svg) {
    return <div style={{ width: size * 0.5, height: size * 0.5, borderRadius: '50%', background: color, margin: size * 0.25 }} />;
  }
  return <Svg size={size} color={color} strokeWidth={strokeWidth} absoluteStrokeWidth={false} />;
};

const SceneTitle: React.FC<{ text?: string; portrait: boolean }> = ({ text, portrait }) => {
  const a = useSpringAt(0.05, 16);
  if (!text) return null;
  return (
    <div
      style={{
        fontFamily: theme.font,
        color: theme.text,
        fontWeight: 900,
        fontSize: portrait ? 64 : 58,
        letterSpacing: -1,
        textAlign: 'center',
        marginBottom: portrait ? 44 : 36,
        opacity: a,
        transform: `translateY(${(1 - a) * 24}px)`,
      }}
    >
      {text}
    </div>
  );
};

/** 1234.5 -> "1,234.5"; whole numbers stay whole. */
function formatNumber(n: number, decimals: number): string {
  return n.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

// ---------------------------------------------------------------- bar chart

const Bars: React.FC<{
  title?: string;
  items: string[];
  values: number[];
  prefix?: string;
  suffix?: string;
  cues: number[];
  aspectRatio: AspectRatio;
}> = ({ title, items, values, prefix = '', suffix = '', cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const portrait = aspectRatio === 'PORTRAIT';
  const columns = aspectRatio === 'LANDSCAPE'; // wide screens: columns that grow up; tall: bars that grow right
  const max = Math.max(1, ...values);
  const decimals = values.some((v) => !Number.isInteger(v)) ? 1 : 0;

  const grow = (i: number) =>
    interpolate(frame, [Math.round(cues[i] * fps), Math.round((cues[i] + 0.9) * fps)], [0, 1], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
      easing: Easing.out(Easing.cubic),
    });

  if (columns) {
    const barW = Math.min(220, 1200 / items.length);
    const chartH = title ? 470 : 560;
    return (
      <div style={{ width: '100%', fontFamily: theme.font }}>
        <SceneTitle text={title} portrait={false} />
        <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'flex-end', gap: barW * 0.35, height: chartH + 120 }}>
          {items.map((label, i) => {
            const g = grow(i);
            const color = theme.palette[i % theme.palette.length];
            const h = Math.max(8, (values[i] / max) * chartH * g);
            const shown = springAt(frame, fps, cues[i], 16);
            return (
              <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: barW, opacity: shown }}>
                <div style={{ color: theme.text, fontWeight: 900, fontSize: 46, marginBottom: 14 }}>
                  {prefix}
                  {formatNumber(values[i] * g, decimals)}
                  {suffix}
                </div>
                <div
                  style={{
                    width: '100%',
                    height: h,
                    borderRadius: '18px 18px 6px 6px',
                    background: `linear-gradient(180deg, ${color}, ${color}AA)`,
                    boxShadow: `0 0 40px ${color}55`,
                  }}
                />
                <div style={{ marginTop: 18, color: theme.muted, fontWeight: 800, fontSize: 38, textAlign: 'center', lineHeight: 1.1 }}>
                  {label}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  const labelW = portrait ? 260 : 240;
  return (
    <div style={{ width: '100%', fontFamily: theme.font }}>
      <SceneTitle text={title} portrait={portrait} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: portrait ? 48 : 30 }}>
        {items.map((label, i) => {
          const g = grow(i);
          const color = theme.palette[i % theme.palette.length];
          const shown = springAt(frame, fps, cues[i], 16);
          return (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 24, opacity: shown, transform: `translateX(${(1 - shown) * -60}px)` }}>
              <div style={{ width: labelW, flex: `0 0 ${labelW}px`, color: theme.text, fontWeight: 800, fontSize: portrait ? 52 : 44, lineHeight: 1.1 }}>
                {label}
              </div>
              <div style={{ flex: 1, display: 'flex', alignItems: 'center', gap: 18 }}>
                <div
                  style={{
                    height: portrait ? 100 : 72,
                    width: `${Math.max(3, (values[i] / max) * 78 * g)}%`,
                    borderRadius: 14,
                    background: `linear-gradient(90deg, ${color}AA, ${color})`,
                    boxShadow: `0 0 34px ${color}55`,
                  }}
                />
                <div style={{ color: theme.text, fontWeight: 900, fontSize: portrait ? 54 : 46, whiteSpace: 'nowrap' }}>
                  {prefix}
                  {formatNumber(values[i] * g, decimals)}
                  {suffix}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

// ---------------------------------------------------------------- timeline

const Timeline: React.FC<{ items: string[]; labels: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({
  items,
  labels,
  cues,
  aspectRatio,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const n = items.length;
  // how far the line is drawn: 0 = first dot, 1 = last dot; it reaches each dot when the voice says it
  const reached = cues.reduce((acc, c, i) => (t >= c ? i : acc), 0);
  const nextCue = cues[reached + 1];
  const toward = nextCue == null ? 0 : interpolate(t, [nextCue - 0.45, nextCue], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const line = n <= 1 ? 1 : Math.min(1, (reached + toward) / (n - 1));
  const start = useSpringAt(Math.max(0, cues[0] - 0.2), 18);

  if (aspectRatio === 'LANDSCAPE') {
    const step = 1500 / Math.max(1, n - 1 || 1);
    const width = n <= 1 ? 0 : 1500;
    return (
      <div style={{ position: 'relative', width: 1640, height: 560, fontFamily: theme.font }}>
        {/* the track and the drawn part */}
        <div style={{ position: 'absolute', left: 70, top: 270, width, height: 10, borderRadius: 5, background: theme.panelBorder, opacity: start }} />
        <div
          style={{
            position: 'absolute',
            left: 70,
            top: 270,
            width: width * line,
            height: 10,
            borderRadius: 5,
            background: `linear-gradient(90deg, ${theme.accent}, ${theme.accent2})`,
            boxShadow: `0 0 24px ${theme.accent}`,
          }}
        />
        {items.map((event, i) => {
          const a = springAt(frame, fps, cues[i], 13);
          const color = theme.palette[i % theme.palette.length];
          const x = 70 + (n <= 1 ? 750 : i * step);
          const up = i % 2 === 0; // alternate above / below so long texts never collide
          return (
            <React.Fragment key={i}>
              <div
                style={{
                  position: 'absolute',
                  left: x - 26,
                  top: 249,
                  width: 52,
                  height: 52,
                  borderRadius: 26,
                  background: theme.bg,
                  border: `8px solid ${color}`,
                  transform: `scale(${a})`,
                  boxShadow: `0 0 30px ${color}88`,
                }}
              />
              <div
                style={{
                  position: 'absolute',
                  left: x - 170,
                  width: 340,
                  top: up ? 40 : 330,
                  textAlign: 'center',
                  opacity: a,
                  transform: `translateY(${(1 - a) * (up ? -30 : 30)}px)`,
                }}
              >
                {up ? null : <DateLabel text={labels[i]} color={color} size={50} />}
                <div style={{ color: theme.text, fontWeight: 750, fontSize: 44, lineHeight: 1.15, margin: '10px 0' }}>{event}</div>
                {up ? <DateLabel text={labels[i]} color={color} size={50} /> : null}
              </div>
            </React.Fragment>
          );
        })}
      </div>
    );
  }

  // portrait / square: a vertical line on the left, one row per event
  const portrait = aspectRatio === 'PORTRAIT';
  const rowH = portrait ? Math.min(200, 1000 / n) : Math.min(150, 760 / n);
  const height = rowH * n;
  return (
    <div style={{ position: 'relative', width: '100%', height, fontFamily: theme.font }}>
      <div style={{ position: 'absolute', left: 34, top: rowH / 2, width: 10, height: height - rowH, borderRadius: 5, background: theme.panelBorder, opacity: start }} />
      <div
        style={{
          position: 'absolute',
          left: 34,
          top: rowH / 2,
          width: 10,
          height: (height - rowH) * line,
          borderRadius: 5,
          background: `linear-gradient(180deg, ${theme.accent}, ${theme.accent2})`,
          boxShadow: `0 0 24px ${theme.accent}`,
        }}
      />
      {items.map((event, i) => {
        const a = springAt(frame, fps, cues[i], 13);
        const color = theme.palette[i % theme.palette.length];
        return (
          <div key={i} style={{ position: 'absolute', left: 0, right: 0, top: i * rowH, height: rowH, display: 'flex', alignItems: 'center' }}>
            <div
              style={{
                width: 78,
                height: 78,
                flex: '0 0 78px',
                marginLeft: 0,
                borderRadius: 39,
                background: theme.bg,
                border: `10px solid ${color}`,
                transform: `scale(${a})`,
                boxShadow: `0 0 30px ${color}88`,
              }}
            />
            <div style={{ marginLeft: 34, opacity: a, transform: `translateX(${(1 - a) * 50}px)` }}>
              <DateLabel text={labels[i]} color={color} size={portrait ? 46 : 40} />
              <div style={{ color: theme.text, fontWeight: 750, fontSize: portrait ? 50 : 42, lineHeight: 1.15, marginTop: 4 }}>{event}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
};

const DateLabel: React.FC<{ text: string; color: string; size: number }> = ({ text, color, size }) =>
  text ? <div style={{ color, fontWeight: 900, fontSize: size, letterSpacing: -0.5 }}>{text}</div> : null;

// ---------------------------------------------------------------- cycle

const Cycle: React.FC<{ title?: string; items: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({ title, items, cues, aspectRatio }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const n = Math.max(1, items.length);
  const landscape = aspectRatio === 'LANDSCAPE';
  const R = landscape ? 290 : 330; // ring radius
  const pillW = landscape ? 280 : 260;
  const width = R * 2 + pillW + 20;
  const height = R * 2 + 130;
  const cx = width / 2;
  const cy = height / 2;
  const center = useSpringAt(0.1, 14);
  // the ring fills up to the item being spoken, then keeps turning slowly once all are there
  const reached = cues.reduce((acc, cue, i) => (t >= cue ? i + 1 : acc), 0);
  const nextCue = cues[reached];
  const toward = nextCue == null ? 0 : interpolate(t, [nextCue - 0.4, nextCue], [0, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' });
  const circumference = 2 * Math.PI * R;
  const done = reached >= n;
  // node i sits at i/n of the ring; after the last one the ring closes back to the first
  const fill = done
    ? interpolate(t, [cues[n - 1], cues[n - 1] + 0.8], [(n - 1) / n, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' })
    : reached === 0
      ? 0
      : (reached - 1 + toward) / n;
  const spin = done ? (t - cues[n - 1]) * 18 : 0; // degrees: the arrow head keeps going round

  const angle = (i: number) => -Math.PI / 2 + (i / n) * 2 * Math.PI;

  return (
    <div style={{ position: 'relative', width, height, fontFamily: theme.font }}>
      <svg width={width} height={height} style={{ position: 'absolute', left: 0, top: 0 }}>
        <circle cx={cx} cy={cy} r={R} fill="none" stroke={theme.panelBorder} strokeWidth={12} />
        <circle
          cx={cx}
          cy={cy}
          r={R}
          fill="none"
          stroke={theme.accent}
          strokeWidth={12}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - Math.max(0, fill))}
          transform={`rotate(-90 ${cx} ${cy})`}
          style={{ filter: `drop-shadow(0 0 12px ${theme.accent})` }}
        />
        {/* a small arrow head that travels around once the loop is complete */}
        {done ? (
          <g transform={`rotate(${spin} ${cx} ${cy})`}>
            <polygon points={`${cx - 18},${cy - R - 22} ${cx + 22},${cy - R} ${cx - 18},${cy - R + 22}`} fill={theme.accent2} />
          </g>
        ) : null}
      </svg>

      {/* the word in the middle */}
      <div
        style={{
          position: 'absolute',
          // small enough that the stage cards on the ring never cover it
          left: cx - R * 0.42,
          top: cy - R * 0.42,
          width: R * 0.84,
          height: R * 0.84,
          borderRadius: '50%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          textAlign: 'center',
          padding: 24,
          boxSizing: 'border-box',
          background: theme.panel,
          border: `3px solid ${theme.panelBorder}`,
          boxShadow: theme.shadow,
          color: theme.text,
          fontWeight: 900,
          fontSize: (title ?? '').length > 10 ? 38 : 50,
          lineHeight: 1.05,
          opacity: center,
          transform: `scale(${0.7 + 0.3 * center})`,
        }}
      >
        {title ?? ''}
      </div>

      {items.map((stage, i) => {
        const a = springAt(frame, fps, cues[i], 12);
        const color = theme.palette[i % theme.palette.length];
        const x = cx + R * Math.cos(angle(i));
        const y = cy + R * Math.sin(angle(i));
        const w = pillW;
        return (
          <div
            key={i}
            style={{
              ...panelStyle(),
              position: 'absolute',
              left: x - w / 2,
              top: y - 52,
              width: w,
              minHeight: 104,
              boxSizing: 'border-box',
              padding: '14px 18px',
              display: 'flex',
              alignItems: 'center',
              gap: 14,
              borderColor: color,
              opacity: a,
              transform: `scale(${0.5 + 0.5 * a})`,
            }}
          >
            <div
              style={{
                width: 52,
                height: 52,
                flex: '0 0 52px',
                borderRadius: 26,
                background: color,
                color: theme.onAccent,
                fontWeight: 900,
                fontSize: 28,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              {i + 1}
            </div>
            <div style={{ color: theme.text, fontWeight: 800, fontSize: landscape ? 38 : 36, lineHeight: 1.1 }}>{stage}</div>
          </div>
        );
      })}
    </div>
  );
};

// ---------------------------------------------------------------- icon grid

const IconGrid: React.FC<{ items: string[]; icons: string[]; cues: number[]; aspectRatio: AspectRatio }> = ({
  items,
  icons,
  cues,
  aspectRatio,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const n = items.length;
  const landscape = aspectRatio === 'LANDSCAPE';
  const portrait = aspectRatio === 'PORTRAIT';
  const cols = landscape ? (n <= 4 ? n : 3) : n <= 4 ? 2 : portrait ? 2 : 3;
  const iconSize = landscape ? 120 : portrait ? 110 : 90;

  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${cols}, 1fr)`,
        gap: portrait ? 34 : 30,
        width: '100%',
        fontFamily: theme.font,
      }}
    >
      {items.map((item, i) => {
        const a = springAt(frame, fps, cues[i], 12);
        const color = theme.palette[i % theme.palette.length];
        // an odd last card in a 2-column grid sits in the middle
        const lonely = cols === 2 && n % 2 === 1 && i === n - 1;
        return (
          <div
            key={i}
            style={{
              ...panelStyle(),
              gridColumn: lonely ? '1 / span 2' : undefined,
              justifySelf: lonely ? 'center' : undefined,
              width: lonely ? '50%' : undefined,
              boxSizing: 'border-box',
              padding: portrait ? '34px 22px' : landscape ? '44px 24px' : '28px 20px',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              textAlign: 'center',
              gap: 20,
              borderTop: `8px solid ${color}`,
              opacity: a,
              transform: `translateY(${(1 - a) * 50}px) scale(${0.85 + 0.15 * a})`,
            }}
          >
            <div
              style={{
                width: iconSize * 1.55,
                height: iconSize * 1.55,
                borderRadius: '50%',
                background: `${color}22`,
                border: `3px solid ${color}66`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Icon name={icons[i]} size={iconSize} color={color} strokeWidth={2} />
            </div>
            <div style={{ color: theme.text, fontWeight: 800, fontSize: portrait ? 46 : landscape ? 48 : 40, lineHeight: 1.15 }}>{item}</div>
          </div>
        );
      })}
    </div>
  );
};
