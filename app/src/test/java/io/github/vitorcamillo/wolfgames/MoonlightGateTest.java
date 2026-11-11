package io.github.vitorcamillo.wolfgames;

import android.content.pm.PackageManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MoonlightGateTest {
    @Test public void targetStateFollowsMoonlightPresence() {
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,MoonlightGate.targetState(true));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,MoonlightGate.targetState(false));
    }

    @Test public void knowsMoonlightAndItsVPlusFork() {
        org.junit.Assert.assertTrue(MoonlightGate.isMoonlight("com.limelight"));
        org.junit.Assert.assertTrue(MoonlightGate.isMoonlight("com.limelight.qiin"));
        org.junit.Assert.assertFalse(MoonlightGate.isMoonlight("com.limelight.other"));
        org.junit.Assert.assertFalse(MoonlightGate.isMoonlight(null));
        assertEquals("com.limelight.qiin",MoonlightGate.PACKAGES[0]); // V+ wins when both are installed
    }
}
