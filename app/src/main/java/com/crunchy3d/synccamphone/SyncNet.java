package com.crunchy3d.synccamphone;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.provider.Settings;
import android.os.Build;

import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** UDP protocol helpers shared by master and camera nodes. */
final class SyncNet {
    private SyncNet() {}

    static final int PORT = 50505;
    static final String MAGIC = "CSP1";

    // message types
    static final String BEACON = "BEACON";   // master -> broadcast: BEACON|masterName
    static final String PING = "PING";       // camera -> master:    PING|seq|t1
    static final String PONG = "PONG";       // master -> camera:    PONG|seq|t1|t2|t3
    // camera -> master: STATUS|id|name|offset|offsetErr|scatter|minRtt|medRtt|driftPpb|residual|used|windowS
    //                   |streaming|phaseValid|phaseNs|ownValid|ownT0|ownPeriodNs|steps|aligned
    //                   |capProgressS(-1 = none)|ledY|ledPeak|recFrames(-1 = not recording)
    //                   |photoSeqDone(-1 = none)|photoErrNs (saved frame - photo time)
    //                   |side (optional 26: Downloads.SIDE_*)|maxImg (27)|maxVid (28): highest file
    //                   numbers on the phone (the master continues after the highest of all phones)
    //                   |aeSeq (29: counter of this phone's AE measurements)|aeRatio (30: wanted exposure
    //                   factor)|aeLight (31: exposure ns x ISO it was measured at)|aeClip (32: clipped
    //                   fraction)|rawDark (33)|rawMedian (34)|awb (35: own AWB gains r;ge;go;b or -)
    //                   - joint AE / white balance: the master combines all phones' measurements
    //                   |lens (36: Lenses kind this camera uses now, -1 = unknown)
    static final String STATUS = "STATUS";
    static final int STATUS_FIELDS = 26;
    // master -> camera: STATE|seq|running|periodNs|exposureNs|iso|align|refId|refValid|refT0|refPeriodNs
    //                   |captureSeq|captureSeconds|asymNs|record|recordId|videoHeight|recStart|recStop
    //                   |soundOffsetNs|photoMode|photoSeq|photoTime
    //                   |wbMode|awbPreset|wbGains(r;ge;go;b or -)|wbXform(18 ints ; or -)|focusManual|diopters
    //                   |photoAspect (0 = 4:3, 1 = 3:2, 2 = 16:9, 3 = 1:1)|photoFormat (0 JPEG, 1 RAW, 2 RAW+JPEG, 3 HDR burst)
    //                   |conformFps (0 = real timing, else written at exactly this rate)|videoBitrate (bit/s, 0 = auto)|hevc (0/1)
    //                   |appJpeg (0 = camera JPEG, 1 = in-app JPEG from full-res YUV)
    //                   |tone (SyncCamera.TONES: 0 standard, 1 soft, 2 strong)
    //                   |keepBurstFrames (0/1: HDR burst also saves the 8 single DNGs)
    //                   |hdrMode (optional field 37: PhotoBurst.MODE_* 0 one frame, 1 2-frame HDR, 2 auto)
    //                   |brightness (38: 0 night look, 1 bright, 2 natural)|natural (39: 0/1 natural motion blur)
    //                   |hdrPlan (40: longer frames "exposureNs:iso;..." or "-", computed by the master)
    //                   |toneShare (41: 0/1)|jpegQuality (42: 90 / 95 / 100, every JPEG saved)
    //                   |aeActive (43: 0/1, the cameras measure for the joint AE)|aeAllow (44: clip fraction)
    //                   |standby (45: 0/1, the master put all cameras to sleep - shown on the cameras)
    //                   |sharpLevel (46: MergeCore.SHARP_NAMES 0 Off .. 3 Strong; Off = camera edge mode off)
    //                   |clarityLevel (47: MergeCore.CLARITY_NAMES 0 Off .. 3 Strong, local contrast)
    //                   |astroStopSeq (48: incremented when the user stops a running astro series)
    //                   |nightOut (49: -1, or the user's format 0 JPEG / 1 RAW / 2 RAW+JPEG when that format
    //                   is shot as an averaging burst because the scene is too dark; photoFormat (30) is then 3)
    //                   |gpuMode (50: MergeCore.GPU_NAMES 0 Off, 1 On, 2 Check - merge weights on the GPU)
    //                   |remoteMode (51: 1 = remote trigger of another camera app, item 20; our cameras off)
    //                   |lens (52: Lenses kind 0 main, 1 ultra-wide, 2 telephoto, 3 front; copied to the cameras,
    //                   which may then choose another lens themselves)
    //                   |cbr (53: 1 = constant video bitrate, 0 = variable: the bitrate is a maximum)
    //                   |... |previewW (56: preview width limit 1920 / 1280 / 960 / 720)|fillLost (57: 1 = fill lost video frames)
    //                   |timelapse (54: 0, or the number of the time-lapse's first photo: folder Timelapse_NNNN)
    //                   |sideWarn (55: "-", or the warning naming the phones that share a side, e.g. LEFT)
    // recStart / recStop: master time of the first / last recorded frame (recStop 0 = not set)
    // soundOffsetNs: added to the sound timestamps (negative = sound earlier; Pixel 7a about -50 ms)
    // asymNs: Wi-Fi delay asymmetry correction, subtracted from the camera's master-time stamps
    // asymNs, soundOffsetNs = Long.MIN_VALUE (MasterNode.AUTO): each camera uses its own model's
    //   measured value (SyncActivity.modelDefaults); any other value = manual, for all cameras
    // (measured optically: camera->master is ~620 us slower than master->camera, so ~310 us)
    static final String STATE = "STATE";
    // master -> cameras after its HDR burst's develop has started: TONE|photoSeq|MergeCore.Tone text
    static final String TONE = "TONE";
    static final int STATE_FIELDS = 37;

    static String join(float[] v) {
        if (v == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (float x : v) sb.append(sb.length() > 0 ? ";" : "").append(x);
        return sb.toString();
    }

    static String join(int[] v) {
        if (v == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (int x : v) sb.append(sb.length() > 0 ? ";" : "").append(x);
        return sb.toString();
    }

    static float[] floats(String s) {
        if (s == null || s.equals("-") || s.isEmpty()) return null;
        String[] p = s.split(";");
        float[] v = new float[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Float.parseFloat(p[i]);
        return v;
    }

    static int[] ints(String s) {
        if (s == null || s.equals("-") || s.isEmpty()) return null;
        String[] p = s.split(";");
        int[] v = new int[p.length];
        for (int i = 0; i < p.length; i++) v[i] = Integer.parseInt(p[i]);
        return v;
    }
    static final String SELF = "self";

    static byte[] msg(Object... parts) {
        StringBuilder sb = new StringBuilder(MAGIC);
        for (Object p : parts) sb.append('|').append(p);
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Returns the fields after the magic, or null if this is not our packet. */
    static String[] parse(DatagramPacket p) {
        String s = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.US_ASCII);
        String[] f = s.split("\\|", -1);
        if (f.length < 2 || !MAGIC.equals(f[0])) return null;
        String[] out = new String[f.length - 1];
        System.arraycopy(f, 1, out, 0, out.length);
        return out;
    }

    /** Broadcast addresses of all active IPv4 interfaces (Wi-Fi, hotspot, ...). */
    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                        out.add(ia.getBroadcast());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Human-readable list of local IPv4 addresses, e.g. "192.168.43.1 (wlan1)". */
    static String localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                        out.add(ia.getAddress().getHostAddress() + " (" + ni.getName() + ")");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out.isEmpty() ? "no network - enable the hotspot" : String.join(", ", out);
    }

    /** The Wi-Fi network (may have no internet, e.g. a phone hotspot). */
    @SuppressWarnings("deprecation")
    static Network wifiNetwork(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** Default gateway of the Wi-Fi network = the hotspot phone. */
    static InetAddress wifiGateway(Context ctx, Network n) {
        if (n == null) return null;
        LinkProperties lp = ctx.getSystemService(ConnectivityManager.class).getLinkProperties(n);
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway();
        }
        return null;
    }

    /** Stable per-device id and a short display name such as "Pixel 7a #3F2A". */
    static String deviceId(Context ctx) {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return id == null ? "unknown" : id;
    }

    static String deviceName(Context ctx) {
        String id = deviceId(ctx);
        String tail = id.length() >= 4 ? id.substring(id.length() - 4) : id;
        return Build.MODEL + " #" + tail.toUpperCase(Locale.US);
    }
}
