import { useCallback, useLayoutEffect, useRef } from 'react';

/** A scroll within this many ms of a real user input counts as that user's doing. */
const USER_INTENT_WINDOW_MS = 400;

/** Within this many px of the bottom counts as "at the tail". */
const AT_BOTTOM_THRESHOLD_PX = 8;

/**
 * Keeps a scroll container pinned to its tail while `follow` is on, and stops
 * following only when the READER actually scrolls away.
 *
 * WHY A `scroll` EVENT IS NOT ENOUGH, which is what broke the first version.
 * These lists are virtualized: rows are measured as they render, so the content
 * height keeps changing after a pin. Measured against a live server, clicking
 * Follow pinned correctly and then cancelled itself about a second later —
 * `scrollHeight` went 4656 -> 4840 -> 10440 as rows were measured, each reflow
 * moved the element off the bottom and fired a `scroll` event, and a handler that
 * reads "not at the bottom" as "the reader scrolled away" turned following off.
 * Follow appeared not to work at all.
 *
 * So intent is tracked explicitly. A scroll only stops following if it lands
 * within USER_INTENT_WINDOW_MS of a real input event — wheel, touch, pointer
 * (a scrollbar drag) or a navigation key. A scroll caused by our own pin, by the
 * virtualizer measuring, or by new rows arriving has no such input behind it and
 * is ignored -- in EITHER direction. Resuming needs a gesture too: the window
 * evicts from the top, so the content shrinks, and a shrink alone can leave a
 * motionless reader at the bottom and switch following back on underneath them.
 *
 * RE-PINNING. Following also has to survive the content growing underneath it, so
 * a ResizeObserver on the scrolled content re-pins on every size change. Without
 * it the view would sit still while the list grew past it — following in name only.
 */
export function useTailFollow(
  follow: boolean | undefined,
  onFollowChange: ((follow: boolean) => void) | undefined,
) {
  const elRef = useRef<HTMLDivElement | null>(null);
  const detachRef = useRef<(() => void) | null>(null);
  const lastUserInputAt = useRef(0);
  // The previous scrollTop, so direction comes from where the view ACTUALLY went.
  // A flag set by the wheel handler was not enough: it said nothing about a
  // scrollbar DRAG (pointerdown, then scrolls with no wheel), and it went stale --
  // after wheeling down to the tail it still read "downward", so a later drag
  // upward was not recognised as leaving and the pin hauled the reader back.
  const prevScrollTop = useRef(0);
  // Whether the reader's last INPUT was towards the tail. Resume uses this rather
  // than the scroll delta, because the delta lies here: turning following off
  // re-enables the list's scroll anchoring, and the anchor restore scrolls the
  // view DOWN on its own. Read as a delta that is indistinguishable from the
  // reader scrolling back, so following switched itself straight back on.
  const lastInputWasDownward = useRef(false);

  const markUserIntent = useCallback(() => {
    lastUserInputAt.current = Date.now();
  }, []);

  // Stopping is driven by the GESTURE, not by the scroll event it causes.
  // Inferring it from the scroll was racy: the re-pin could land first, leaving
  // the element back at the bottom so the scroll handler saw `atBottom` and kept
  // following. Measured against a live server, a deliberate wheel-up could not
  // stop it at all. A wheel up, or a key that navigates upward, is unambiguous on
  // its own — so act on it directly.
  const stopFollowing = useRef(onFollowChange);
  const followRef = useRef(follow);
  // Synced in a LAYOUT effect, not during render (which `react-hooks/refs`
  // forbids) and not in a passive effect (which runs after paint, leaving a
  // window where a wheel event would read a stale `follow` and do nothing).
  useLayoutEffect(() => {
    stopFollowing.current = onFollowChange;
    followRef.current = follow;
  });
  const userMovedAway = useCallback(() => {
    if (followRef.current) stopFollowing.current?.(false);
  }, []);

  /**
   * The ONE place that moves the view to the tail. Every caller goes through it
   * so the mid-gesture guard cannot be forgotten: a second, unguarded pin in the
   * panel's own data effect re-pinned in the same tick as a wheel event -- before
   * React had flushed `follow: false` -- and undid the reader's scroll about one
   * time in six.
   */
  const pinToTail = useCallback(() => {
    const el = elRef.current;
    if (!el || !followRef.current) return;
    // Never yank the view back while the reader is mid-gesture. Under load the
    // observer fires continuously as rows are measured.
    if (Date.now() - lastUserInputAt.current < USER_INTENT_WINDOW_MS) return;
    el.scrollTop = el.scrollHeight;
  }, []);

  // Keep pinning as the content is measured and grows.
  useLayoutEffect(() => {
    const el = elRef.current;
    if (!el || !follow) return;
    const pin = pinToTail;
    pin();

    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(pin);
    // The element's own box rarely changes; what grows is the content inside it,
    // which for a virtualized list is the sizing spacer.
    //
    // REBIND WHEN THE CHILDREN ARE REPLACED. Observing them once is not enough,
    // and getting this wrong is invisible: ProgressiveList paints row divs first
    // and only swaps in its sizing spacer once it has found a scroll parent. That
    // swap does not change `follow`, so an effect keyed on `follow` alone keeps
    // observing the detached rows and never sees the spacer -- the one node whose
    // height actually grows as rows are measured. It matters most on the DEFAULT
    // path, where autoScroll is on at mount and nobody ever clicks Follow.
    //
    // HONESTLY: this is correctness, not a fix for an observed failure. Removing
    // the rebinding and re-testing against a real server -- seeded backlog, no
    // traffic, so measurement is the only thing that could move the height -- did
    // NOT reproduce a fault, because Panel's own re-pin runs on every render and
    // covers it. The rebinding is kept because an observer bound to detached nodes
    // is wrong however well something else happens to mask it, and whatever masks
    // it today is not a guarantee anyone wrote down.
    const observeChildren = () => {
      observer.disconnect();
      observer.observe(el);
      for (const child of Array.from(el.children)) observer.observe(child);
    };
    observeChildren();
    const childSwap = new MutationObserver(observeChildren);
    childSwap.observe(el, { childList: true });
    return () => {
      observer.disconnect();
      childSwap.disconnect();
    };
  }, [follow, pinToTail]);

  const handleScroll = useCallback(
    (el: HTMLElement) => {
      if (follow === undefined || !onFollowChange) return;
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= AT_BOTTOM_THRESHOLD_PX;

      // BOTH directions require the reader's own gesture. Resuming was briefly
      // unconditional -- "arriving at the tail always resumes" -- which sounds
      // harmless but is not: under load the window evicts from the top, so the
      // content SHRINKS, and a shrink can leave a reader who never moved sitting
      // at the bottom. Following then switched itself back on and the panel
      // started scrolling under them again. Only a person may change this.
      const movedUp = el.scrollTop < prevScrollTop.current;
      prevScrollTop.current = el.scrollTop;

      const byUser = Date.now() - lastUserInputAt.current < USER_INTENT_WINDOW_MS;
      if (!byUser) return;
      if (atBottom) {
        // Resume on a downward INPUT, not a downward delta -- see
        // lastInputWasDownward.
        if (!follow && lastInputWasDownward.current) onFollowChange(true);
      } else if (follow && movedUp) {
        // Stop on the observed movement, which unlike an input event also covers
        // a scrollbar DRAG: that produces pointerdown plus scrolls, and no wheel.
        onFollowChange(false);
      }
    },
    [follow, onFollowChange],
  );

  /**
   * A CALLBACK ref, not an object ref, because the listeners below must follow
   * the element. Attaching them from an effect that merely READS `ref.current`
   * leaves them on a detached node the moment the scroll container is replaced:
   * the effect's deps have not changed, so it never re-runs, and the element now
   * on screen has no handlers at all. Measured against a live server that lost
   * the wheel gesture entirely on roughly half of all runs -- Follow could not be
   * switched off by scrolling, with no error anywhere to say why.
   *
   * Wheel/touch/pointer/key all mean a person is driving. They are registered
   * `passive` because they fire at high frequency during a scroll and must never
   * block it.
   */
  const scrollRef = useCallback(
    (node: HTMLDivElement | null) => {
      detachRef.current?.();
      detachRef.current = null;
      elRef.current = node;
      if (!node) return;

      const opts = { passive: true } as const;
      const navKeys = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' ']);
      const upKeys = new Set(['ArrowUp', 'PageUp', 'Home']);
      const onWheel = (e: WheelEvent) => {
        markUserIntent();
        lastInputWasDownward.current = e.deltaY > 0;
        // Act on the gesture immediately rather than waiting for the scroll it
        // causes: the re-pin could otherwise land first and the scroll would read
        // as "still at the tail". Scrolling down needs no fast path — handleScroll
        // resumes on arrival.
        if (e.deltaY < 0) userMovedAway();
      };
      const onKeyDown = (e: KeyboardEvent) => {
        if (!navKeys.has(e.key)) return;
        markUserIntent();
        // Space scrolls down, Shift+Space scrolls UP — same `e.key`.
        const up = upKeys.has(e.key) || (e.key === ' ' && e.shiftKey);
        lastInputWasDownward.current = !up;
        if (up) userMovedAway();
      };
      // Touch records intent; the observed movement decides whether it stopped
      // following, so a swipe back towards the tail is not read as leaving.
      // Resuming by touch alone is not offered -- there is no reliable downward
      // signal here without tracking clientY -- so the chip resumes it.
      const onTouchMove = markUserIntent;
      node.addEventListener('wheel', onWheel, opts);
      node.addEventListener('touchstart', markUserIntent, opts);
      node.addEventListener('touchmove', onTouchMove, opts);
      // A scrollbar drag: intent, with no direction of its own. Clearing the
      // downward flag keeps a stale "was going down" from a much earlier wheel
      // out of the resume decision.
      const onPointerDown = () => {
        markUserIntent();
        lastInputWasDownward.current = false;
      };
      node.addEventListener('pointerdown', onPointerDown, opts);
      node.addEventListener('keydown', onKeyDown, opts);
      detachRef.current = () => {
        node.removeEventListener('wheel', onWheel);
        node.removeEventListener('touchstart', markUserIntent);
        node.removeEventListener('touchmove', onTouchMove);
        node.removeEventListener('pointerdown', onPointerDown);
        node.removeEventListener('keydown', onKeyDown);
      };
    },
    [markUserIntent, userMovedAway],
  );

  return { scrollRef, handleScroll, markUserIntent, pinToTail };
}
