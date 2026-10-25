package com.erkasa.backend.jobs;

import java.util.Objects;

/**
 * How a video should be made: the choices from the "New video" wizard (phase 9.1).
 * Stored as columns of projects (see V11__project_options.sql).
 *
 * @param voiceSpeed percent of normal speed: 100 = normal, 90 = slower, 110 = faster (70 to 120)
 */
public record VideoOptions(Purpose purpose,
                           Template template,
                           Audience audience,
                           Tone tone,
                           Theme theme,
                           int voiceSpeed,
                           CaptionStyle captionStyle,
                           CaptionPosition captionPosition) {

    public static final int MIN_VOICE_SPEED = 70;
    public static final int MAX_VOICE_SPEED = 120;
    public static final int NORMAL_VOICE_SPEED = 100;

    public VideoOptions {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(tone, "tone");
        Objects.requireNonNull(theme, "theme");
        Objects.requireNonNull(captionStyle, "captionStyle");
        Objects.requireNonNull(captionPosition, "captionPosition");
        if (voiceSpeed < MIN_VOICE_SPEED || voiceSpeed > MAX_VOICE_SPEED) {
            throw new IllegalArgumentException("voiceSpeed must be " + MIN_VOICE_SPEED + "-" + MAX_VOICE_SPEED);
        }
    }

    /** How every video was made before phase 9 (the defaults of the columns in V11). */
    public static VideoOptions classic() {
        return new VideoOptions(Purpose.EXPLAINER, Template.CLASSIC, Audience.BEGINNER, Tone.EDUCATIONAL,
                Theme.DARK, NORMAL_VOICE_SPEED, CaptionStyle.KARAOKE, CaptionPosition.BOTTOM);
    }

    /**
     * Fills what the user did not choose. Template and tone follow the purpose
     * (BUSINESS -> INFOGRAPHIC + PROFESSIONAL), the rest have fixed defaults.
     */
    public static VideoOptions withDefaults(Purpose purpose, Template template, Audience audience, Tone tone,
                                            Theme theme, Integer voiceSpeed,
                                            CaptionStyle captionStyle, CaptionPosition captionPosition) {
        Purpose p = purpose == null ? Purpose.EXPLAINER : purpose;
        return new VideoOptions(p,
                template == null ? p.defaultTemplate() : template,
                audience == null ? Audience.BEGINNER : audience,
                tone == null ? p.defaultTone() : tone,
                theme == null ? Theme.DARK : theme,
                voiceSpeed == null ? NORMAL_VOICE_SPEED : voiceSpeed,
                captionStyle == null ? CaptionStyle.KARAOKE : captionStyle,
                captionPosition == null ? CaptionPosition.BOTTOM : captionPosition);
    }

    /** The same choices with another template (used while a template is not ready yet, see SceneCatalog). */
    public VideoOptions withTemplate(Template other) {
        return new VideoOptions(purpose, other, audience, tone, theme, voiceSpeed, captionStyle, captionPosition);
    }

    /** What the user wants to make (the cards in step 1 of the wizard). */
    public enum Purpose {
        EXPLAINER(Template.ANIMATED_2D, Tone.EDUCATIONAL),
        SHORT_FORM(Template.ANIMATED_2D, Tone.ENTERTAINING),
        BUSINESS(Template.INFOGRAPHIC, Tone.PROFESSIONAL);

        private final Template defaultTemplate;
        private final Tone defaultTone;

        Purpose(Template defaultTemplate, Tone defaultTone) {
            this.defaultTemplate = defaultTemplate;
            this.defaultTone = defaultTone;
        }

        public Template defaultTemplate() {
            return defaultTemplate;
        }

        public Tone defaultTone() {
            return defaultTone;
        }
    }

    /** The scene system that draws the video (Remotion). CLASSIC = images + motion graphics. */
    public enum Template {
        ANIMATED_2D, INFOGRAPHIC, CLASSIC
    }

    public enum Audience {
        BEGINNER, INTERMEDIATE, EXPERT
    }

    public enum Tone {
        EDUCATIONAL, ENTERTAINING, PROFESSIONAL
    }

    /** Ready-made colour themes until branding (logo, colours, font) comes after deploy. */
    public enum Theme {
        DARK, LIGHT, VIBRANT
    }

    public enum CaptionStyle {
        KARAOKE, CLEAN, BOLD, NONE
    }

    public enum CaptionPosition {
        BOTTOM, CENTER, TOP
    }
}
