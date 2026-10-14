package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class BuiltinRuleStoreTest {
    @Test public void malformedOrMissingRulesAreSafeDefaults() {
        assertEquals(0, BuiltinRuleStore.parse(null));
        assertEquals(0, BuiltinRuleStore.parse(""));
        assertEquals(0, BuiltinRuleStore.parse("v1:999"));
        assertEquals(0, BuiltinRuleStore.parse("v2:1"));
        assertEquals(0, BuiltinRuleStore.parse("v1:-1"));
        assertEquals(0, BuiltinRuleStore.parse("v1:foo"));
    }
    @Test public void bitmasksRoundTrip() {
        assertEquals(0, BuiltinRuleStore.parse("v1:0"));
        assertEquals(255, BuiltinRuleStore.parse("v1:255"));
        assertEquals(16, BuiltinRuleStore.parse("v1:16"));
    }
    @Test public void builtinIdsAreStrictlyRecognized() {
        assertEquals(BuiltinMenuRules.COPY, BuiltinMenuRules.flagFor(android.R.id.copy));
        assertEquals(BuiltinMenuRules.SHARE, BuiltinMenuRules.flagFor(android.R.id.shareText));
        assertEquals(0, BuiltinMenuRules.flagFor(0));
    }
}
