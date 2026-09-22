import { useState, type Dispatch, type SetStateAction } from 'react';
import { useDashboardStore } from '../store';

/**
 * Per-panel "follow the newest entry" state, with the toolbar's global control as
 * a master switch over all of it.
 *
 * WHY THIS IS NOT JUST useState. The live panels render in console order — oldest
 * first, newest appended at the bottom — and following the tail is an explicit
 * choice rather than something inferred from scroll position. Each panel owns that
 * choice, because a reader is usually studying one panel while the others stream.
 * But with four panels, "stop everything moving" would be four clicks, so the
 * toolbar keeps a single control that drives them all.
 *
 * The two live at different granularities, so neither can simply BE the other: the
 * global control sets every panel, and a panel may then diverge from it. Flipping
 * the global control re-syncs every panel to its new value; from then on the panel
 * is on its own again until the global control moves next.
 *
 * IMPLEMENTATION NOTE. The re-sync happens during render (React's documented
 * "adjusting state when a prop changes" pattern) rather than in an effect. An
 * effect would apply a render late, so a panel would paint one frame still
 * following after the user asked everything to stop — which is exactly the frame
 * that scrolls the thing they wanted to read out of view.
 */
export function useFollow(): [boolean, Dispatch<SetStateAction<boolean>>] {
  const global = useDashboardStore((s) => s.autoScroll);
  const [prevGlobal, setPrevGlobal] = useState(global);
  const [follow, setFollow] = useState(global);

  if (global !== prevGlobal) {
    setPrevGlobal(global);
    setFollow(global);
    return [global, setFollow];
  }

  return [follow, setFollow];
}
