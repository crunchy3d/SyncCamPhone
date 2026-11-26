package com.crunchy3d.synccamphone;

import java.util.Arrays;

/**
 * Measures the frame phase of this camera (in master time) against the reference camera and
 * corrects it with one-frame duration steps. Also measures this camera's own period and frame
 * anchor, which the master uses when this camera is the reference.
 *
 * Runs on the camera thread (onFrame); results are read by other threads via volatile fields.
 */
final class PhaseAligner {
    static final int N = 30;                 // frames per estimate (1 s at 30 fps)
    static final int N_FAST = 10;            // frames per estimate while not yet aligned
    // tighter since 2026-11-17 (user: correct at 10 us): was 50 / 100 us
    static final long ALIGN_NS = 20_000;     // aligning: correct until |error| < 20 us
    static final long TRACK_NS = 40_000;     // aligned: correct again when |error| > 40 us

    // Drift-aware tracking: once the phase drift rate is known, let the phase sweep through
    // zero: correct when it is TRIG_NS past zero in the drift direction, to TARGET_NS on the
    // other side. Gives a sawtooth centred on zero instead of one between 0 and -TRACK_NS.
    // +-10 us (was 30 / 25 us): sawtooth -10..+10 us, a small step about every 3 s at 6.5 us/s
    static final long TRIG_NS = 10_000;
    static final long TARGET_NS = 10_000;
    static final double MIN_DRIFT_NS_PER_S = 1_000;   // below 1 us/s: use plain hysteresis
    // FILTERED TRACKING WITH OVERSHOOT (#5, user's design 2026-11-25). The Pixel 4's phase estimates scatter
    // ~+-15 us (much of it the Wi-Fi clock); deciding on single estimates chased that noise (a step every
    // 2.5 s, half the wrong way) and every step paused the measurement ~1 s (red / "measuring"). Now:
    //  - first-order filter y = y + A (x - y) per frame (time constant ~1 s on top of the 1 s median),
    //    started at the measured value;
    //  - aligned: filtered > +TRACK_T -> step to -TRACK_T/2, < -TRACK_T -> to +TRACK_T/2 (overshoot by the
    //    sign of the error: a real drift gives a long sawtooth around zero, noise alone rarely reaches T);
    //  - a step shifts the stored frame times and the filter by the step: no measurement pause;
    //  - coarse: |filtered| or |measured - filtered| > COARSE_NS -> straight to 0, filter restarted.
    static final double FILTER_A = 1.0 / 30;
    static final long TRACK_T_NS = 20_000;
    static final long COARSE_NS = 1_000_000;
    private double filtNs = Double.NaN;
    volatile double rawPhaseNs = Double.NaN;  // last unfiltered estimate (log)
    volatile int coarseSteps;
    private long pendingDelta;                // the step requested, applied to the history when it lands
    volatile double noiseNs = Double.NaN;     // (unused since the filter; kept for the log format)

    double trigNs() {
        return TRACK_T_NS;
    }
    // segment length for a drift estimate: the segments between the steps are ~2 s now (was 5 s / 50)
    static final double MIN_SEG_S = 1.5;
    static final int MIN_SEG_POINTS = 40;

    /** Reference frame grid in master time: frames at t0 + k * period. */
    static final class Ref {
        final long t0;
        final double period;

        Ref(long t0, double period) {
            this.t0 = t0;
            this.period = period;
        }
    }

    private final long[] buf = new long[64];
    private int count;                        // frames since the last reset
    private String pendingTag;                // step requested, its frame not seen yet
    private int pendingFrames;

    volatile boolean phaseValid;
    volatile double phaseNs;
    volatile boolean aligned;
    volatile int steps;
    volatile double lastStepNs;
    volatile boolean ownValid;
    volatile long ownT0;                      // a recent own frame time (master time), robust
    volatile double ownPeriodNs;              // own frame period in master time
    volatile boolean driftValid;
    volatile double driftNsPerS;              // phase drift rate (ns per second)

    // phase estimates since the last step: (master time, phase)
    private final long[] segT = new long[1024];
    private final double[] segP = new double[1024];
    private int segCount;

    void reset() {
        count = 0;
        pendingTag = null;
        phaseValid = false;
        ownValid = false;
        aligned = false;
        segCount = 0;
        driftValid = false;
    }

    /** Least-squares slope (ns/s) of the current segment, or NaN if it is too short. */
    private double segmentSlope() {
        int n = Math.min(segCount, segT.length);
        if (n < MIN_SEG_POINTS) return Double.NaN;
        int first = segCount - n;
        long t0 = segT[first % segT.length];
        double span = (segT[(segCount - 1) % segT.length] - t0) / 1e9;
        if (span < MIN_SEG_S) return Double.NaN;
        double mx = 0, my = 0;
        for (int i = first; i < segCount; i++) {
            mx += (segT[i % segT.length] - t0) / 1e9;
            my += segP[i % segP.length];
        }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0;
        for (int i = first; i < segCount; i++) {
            double dx = (segT[i % segT.length] - t0) / 1e9 - mx;
            sxx += dx * dx;
            sxy += dx * (segP[i % segP.length] - my);
        }
        if (sxx <= 0) return Double.NaN;
        double b = sxy / sxx, r2 = 0;
        for (int i = first; i < segCount; i++) {
            double dx = (segT[i % segT.length] - t0) / 1e9 - mx;
            double r = segP[i % segP.length] - my - b * dx;
            r2 += r * r;
        }
        lastResidNs = Math.sqrt(r2 / n);
        return b;
    }

    private double lastResidNs = Double.NaN;

    /**
     * @param tMaster frame timestamp converted to master time (Long.MIN_VALUE if no clock yet)
     * @param ref     reference grid, or null
     * @param correct true if this camera should steer its phase (align on, not the reference)
     */
    void onFrame(long tMaster, String tag, Ref ref, boolean correct, SyncCamera cam) {
        boolean stepLanded = false;
        if (pendingTag != null) {
            // frames up to and including the step frame are not shifted yet; after it all frames are
            // later by pendingDelta: shift the history (frame times, filter) by it and go on (no pause)
            if (pendingTag.equals(tag)) {
                pendingTag = null;
                stepLanded = true;
            } else if (++pendingFrames > 60) {        // step frame never seen: restart cleanly
                pendingTag = null;
                count = 0;
                filtNs = Double.NaN;
                aligned = false;
                return;
            }
        }
        if (tMaster == Long.MIN_VALUE) return;
        buf[count % buf.length] = tMaster;
        count++;
        if (stepLanded) {
            int n = Math.min(count, buf.length);          // all stored frames incl. the step frame itself
            for (int k = 0; k < n; k++) buf[(count - 1 - k) % buf.length] += pendingDelta;
            if (!Double.isNaN(filtNs)) filtNs += pendingDelta;
        }
        int nf = correct && !aligned ? N_FAST : N;   // fewer frames while aligning (faster start)
        if (count < nf) {
            phaseValid = false;
            return;
        }

        long[] t = new long[nf];
        for (int i = 0; i < nf; i++) t[i] = buf[(count - nf + i) % buf.length];

        // own period (median interval; robust against single-timestamp outliers and drops)
        double[] iv = new double[nf - 1];
        for (int i = 0; i < nf - 1; i++) iv[i] = t[i + 1] - t[i];
        double period = median(iv);
        double[] anchor = new double[nf];
        for (int i = 0; i < nf; i++) anchor[i] = (t[i] - t[nf - 1]) - (i - (nf - 1)) * period;
        ownT0 = t[nf - 1] + Math.round(median(anchor));
        ownPeriodNs = period;
        ownValid = true;

        if (ref == null || ref.period <= 0) {
            phaseValid = false;
            return;
        }
        double raw = phaseError(t, ref);
        rawPhaseNs = raw;
        if (Double.isNaN(filtNs)) filtNs = raw;
        else filtNs += FILTER_A * (raw - filtNs);
        phaseNs = aligned ? filtNs : raw;
        phaseValid = true;

        if (!correct) {
            aligned = false;
            segCount = 0;
            return;
        }
        segT[segCount % segT.length] = t[nf - 1];
        segP[segCount % segP.length] = phaseNs;
        segCount++;
        double slope = segmentSlope();                // drift: for the log only
        if (pendingTag != null) return;               // a step is on its way

        double target, err;
        if (!aligned) {
            // (re)aligning on the measured value, straight to 0; then the filter starts there
            if (Math.abs(raw) <= ALIGN_NS) {
                aligned = true;
                filtNs = raw;
                return;
            }
            err = raw;
            target = 0;
        } else if (Math.abs(filtNs) > COARSE_NS || Math.abs(raw - filtNs) > COARSE_NS) {
            aligned = false;                          // a big jump: coarse correction to 0
            coarseSteps++;
            filtNs = Double.NaN;
            err = raw;
            target = 0;
        } else if (filtNs > TRACK_T_NS) {
            err = filtNs;
            target = -TRACK_T_NS / 2.0;               // overshoot to the other side
        } else if (filtNs < -TRACK_T_NS) {
            err = filtNs;
            target = TRACK_T_NS / 2.0;
        } else {
            return;
        }
        // we are err late (positive) or early: shift all later frames by target - err
        long delta = Math.round(target - err);
        String stepTag = cam.requestStep(delta);
        if (stepTag == null) {
            delta += Math.round(ref.period);
            stepTag = cam.requestStep(delta);
        }
        if (stepTag != null) {
            pendingTag = stepTag;
            pendingFrames = 0;
            pendingDelta = Double.isNaN(filtNs) ? 0 : delta;
            if (Double.isNaN(filtNs)) count = 0;     // coarse: measure afresh after it
            steps++;
            lastStepNs = delta;
            if (!Double.isNaN(slope)) {
                driftNsPerS = driftValid ? 0.5 * (driftNsPerS + slope) : slope;
                driftValid = true;
            }
            segCount = 0;
        }
    }

    /** Robust circular estimate of (frame time - reference grid), wrapped to (-P/2, P/2]. */
    private static double phaseError(long[] t, Ref ref) {
        double p = ref.period;
        double[] e = new double[t.length];
        double s = 0, c = 0;
        for (int i = 0; i < t.length; i++) {
            e[i] = wrap(t[i] - ref.t0, p);
            double a = 2 * Math.PI * e[i] / p;
            s += Math.sin(a);
            c += Math.cos(a);
        }
        double m = Math.atan2(s, c) / (2 * Math.PI) * p;
        for (int i = 0; i < e.length; i++) e[i] = m + wrap(e[i] - m, p);
        return wrap(median(e), p);
    }

    static double wrap(double x, double p) {
        double r = x - p * Math.floor(x / p);
        return r > p / 2 ? r - p : r;
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : 0.5 * (s[n / 2 - 1] + s[n / 2]);
    }
}
