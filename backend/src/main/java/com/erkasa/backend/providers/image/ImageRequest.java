package com.erkasa.backend.providers.image;

import com.erkasa.backend.providers.AspectRatio;

/**
 * @param prompt      what the image should show (written by the LLM for each scene)
 * @param aspectRatio shape of the video
 */
public record ImageRequest(String prompt, AspectRatio aspectRatio) {
}