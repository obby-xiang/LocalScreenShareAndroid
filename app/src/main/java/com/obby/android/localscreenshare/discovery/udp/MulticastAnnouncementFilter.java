package com.obby.android.localscreenshare.discovery.udp;

import androidx.annotation.NonNull;

import com.obby.android.localscreenshare.core.stream.TimeSource;
import com.obby.android.localscreenshare.support.Constants;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Admission rules applied to decoded announcements before they reach the store.
 *
 * <p>Three independent rules live here, all of them clock-injected so they are deterministic on
 * the JVM:</p>
 *
 * <ul>
 *   <li><b>Self filtering</b> is by device id only. Filtering by address is wrong on a
 *       multi-homed device: the same phone appears on several interfaces and may see its own
 *       announcement through a different one.</li>
 *   <li><b>Ordering</b> discards a datagram whose {@code (boot, seq)} was already seen or is older
 *       than the newest one from the same boot. A different boot means the peer restarted, so its
 *       sequence baseline restarts too.</li>
 *   <li><b>Registration merging</b> lets one address trigger at most one Register call per
 *       {@link Constants#REGISTER_MERGE_WINDOW_MS} window, which collapses the three startup
 *       announcements and the interfaces that heard them into one handshake.</li>
 * </ul>
 *
 * <p>Both tables are bounded, so a hostile or broken peer cannot grow discovery state without
 * limit. Instances are not thread-safe; the provider calls them on the main thread.</p>
 */
public final class MulticastAnnouncementFilter {

    /** Upper bound of tracked devices and addresses. */
    public static final int MAX_TRACKED = 256;

    @NonNull
    private final Predicate<String> mSelfIdFilter;

    @NonNull
    private final TimeSource mTimeSource;

    @NonNull
    private final Map<String, Sequence> mSequences = new LinkedHashMap<>();

    @NonNull
    private final Map<String, Long> mLastRegisterMs = new LinkedHashMap<>();

    public MulticastAnnouncementFilter(@NonNull final Predicate<String> selfIdFilter,
        @NonNull final TimeSource timeSource) {
        mSelfIdFilter = Objects.requireNonNull(selfIdFilter, "selfIdFilter");
        mTimeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    /**
     * Applies the self and ordering rules.
     *
     * @return true when the announcement is new and should be delivered
     */
    public boolean accept(@NonNull final Announcement announcement) {
        if (mSelfIdFilter.test(announcement.getId())) {
            return false;
        }
        final String boot = announcement.getBoot();
        if (boot == null || announcement.getSeq() < 0L) {
            // Without an envelope the datagram cannot be ordered; delivering it is still safe
            // because the store merges by id and the device model tolerates repeats.
            return true;
        }

        final Sequence sequence = mSequences.get(announcement.getId());
        if (sequence == null || !boot.equals(sequence.boot)) {
            putSequence(announcement.getId(), new Sequence(boot, announcement.getSeq()));
            return true;
        }
        if (announcement.getSeq() <= sequence.seq) {
            return false;
        }
        sequence.seq = announcement.getSeq();
        return true;
    }

    /**
     * Returns whether the address may start a Register call now.
     *
     * <p>The first call for an address is always allowed; later ones are merged while the window
     * is open. A goodbye never reaches this method.</p>
     */
    public boolean shouldRegister(@NonNull final String host, final int port) {
        final String key = host + "|" + port;
        final long nowMs = mTimeSource.nowMs();
        final Long lastMs = mLastRegisterMs.get(key);
        if (lastMs != null && nowMs - lastMs < Constants.REGISTER_MERGE_WINDOW_MS) {
            return false;
        }
        mLastRegisterMs.put(key, nowMs);
        if (mLastRegisterMs.size() > MAX_TRACKED) {
            final Iterator<String> iterator = mLastRegisterMs.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    /** Drops the ordering and merge state; called when discovery stops. */
    public void reset() {
        mSequences.clear();
        mLastRegisterMs.clear();
    }

    private void putSequence(@NonNull final String id, @NonNull final Sequence sequence) {
        mSequences.put(id, sequence);
        if (mSequences.size() > MAX_TRACKED) {
            final Iterator<String> iterator = mSequences.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    /** Last accepted sequence of one device boot. */
    private static final class Sequence {
        @NonNull
        final String boot;

        long seq;

        private Sequence(@NonNull final String boot, final long seq) {
            this.boot = boot;
            this.seq = seq;
        }
    }
}
