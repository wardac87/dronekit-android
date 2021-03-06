package com.o3dr.services.android.lib.drone.companion.solo.action;

/**
 * Created by Fredia Huya-Kouadio on 7/31/15.
 */
public class SoloCameraActions {

    //Private to prevent instantiation
    private SoloCameraActions() {
    }

    private static final String PACKAGE_NAME = "com.o3dr.services.android.lib.drone.companion.solo.action.camera";

    public static final String ACTION_START_VIDEO_STREAM = PACKAGE_NAME + ".START_VIDEO_STREAM";
    public static final String EXTRA_VIDEO_DISPLAY = "extra_video_display";
    public static final String EXTRA_VIDEO_TAG = "extra_video_tag";

    public static final String ACTION_STOP_VIDEO_STREAM = PACKAGE_NAME + ".STOP_VIDEO_STREAM";
}
