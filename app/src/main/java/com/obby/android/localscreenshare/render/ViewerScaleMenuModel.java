package com.obby.android.localscreenshare.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure projection of the viewer's viewing-percentage menu rows.
 *
 * <p><b>Why.</b> The viewing percentage sized the viewer window but had no entry in the user
 * interface: the only control for it lived in the legacy bitmap viewer, which nothing mounts. The
 * percentage is stored separately from the zoom preset, so it needs its own menu. The rows are the
 * three presets and, when the applied percentage is not one of them, that value as well: a menu
 * that silently preselects a neighbouring preset would misreport the window the user is looking at,
 * which is the same class of mistake the zoom menu rows were fixed for.</p>
 *
 * <p><b>Purity.</b> The model reads no configuration, keeps no state and touches no Android type, so
 * the same input always produces the same rows and every rule is unit-tested on the JVM.</p>
 */
public final class ViewerScaleMenuModel {

    /**
     * Lowest percentage a row can carry; a window of zero pixels cannot be shown.
     */
    public static final int MIN_PERCENT = 1;

    /**
     * Highest percentage a row can carry; the percentage is a fraction of the usable display area.
     */
    public static final int MAX_PERCENT = 100;

    /**
     * Presets offered by the menu, in ascending order.
     */
    private static final int[] PRESET_PERCENTS = {60, 80, 100};

    private ViewerScaleMenuModel() {
    }

    /**
     * Builds the viewing-percentage rows for an applied percentage.
     *
     * <p>Rows are ascending so the menu always reads as "smaller … larger". The applied percentage
     * is clamped into {@link #MIN_PERCENT}..{@link #MAX_PERCENT} first: a value written by another
     * version, or a zero left behind by a cleared preference, must not produce an unusable row or a
     * row outside the range the window policy accepts.</p>
     *
     * @param currentPercent applied viewing percentage
     * @return an immutable list of at least three rows; never null
     */
    public static List<Entry> build(int currentPercent) {
        final int current = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, currentPercent));
        final List<Integer> percents = new ArrayList<>(PRESET_PERCENTS.length + 1);
        boolean currentListed = false;
        for (int preset : PRESET_PERCENTS) {
            if (preset == current) {
                // The applied percentage is a preset already; listing it twice would show two rows
                // for one window size.
                currentListed = true;
            }
        }
        for (int preset : PRESET_PERCENTS) {
            if (!currentListed && current < preset) {
                percents.add(current);
                currentListed = true;
            }
            percents.add(preset);
        }
        if (!currentListed) {
            percents.add(current);
        }

        final List<Entry> entries = new ArrayList<>(percents.size());
        for (int percent : percents) {
            entries.add(new Entry(percent, percent == current));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * One viewing-percentage row: the percentage it applies and whether it is the applied one.
     */
    public static final class Entry {

        private final int percent;

        private final boolean selected;

        private Entry(int percent, boolean selected) {
            this.percent = percent;
            this.selected = selected;
        }

        /**
         * Returns the viewing percentage this row applies.
         */
        public int getPercent() {
            return percent;
        }

        /**
         * Returns whether this row is the percentage currently applied to the window.
         */
        public boolean isSelected() {
            return selected;
        }
    }
}
