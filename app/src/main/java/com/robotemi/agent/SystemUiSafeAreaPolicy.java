package com.robotemi.agent;

final class SystemUiSafeAreaPolicy {
    private static final float MINIMUM_TOUCH_TARGET_DP = 48f;
    private static final float TEMI_LAUNCHER_BOTTOM_FRACTION = 0.07f;

    private SystemUiSafeAreaPolicy() {
    }

    static int resolveBottomInset(
            int systemBarBottom,
            int displayCutoutBottom,
            int windowHeight,
            float density) {
        int minimumTouchTarget = minimumTouchTarget(density);
        int proportionalLauncherArea = Math.round(
                Math.max(0, windowHeight)
                        * TEMI_LAUNCHER_BOTTOM_FRACTION);
        int launcherControls = Math.max(
                minimumTouchTarget, proportionalLauncherArea);
        return Math.max(
                Math.max(systemBarBottom, displayCutoutBottom),
                launcherControls);
    }

    static int resolveTrailingInset(
            int systemBarTrailing,
            int displayCutoutTrailing,
            float density) {
        return Math.max(
                Math.max(systemBarTrailing, displayCutoutTrailing),
                minimumTouchTarget(density));
    }

    private static int minimumTouchTarget(float density) {
        float safeDensity = density > 0f ? density : 1f;
        return Math.round(MINIMUM_TOUCH_TARGET_DP * safeDensity);
    }
}
