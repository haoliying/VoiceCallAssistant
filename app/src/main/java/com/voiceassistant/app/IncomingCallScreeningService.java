package com.voiceassistant.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallScreeningService;
import android.app.role.RoleManager;
import android.util.Log;

/**
 * 可选的来电识别通道：成为系统的“来电筛选”应用后，
 * Android 10 以上会在响铃前把来电号码交给我们，即使没有通话记录权限也能拿到号码。
 * 这里只读取号码，然后原样放行通话，不做任何拦截。
 */
public class IncomingCallScreeningService extends CallScreeningService {

    private static final String TAG = "CallScreening";
    private static final long VALID_MS = 60000L;

    private static volatile String pendingNumber;
    private static volatile long capturedAt;

    static String recentNumber() {
        if (System.currentTimeMillis() - capturedAt > VALID_MS) return null;
        return pendingNumber;
    }

    static void clear() {
        pendingNumber = null;
        capturedAt = 0L;
    }

    /** 当前应用是否已经是系统的来电筛选应用。 */
    static boolean isEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            RoleManager roleManager = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
            return roleManager != null
                    && roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
                    && roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
        } catch (Exception e) {
            Log.w(TAG, "isEnabled failed", e);
            return false;
        }
    }

    @Override
    public void onScreenCall(Call.Details callDetails) {
        try {
            if (callDetails != null) {
                Uri handle = callDetails.getHandle();
                if (handle != null) {
                    String number = handle.getSchemeSpecificPart();
                    if (number != null && !number.trim().isEmpty()) {
                        pendingNumber = number.trim();
                        capturedAt = System.currentTimeMillis();
                        Log.i(TAG, "captured caller number from call screening");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onScreenCall failed", e);
        }

        // 一律放行，不拦截任何来电
        respondToCall(callDetails, new CallResponse.Builder().build());
    }
}
