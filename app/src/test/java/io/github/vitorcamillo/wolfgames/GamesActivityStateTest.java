package io.github.vitorcamillo.wolfgames;

import org.junit.Test;
import static org.junit.Assert.*;

public class GamesActivityStateTest {
    @Test public void onStartKeepsFallbackStatusAfterReturningToTheApp() {
        assertFalse(GamesActivity.shouldHideStatusOnStart(true));
        assertTrue(GamesActivity.shouldHideStatusOnStart(false));
    }
}
