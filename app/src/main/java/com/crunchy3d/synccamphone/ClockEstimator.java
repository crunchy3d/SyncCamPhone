package com.crunchy3d.synccamphone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Estimates offset (master clock - local clock) and drift from NTP-style ping samples.
 *
 * Method: keep all samples of the last 120 s; select the 10% with the shortest round trip
 * (least queueing, so the least asymmetric delay); fit offset(t) = a + b*t by least squares;
 * drop outliers beyond 3 robust sigmas and refit. With less than 10 s of data only the
 * offset is fitted (no drift).
 */
final class ClockEstimator {

    static final class Sample {
        final long localMid;   // local time of the sample (middle of the round trip)
        final double offset;   // ns, master - local
        final double rtt;      // ns, network round trip without master processing time

        Sample(long localMid, double offset, double rtt) {
            this.localMid = localMid;
            this.offset = offset;
            this.rtt = rtt;
        }
    }

    static final class Result {
        boolean valid;
        double offsetNs;       // master - local at the time of compute()
        double offsetErrNs;    // standard error of the fitted offset
        double scatterNs;      // std of the selected samples around the fit
        double driftPpm;       // master clock rate relative to local clock
        double residualNs;     // best sample of the last second minus fit (for the graph)
        double minRttNs, medRttNs;
        int used, total;
        double windowS;
    }

    private static final long WINDOW_NS = 120_000_000_000L;
    private static final double KEEP_FRACTION = 0.10;
    private static final int MIN_FIT = 8;
    private static final double MIN_DRIFT_SPAN_S = 10;
    // EVEN SELECTION (2026-11-22): the fastest 10 % PER 10 s SLICE instead of the fastest 10 % of the
    // whole window. At the start the app pings faster and a hotspot may be quickest in the first
    // seconds: all chosen samples came from there, and extrapolating them with a ~5 ppm drift made
    // the error grow 20 -> 305 us for 2 min, until they left the window (SYNC yellow / red).
    private static final long SLICE_NS = 10_000_000_000L;
    static boolean evenSelection = true;       // false: the old selection (bench comparison)

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    /** t1 = local send, t2 = master receive, t3 = master send, t4 = local receive (all ns). */
    synchronized void add(long t1, long t2, long t3, long t4) {
        double rtt = (double) (t4 - t1) - (t3 - t2);
        if (rtt < 0) return;
        double offset = ((double) (t2 - t1) + (double) (t3 - t4)) / 2.0;
        long mid = t1 + (t4 - t1) / 2;
        samples.addLast(new Sample(mid, offset, rtt));
        while (!samples.isEmpty() && samples.peekFirst().localMid < mid - WINDOW_NS) samples.pollFirst();
    }

    synchronized void reset() {
        samples.clear();
    }

    /** Fit result: a = offset at x = 0, b = slope (ns/s), sigma, se = standard error of a. */
    private static double[] fit(List<Sample> pts, long now, boolean withSlope) {
        int n = pts.size();
        double mx = 0, my = 0;
        for (Sample s : pts) {
            mx += (s.localMid - now) / 1e9;
            my += s.offset;
        }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0;
        for (Sample s : pts) {
            double dx = (s.localMid - now) / 1e9 - mx;
            sxx += dx * dx;
            sxy += dx * (s.offset - my);
        }
        double b = withSlope && sxx > 0 ? sxy / sxx : 0;
        double a = my - b * mx;
        double ss = 0;
        for (Sample s : pts) {
            double e = s.offset - (a + b * (s.localMid - now) / 1e9);
            ss += e * e;
        }
        int dof = Math.max(1, n - (withSlope ? 2 : 1));
        double sigma = Math.sqrt(ss / dof);
        double se = withSlope && sxx > 0 ? sigma * Math.sqrt(1.0 / n + mx * mx / sxx) : sigma / Math.sqrt(n);
        return new double[]{a, b, sigma, se};
    }

    synchronized Result compute(long now) {
        Result r = new Result();
        int n = samples.size();
        r.total = n;
        if (n == 0) return r;

        double[] rtts = new double[n];
        int i = 0;
        Sample best = null;
        for (Sample s : samples) {
            rtts[i++] = s.rtt;
            if (best == null || s.rtt < best.rtt) best = s;
        }
        Arrays.sort(rtts);
        r.minRttNs = rtts[0];
        r.medRttNs = rtts[n / 2];
        r.windowS = (samples.peekLast().localMid - samples.peekFirst().localMid) / 1e9;

        int k = Math.max(Math.min(n, MIN_FIT), (int) Math.ceil(n * KEEP_FRACTION));
        double limit = rtts[k - 1];
        List<Sample> sel = new ArrayList<>();
        if (evenSelection) {
            // per slice its fastest 10 % (at least one), but nothing slower than the window's median
            double cap = rtts[n / 2];
            long t0 = samples.peekFirst().localMid;
            java.util.TreeMap<Long, List<Sample>> slices = new java.util.TreeMap<>();
            for (Sample s : samples) slices.computeIfAbsent((s.localMid - t0) / SLICE_NS, x -> new ArrayList<>()).add(s);
            for (List<Sample> sl : slices.values()) {
                sl.sort((p, q) -> Double.compare(p.rtt, q.rtt));
                int kb = Math.max(1, (int) Math.ceil(sl.size() * KEEP_FRACTION));
                for (int j = 0; j < kb && j < sl.size(); j++) if (sl.get(j).rtt <= cap) sel.add(sl.get(j));
            }
        }
        if (sel.size() < MIN_FIT) {
            sel.clear();
            for (Sample s : samples) if (s.rtt <= limit) sel.add(s);
        }

        r.valid = true;
        if (sel.size() < MIN_FIT) {
            r.offsetNs = best.offset;
            r.offsetErrNs = best.rtt / 2;
            r.scatterNs = best.rtt / 2;
            r.used = 1;
            return r;
        }

        boolean withSlope = r.windowS >= MIN_DRIFT_SPAN_S;
        double[] f = fit(sel, now, withSlope);

        // robust outlier rejection (3 sigma via median absolute deviation), then refit
        double[] res = new double[sel.size()];
        for (int j = 0; j < res.length; j++) {
            Sample s = sel.get(j);
            res[j] = s.offset - (f[0] + f[1] * (s.localMid - now) / 1e9);
        }
        double[] sorted = res.clone();
        Arrays.sort(sorted);
        double med = sorted[sorted.length / 2];
        double[] dev = new double[res.length];
        for (int j = 0; j < res.length; j++) dev[j] = Math.abs(res[j] - med);
        Arrays.sort(dev);
        double cut = Math.max(20_000, 3 * 1.4826 * dev[dev.length / 2]);
        List<Sample> kept = new ArrayList<>();
        for (int j = 0; j < res.length; j++) if (Math.abs(res[j] - med) <= cut) kept.add(sel.get(j));
        if (kept.size() >= MIN_FIT && kept.size() < sel.size()) {
            sel = kept;
            f = fit(sel, now, withSlope);
        }

        r.offsetNs = f[0];
        r.driftPpm = f[1] / 1e3;
        r.scatterNs = f[2];
        r.offsetErrNs = f[3];
        r.used = sel.size();

        // graph value: the shortest-RTT sample of the last second against the fit
        Sample recent = null;
        for (Sample s : samples) {
            if (s.localMid >= now - 1_000_000_000L && (recent == null || s.rtt < recent.rtt)) recent = s;
        }
        if (recent != null) {
            r.residualNs = recent.offset - (f[0] + f[1] * (recent.localMid - now) / 1e9);
        }
        return r;
    }
}
