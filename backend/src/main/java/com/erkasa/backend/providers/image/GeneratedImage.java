package com.erkasa.backend.providers.image;

import com.erkasa.backend.providers.ProviderUsage;

/**
 * @param data        the image file bytes
 * @param contentType e.g. image/png
 */
public record GeneratedImage(byte[] data, String contentType, int width, int height, ProviderUsage usage) {
}