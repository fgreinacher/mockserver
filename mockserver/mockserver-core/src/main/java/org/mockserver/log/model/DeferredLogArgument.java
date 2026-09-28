package org.mockserver.log.model;

import java.util.function.Supplier;

/**
 * A log-message argument that is rendered each time its entry is rendered, and never stored as text.
 * <p>
 * For an argument derived entirely from objects the entry already retains, such as the curl form of a
 * logged request: storing the rendered String would keep a second copy of the request body on every
 * retained entry, which the event-log byte budget does not count. {@link LogEntry} renders it wherever
 * the stored String used to appear, so the output is unchanged. The renderer must depend only on
 * objects that are not mutated after the entry is logged.
 */
public final class DeferredLogArgument {

    private final Supplier<String> renderer;

    private DeferredLogArgument(Supplier<String> renderer) {
        this.renderer = renderer;
    }

    public static DeferredLogArgument deferred(Supplier<String> renderer) {
        return new DeferredLogArgument(renderer);
    }

    /**
     * The rendered text, or a short placeholder if rendering fails, so one bad argument cannot break a
     * retrieve or dashboard read of the whole log.
     */
    public String render() {
        try {
            return renderer.get();
        } catch (RuntimeException e) {
            return "<unable to render: " + e.getClass().getSimpleName() + ">";
        }
    }

    @Override
    public String toString() {
        return render();
    }
}
