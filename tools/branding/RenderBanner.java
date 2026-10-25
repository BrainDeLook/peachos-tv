import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Renders the static TV banner from the project's own mark and bundled Inter font. */
public class RenderBanner {
    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x10131b)); g.fillRect(0, 0, 320, 180);
        g.setPaint(new GradientPaint(123, 25, new Color(0xffbb93), 197, 99, new Color(0xf17c88)));
        g.fillRoundRect(123, 25, 74, 74, 28, 28);
        g.setColor(new Color(0x171a25)); g.fillRoundRect(132, 44, 56, 38, 12, 12);
        g.setColor(new Color(0xffbd99)); g.fillRoundRect(138, 51, 11, 16, 4, 4);
        g.setColor(new Color(0xf69da1)); g.fillRoundRect(154, 51, 11, 16, 4, 4);
        g.setColor(new Color(0xc5b9e7)); g.fillRoundRect(170, 51, 12, 16, 4, 4);
        g.setColor(new Color(255, 255, 255, 204)); g.fillRoundRect(145, 73, 30, 4, 4, 4);
        Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[0]).toFile()).deriveFont(27f);
        var glyphs = font.createGlyphVector(new FontRenderContext(null, true, true), "PeachOS TV");
        var bounds = glyphs.getVisualBounds();
        var transform = AffineTransform.getTranslateInstance((320 - bounds.getWidth()) / 2 - bounds.getX(), 146);
        g.setColor(new Color(0xf6f5f8)); g.fill(transform.createTransformedShape(glyphs.getOutline()));
        g.dispose();
        java.nio.file.Files.createDirectories(Path.of(args[1]).toAbsolutePath().getParent());
        ImageIO.write(image, "png", Path.of(args[1]).toFile());
    }
}
