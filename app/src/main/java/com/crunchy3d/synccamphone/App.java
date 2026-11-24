package com.crunchy3d.synccamphone;

/**
 * Name and version shown to the user and written into every file (2026-11-22: CamSyncProbe ->
 * SyncCamPhone, versions from V0.100; +1 for new work, not for corrections). App ID since V0.102:
 * com.crunchy3d.synccamphone (before: com.crunchy3d.camsyncprobe - a separate app on the phones).
 * Keep VERSION equal to versionName in app/build.gradle.
 */
final class App {
    private App() {}

    static final String NAME = "SyncCamPhone";
    static final String VERSION = "V0.105";
    static final String TITLE = NAME + " " + VERSION;
    /** Files go to Download/SyncCamPhone; the old folder is still read for the file numbering. */
    static final String FOLDER = NAME, OLD_FOLDER = "CamSyncProbe";
}
