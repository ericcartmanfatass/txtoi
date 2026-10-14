package io.github.selectionmenucontrol;

import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative HyperOS PROCESS_TEXT classifier.
 * PM-level movable does not imply the final FloatingToolbar preserves ordering.
 * Avoid ColorOS-only extension APIs on Xiaomi ROMs.
 */
final class HyperOsMenuClassifier {
    private HyperOsMenuClassifier() { }

    static SystemMenuClassifier.Result evaluate(List<ResolveInfo> originals) {
        Map<ResolveInfo, Integer> classifications = new IdentityHashMap<>();
        List<ResolveInfo> displayOrder = new ArrayList<>(originals);
        for (ResolveInfo info : originals) {
            int status = info != null && info.activityInfo != null
                    ? SystemMenuClassifier.ORDINARY : SystemMenuClassifier.UNKNOWN;
            classifications.put(info, status);
        }
        return new SystemMenuClassifier.Result(displayOrder, classifications);
    }
}
