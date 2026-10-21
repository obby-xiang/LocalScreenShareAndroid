package com.obby.android.localscreenshare.render;

import android.content.Context;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.obby.android.localscreenshare.R;
import com.obby.android.localscreenshare.support.ServiceProtocol;

import java.util.List;

/**
 * Zoom, display-size and debug-overlay menus shared by both display hosts.
 *
 * <p>The floating window and the full-screen activity expose the same display controls, so the
 * rows, the labels and the dialogs live here once. The rows themselves come from
 * {@link ViewerZoomMenuModel} and {@link ViewerScaleMenuModel}, which own the projection rules; this
 * class only renders them and reports the chosen row. A host supplies one optional trailing action
 * row - "expand to full screen" in the window, "collapse to the window" in the activity - because
 * the two hosts are each other's escape hatch.</p>
 *
 * <p><b>Floating window.</b> A dialog shown by the service has to be a floating window itself;
 * {@code floatingWindow} selects {@link ServiceProtocol#FLOATING_WINDOW_TYPE} for it. A dialog
 * shown by the activity must not set that type, or it would fail without the overlay permission.</p>
 */
public final class ViewerMenuFactory {

    private ViewerMenuFactory() {
    }

    /**
     * Receiver of the selected menu row.
     */
    public interface Listener {

        /**
         * Applies a zoom preset.
         *
         * @param mode chosen preset
         */
        void onZoomModeSelected(ZoomMode mode);

        /**
         * Applies a viewing percentage from the display-size submenu.
         *
         * @param percent chosen percentage
         */
        void onScalePercentSelected(int percent);

        /**
         * Applies the debug-overlay toggle.
         *
         * @param enabled whether the overlay should be shown
         */
        void onHudEnabledSelected(boolean enabled);

        /**
         * Toggles the viewer's audio mute.
         *
         * <p>A default no-op keeps hosts that do not expose the audio row (the full-screen
         * activity) source-compatible.</p>
         */
        default void onAudioMuteToggled() {
        }

        /**
         * Runs the host's trailing action row.
         */
        void onActionSelected();
    }

    /**
     * Builds the display-mode menu.
     *
     * <p>Rows are the zoom presets, the display-size row that opens the submenu, and the host's
     * action row when {@code actionLabelResId} is not zero. The selected preset is marked in its own
     * label, because the theme's single-choice item renders no indicator on the device the menu was
     * tested on: the checked state existed only in the accessibility tree.</p>
     *
     * @param context          context that owns the dialog; required
     * @param entries          zoom rows from {@link ViewerZoomMenuModel}; required
     * @param scalePercent     applied viewing percentage, used by the display-size row
     * @param actionLabelResId label of the trailing action row, or zero for none
     * @param floatingWindow   whether the dialog must be a floating window
     * @param listener         receiver of the chosen row; required
     * @return a dialog that the caller shows; never null
     */
    public static AlertDialog createZoomDialog(Context context,
                                               List<ViewerZoomMenuModel.Entry> entries,
                                               int scalePercent,
                                               @StringRes int actionLabelResId,
                                               boolean floatingWindow,
                                               Listener listener) {
        return createZoomDialog(context, entries, scalePercent, 0, actionLabelResId,
                floatingWindow, listener);
    }

    /**
     * Builds the display-mode menu with an optional audio-mute row.
     *
     * <p>The audio row sits between the last display row and the host's action row. It is a plain
     * item rather than a checked one: muting is an action on the playback, not a display mode, and
     * marking a row would compete with the zoom selection.</p>
     *
     * @param context          context that owns the dialog; required
     * @param entries          zoom rows from {@link ViewerZoomMenuModel}; required
     * @param scalePercent     applied viewing percentage, used by the display-size row
     * @param audioLabelResId  label of the audio row, or zero to omit it
     * @param actionLabelResId label of the trailing action row, or zero for none
     * @param floatingWindow   whether the dialog must be a floating window
     * @param listener         receiver of the chosen row; required
     * @return a dialog that the caller shows; never null
     */
    public static AlertDialog createZoomDialog(Context context,
                                               List<ViewerZoomMenuModel.Entry> entries,
                                               int scalePercent,
                                               @StringRes int audioLabelResId,
                                               @StringRes int actionLabelResId,
                                               boolean floatingWindow,
                                               Listener listener) {
        final boolean hasAction = actionLabelResId != 0;
        final boolean hasAudio = audioLabelResId != 0;
        final int scaleIndex = entries.size();
        final int audioIndex = scaleIndex + 1;
        final int actionIndex = audioIndex + (hasAudio ? 1 : 0);
        final CharSequence[] labels = new CharSequence[scaleIndex + 1
                + (hasAudio ? 1 : 0) + (hasAction ? 1 : 0)];
        int checked = 0;
        for (int i = 0; i < entries.size(); i++) {
            final ViewerZoomMenuModel.Entry entry = entries.get(i);
            labels[i] = labelOf(context, entry);
            if (entry.isSelected()) {
                checked = i;
            }
        }
        labels[scaleIndex] = context.getString(R.string.viewer_scale_menu_entry_percent,
            context.getString(R.string.viewer_scale_setting_label), scalePercent);
        if (hasAudio) {
            labels[audioIndex] = context.getString(audioLabelResId);
        }
        if (hasAction) {
            labels[actionIndex] = context.getString(actionLabelResId);
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.viewer_zoom_mode_title)
            .setSingleChoiceItems(labels, checked, (target, which) -> {
                target.dismiss();
                if (which == scaleIndex) {
                    createScaleDialog(context, scalePercent, floatingWindow, listener).show();
                    return;
                }
                if (hasAudio && which == audioIndex) {
                    listener.onAudioMuteToggled();
                    return;
                }
                if (hasAction && which == actionIndex) {
                    listener.onActionSelected();
                    return;
                }
                final ViewerZoomMenuModel.Entry entry = entries.get(which);
                if (entry.getKind() == ViewerZoomMenuModel.Kind.DEBUG_OVERLAY) {
                    listener.onHudEnabledSelected(!entry.isSelected());
                } else {
                    listener.onZoomModeSelected(entry.getMode());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        if (floatingWindow) {
            dialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
        }
        return dialog;
    }

    /**
     * Builds the display-size submenu.
     *
     * <p>Rows come from {@link ViewerScaleMenuModel}: the presets plus the applied percentage when it
     * is not one of them, so the menu never marks a neighbouring preset as current.</p>
     *
     * @param context        context that owns the dialog; required
     * @param scalePercent   applied viewing percentage
     * @param floatingWindow whether the dialog must be a floating window
     * @param listener       receiver of the chosen percentage; required
     * @return a dialog that the caller shows; never null
     */
    public static AlertDialog createScaleDialog(Context context, int scalePercent,
                                                boolean floatingWindow, Listener listener) {
        final List<ViewerScaleMenuModel.Entry> entries = ViewerScaleMenuModel.build(scalePercent);
        final CharSequence[] labels = new CharSequence[entries.size()];
        int checked = 0;
        for (int i = 0; i < entries.size(); i++) {
            final ViewerScaleMenuModel.Entry entry = entries.get(i);
            labels[i] = markSelection(context,
                context.getString(R.string.viewer_scale_option_percent, entry.getPercent()),
                entry.isSelected());
            if (entry.isSelected()) {
                checked = i;
            }
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
            .setTitle(R.string.viewer_scale_setting_label)
            .setSingleChoiceItems(labels, checked, (target, which) -> {
                listener.onScalePercentSelected(entries.get(which).getPercent());
                target.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        if (floatingWindow) {
            dialog.getWindow().setType(ServiceProtocol.FLOATING_WINDOW_TYPE);
        }
        return dialog;
    }

    /**
     * Returns the label of one zoom row, including the scale it would apply and the marker of the
     * currently applied preset.
     */
    private static String labelOf(Context context, ViewerZoomMenuModel.Entry entry) {
        if (entry.getKind() == ViewerZoomMenuModel.Kind.DEBUG_OVERLAY) {
            return markSelection(context, context.getString(R.string.viewer_debug_overlay_label),
                entry.isSelected());
        }
        final int percent = entry.getEffectivePercent();
        final String label;
        switch (entry.getMode()) {
            case ONE_TO_ONE:
                label = context.getString(entry.isClamped()
                    ? R.string.viewer_zoom_mode_one_to_one_clamped_percent
                    : R.string.viewer_zoom_mode_one_to_one_percent, percent);
                break;
            case INTEGER:
                label = context.getString(R.string.viewer_zoom_mode_integer_percent, percent);
                break;
            case FIT:
            default:
                label = context.getString(R.string.viewer_zoom_mode_fit_percent, percent);
                break;
        }
        return markSelection(context, label, entry.isSelected());
    }

    /**
     * Prefixes the marker of the applied value, so the menu shows a selection on screen and not only
     * in the accessibility tree.
     */
    private static String markSelection(Context context, String label, boolean selected) {
        return selected ? context.getString(R.string.viewer_menu_current, label) : label;
    }
}
