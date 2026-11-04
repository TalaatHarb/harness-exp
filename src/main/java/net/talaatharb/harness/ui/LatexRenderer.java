package net.talaatharb.harness.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;

/** Renders LaTeX math locally with JLaTeXMath; invalid TeX falls back to the source text. */
final class LatexRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(LatexRenderer.class);
    static final float INLINE_FONT_SIZE = 14f;
    static final float DISPLAY_FONT_SIZE = 17f;
    /** Rendered at 2x and shown at half size so formulas stay sharp on HiDPI screens. */
    private static final int SCALE = 2;
    static final int MAX_TEX_CHARS = 4_000;

    private LatexRenderer() {
    }

    static Node render(String tex, boolean display) {
        try {
            if (tex.length() > MAX_TEX_CHARS) {
                throw new IllegalArgumentException("formula too long");
            }
            TeXFormula formula = new TeXFormula(tex);
            TeXIcon icon = formula.createTeXIcon(display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT,
                    (display ? DISPLAY_FONT_SIZE : INLINE_FONT_SIZE) * SCALE);
            icon.setInsets(new Insets(2 * SCALE, 1, 2 * SCALE, 1));
            int width = Math.max(1, icon.getIconWidth());
            int height = Math.max(1, icon.getIconHeight());
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                icon.setForeground(new Color(0x11, 0x18, 0x27));
                icon.paintIcon(null, g, 0, 0);
            } finally {
                g.dispose();
            }
            WritableImage fx = new WritableImage(width, height);
            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            fx.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            ImageView view = new ImageView(fx);
            double shownHeight = (double) height / SCALE;
            view.setFitWidth((double) width / SCALE);
            view.setFitHeight(shownHeight);
            view.setSmooth(true);
            // Sit on the text baseline like a glyph instead of on the image's bottom edge.
            double baseline = icon.getBaseLine() * shownHeight;
            StackPane holder = new StackPane(view) {
                @Override
                public double getBaselineOffset() {
                    return baseline;
                }
            };
            holder.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
            holder.getStyleClass().add(display ? "math-display" : "math-inline");
            Tooltip.install(holder, new Tooltip(tex));
            return holder;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Could not render math '{}': {}", tex, e.toString());
            Label fallback = new Label(display ? "$$" + tex + "$$" : "$" + tex + "$");
            fallback.getStyleClass().addAll("mono", "math-error");
            fallback.setTooltip(new Tooltip("Could not render this formula: " + e.getMessage()));
            return fallback;
        }
    }
}
