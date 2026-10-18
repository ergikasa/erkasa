package com.erkasa.backend.providers.image;

/** Generates one image per scene. Implementations: MockImageProvider now, fal.ai in 3.3. */
public interface ImageProvider {

    String name();

    String model();

    GeneratedImage generate(ImageRequest request);
}