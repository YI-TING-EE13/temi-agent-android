package com.robotemi.agent;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SystemUiSafeAreaPolicyTest {
    @Test
    public void reservesProportionalLauncherAreaAcrossWindowHeights() {
        assertEquals(84, SystemUiSafeAreaPolicy.resolveBottomInset(
                0, 0, 1200, 1f));
        assertEquals(56, SystemUiSafeAreaPolicy.resolveBottomInset(
                0, 0, 800, 1f));
    }

    @Test
    public void preservesMinimumTouchTargetAcrossDensities() {
        assertEquals(96, SystemUiSafeAreaPolicy.resolveBottomInset(
                0, 0, 600, 2f));
        assertEquals(48, SystemUiSafeAreaPolicy.resolveBottomInset(
                0, 0, 0, 0f));
    }

    @Test
    public void neverShrinksReportedSystemOrCutoutInsets() {
        assertEquals(120, SystemUiSafeAreaPolicy.resolveBottomInset(
                120, 0, 1200, 1f));
        assertEquals(100, SystemUiSafeAreaPolicy.resolveBottomInset(
                0, 100, 1200, 1f));
    }

    @Test
    public void reservesDensityAwareTrailingLauncherControls() {
        assertEquals(48, SystemUiSafeAreaPolicy.resolveTrailingInset(
                0, 0, 1f));
        assertEquals(96, SystemUiSafeAreaPolicy.resolveTrailingInset(
                0, 0, 2f));
        assertEquals(120, SystemUiSafeAreaPolicy.resolveTrailingInset(
                120, 0, 2f));
    }
}
