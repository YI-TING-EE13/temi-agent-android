package com.robotemi.agent;

final class SystemUiSafeAreaPolicy {
    private static final float MINIMUM_TOUCH_TARGET_DP = 48f;
    private static final float TEMI_LAUNCHER_TOP_FRACTION = 0.09f;
    private static final float TEMI_TOP_OVERLAY_MINIMUM_DP = 72f;
    private static final float TEMI_LAUNCHER_BOTTOM_FRACTION = 0.07f;

    private SystemUiSafeAreaPolicy() {
    }

    static int resolveTopInset(
            int systemBarTop,
            int displayCutoutTop,
            int windowHeight,
            float density) {
        int proportionalLauncherArea = Math.round(
                Math.max(0, windowHeight)
                        * TEMI_LAUNCHER_TOP_FRACTION);
        int minimumOverlayArea = Math.round(
                TEMI_TOP_OVERLAY_MINIMUM_DP * safeDensity(density));
        int topSafeArea = Math.max(proportionalLauncherArea, minimumOverlayArea);
        return Math.max(
                Math.max(systemBarTop, displayCutoutTop),
                topSafeArea);
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
        return Math.round(MINIMUM_TOUCH_TARGET_DP * safeDensity(density));
    }

    private static float safeDensity(float density) {
        return density > 0f ? density : 1f;
    }
}
