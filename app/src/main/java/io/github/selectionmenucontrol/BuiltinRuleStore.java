package io.github.selectionmenucontrol;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

/** Separate settings key preserves upstream v3 PROCESS_TEXT rules. */
final class BuiltinRuleStore {
    static final String KEY = "selection_menu_control_builtin_v1";
    private static final String TAG = "SelectionMenuControl";

    private BuiltinRuleStore() { }

    static int parse(String stored) {
        if (stored == null || !stored.matches("v1:[0-9]{1,3}")) return 0;
        try {
            int value = Integer.parseInt(stored.substring(3));
            return value >= 0 && value <= BuiltinMenuRules.ALL ? value : 0;
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    static int read(Context context) {
        if (context == null) return 0;
        try {
            return parse(Settings.Global.getString(context.getContentResolver(), KEY));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read built-in menu settings; preserving stock menu", error);
            return 0;
        }
    }

    static boolean save(int flags) {
        if ((flags & ~BuiltinMenuRules.ALL) != 0 || flags < 0) return false;
        return RootAccess.putGlobalSettingVerified(KEY, "v1:" + flags);
    }
}
