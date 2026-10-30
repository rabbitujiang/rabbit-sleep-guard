package com.rabbit.sleepguard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;

/** Read-only capability diagnostic. Never substitutes a probe value for screen time. */
public final class NativeUsageProbe {
    private static final String AUTHORITY = "com.vivo.familycare.local.TimeManagerAppProvider";
    public static JSONObject inspect(Context context) throws Exception {
        JSONObject out = new JSONObject().put("app_version", BuildConfig.VERSION_NAME)
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("authority", AUTHORITY);
        PackageManager pm = context.getPackageManager();
        ProviderInfo info = pm.resolveContentProvider(AUTHORITY, 0);
        if (info == null) return out.put("status", "provider_not_found");
        // Only inspect the expected vendor package, never a lookalike provider.
        if (!"com.vivo.familycare.local".equals(info.packageName))
            return out.put("status", "unexpected_package");
        out.put("vendor_version", pm.getPackageInfo(info.packageName, 0).versionName)
                .put("exported", info.exported).put("enabled", info.enabled);
        if (!info.exported || !info.enabled) return out.put("status", "provider_unavailable");
        String permission = info.readPermission;
        if (permission != null) {
            out.put("read_permission", permission);
            try {
                PermissionInfo p = pm.getPermissionInfo(permission, 0);
                out.put("protection_level", p.protectionLevel);
            } catch (PackageManager.NameNotFoundException ignored) { }
            boolean granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            out.put("permission_granted", granted);
            if (!granted) return out.put("status", "permission_denied");
        }
        try {
            Bundle support = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY),
                    "support_provider", null, null);
            if (support == null || !support.getBoolean("is_support", false))
                return out.put("status", "unsupported_protocol");
            // Only report capability: type/date/units must be verified before reading totals.
            return out.put("status", "protocol_available");
        } catch (SecurityException error) {
            return out.put("status", "permission_denied");
        } catch (RuntimeException error) {
            return out.put("status", "probe_failed");
        }
    }
}
