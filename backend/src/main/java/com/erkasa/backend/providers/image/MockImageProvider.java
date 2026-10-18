package com.erkasa.backend.providers.image;


import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.erkasa.backend.providers.ProviderUsage;

/**
 * Free, offline stand-in for a real image model. Active while erkasa.providers.image=mock.
 *
 * Draws a PNG in the requested shape: a colour gradient (the same prompt always gives the
 * same colours) with the prompt written on it, so you can see which scene it belongs to.
 */
@Component
@ConditionalOnProperty(prefix = "erkasa.providers", name = "image", havingValue = "mock", matchIfMissing = true)
public class MockImageProvider implements ImageProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String model() {
        return "mock-image";
    }

    @Override
    public GeneratedImage generate(ImageRequest request) {
        int width = request.aspectRatio().mockWidth();
        int height = request.aspectRatio().mockHeight();
        String prompt = request.prompt() == null ? "" : request.prompt();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            float hue = (prompt.hashCode() & 0xffff) / 65535f;
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.55f, 0.85f),
                    width, height, Color.getHSBColor((hue + 0.15f) % 1f, 0.65f, 0.35f)));
            g.fillRect(0, 0, width, height);

            int margin = width / 12;
            g.setColor(new Color(255, 255, 255, 170));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(18, width / 28)));
            g.drawString("MOCK " + request.aspectRatio().ratio(), margin, margin + width / 28);

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(22, width / 18)));
            drawWrapped(g, prompt, margin, height / 3, width - 2 * margin);
        } finally {
            g.dispose();
        }

        byte[] png = toPng(image);
        return new GeneratedImage(png, "image/png", width, height, ProviderUsage.free(model(), "images", 0, 1));
    }

    /** Writes text on several lines so it fits in maxWidth. */
    private static void drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        int lineHeight = fm.getHeight();
        for (int i = 0; i < lines.size() && i < 12; i++) {
            g.drawString(lines.get(i), x, y + i * lineHeight);
        }
    }

    private static byte[] toPng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}