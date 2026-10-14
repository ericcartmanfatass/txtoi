package io.github.selectionmenucontrol;

import android.view.Menu;
import android.view.MenuItem;

/** Only standard Android framework menu IDs are handled; no text matching. */
final class BuiltinMenuRules {
    static final int COPY = 1;
    static final int CUT = 1 << 1;
    static final int PASTE = 1 << 2;
    static final int SELECT_ALL = 1 << 3;
    static final int SHARE = 1 << 4;
    static final int PASTE_PLAIN = 1 << 5;
    static final int UNDO = 1 << 6;
    static final int REDO = 1 << 7;
    static final int ALL = 255;

    private BuiltinMenuRules() { }

    static int flagFor(int id) {
        switch (id) {
            case android.R.id.copy: return COPY;
            case android.R.id.cut: return CUT;
            case android.R.id.paste: return PASTE;
            case android.R.id.selectAll: return SELECT_ALL;
            case android.R.id.shareText: return SHARE;
            case android.R.id.pasteAsPlainText: return PASTE_PLAIN;
            case android.R.id.undo: return UNDO;
            case android.R.id.redo: return REDO;
            default: return 0;
        }
    }

    static boolean hide(Menu menu, int mask) {
        if (menu == null || mask == 0) return false;
        boolean changed = false;
        for (int index = 0; index < menu.size(); index++) {
            MenuItem item = menu.getItem(index);
            int flag = flagFor(item.getItemId());
            if (flag != 0 && (mask & flag) != 0 && item.isVisible()) {
                item.setVisible(false);
                changed = true;
            }
            if (item.hasSubMenu()) changed |= hide(item.getSubMenu(), mask);
        }
        return changed;
    }
}
