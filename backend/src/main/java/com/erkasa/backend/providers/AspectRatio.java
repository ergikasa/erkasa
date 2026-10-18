package com.erkasa.backend.providers;

/** Video / image shape. Reels and TikTok use PORTRAIT, YouTube uses LANDSCAPE. */
public enum AspectRatio {
    PORTRAIT("9:16", 720, 1280),
    LANDSCAPE("16:9", 1280, 720),
    SQUARE("1:1", 1024, 1024);

    private final String ratio;
    private final int mockWidth;
    private final int mockHeight;

    AspectRatio(String ratio, int mockWidth, int mockHeight) {
        this.ratio = ratio;
        this.mockWidth = mockWidth;
        this.mockHeight = mockHeight;
    }

    /** "9:16", "16:9", "1:1": the form most image APIs expect. */
    public String ratio() {
        return ratio;
    }

    public int mockWidth() {
        return mockWidth;
    }

    public int mockHeight() {
        return mockHeight;
    }
}