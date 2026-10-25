import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ScriptEditor, cleanScript } from '../components/ScriptEditor';
import type { Visual, VideoScript } from '../api/projects';

/* 9.3: the script review can edit the infographic scenes without breaking their pairs. */

const visual = (patch: Partial<Visual>): Visual => ({
  type: 'image', title: '', subtitle: '', items: [], value: 0, prefix: '', suffix: '', label: '',
  leftTitle: '', rightTitle: '', left: [], right: [], code: '', language: '', text: '', handle: '',
  values: [], labels: [], icons: [], pose: '',
  ...patch,
});

const script = (v: Visual): VideoScript => ({
  title: 'Online orders',
  musicMood: 'calm',
  scenes: [{ narration: 'Sales grew a lot.', onScreenText: '', imagePrompt: '', visual: v, sfx: [] }],
});

describe('infographic scenes in the script editor', () => {
  it('edits one bar label and its number', () => {
    const onChange = vi.fn();
    const bars = visual({ type: 'bars', title: 'Sales', items: ['2020', '2023'], values: [4.2, 5.8], suffix: 'T' });
    render(<ScriptEditor script={script(bars)} targetSeconds={30} onChange={onChange} />);

    const scene = screen.getByRole('article', { name: 'Scene 1' });
    expect(within(scene).getByText('Bar chart')).toBeInTheDocument();
    fireEvent.change(within(scene).getAllByLabelText('Number')[1], { target: { value: '6,1' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.values).toEqual([4.2, 6.1]);

    fireEvent.change(within(scene).getAllByLabelText('Label')[0], { target: { value: '2021' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.items).toEqual(['2021', '2023']);
  });

  it('shows the icon of each grid point and edits the text', () => {
    const onChange = vi.fn();
    const grid = visual({ type: 'iconGrid', items: ['Fast', 'Safe', 'Cheap'], icons: ['zap', 'shield-check', 'coins'] });
    render(<ScriptEditor script={script(grid)} targetSeconds={30} onChange={onChange} />);

    expect(screen.getByText('shield-check')).toBeInTheDocument();
    fireEvent.change(screen.getAllByLabelText('Point')[2], { target: { value: 'Cheaper' } });
    expect(onChange.mock.calls.at(-1)![0].scenes[0].visual.items).toEqual(['Fast', 'Safe', 'Cheaper']);
  });

  it('saving keeps paired positions (an emptied bar label is not dropped)', () => {
    const bars = visual({ type: 'bars', items: [' 2020 ', '', '2026'], values: [1, 2, 3] });
    expect(cleanScript(script(bars)).scenes[0].visual!.items).toEqual(['2020', '', '2026']);

    const list = visual({ type: 'list', items: [' One ', '', 'Two'] });
    expect(cleanScript(script(list)).scenes[0].visual!.items).toEqual(['One', 'Two']);
  });
});
