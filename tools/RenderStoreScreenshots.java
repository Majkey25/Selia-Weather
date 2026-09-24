import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Composes store posters from unedited Android captures. Uses only the JDK. */
public final class RenderStoreScreenshots {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final Color BACKGROUND = new Color(0xF7F5F1);
    private static final Color TEXT = new Color(0x111310);
    private static final Color SECONDARY = new Color(0x5F6560);
    private static final Path CAPTURES = Path.of("docs/store/captures/2026-09-24");
    private static final Path OUTPUT = Path.of("fastlane/metadata/android");
    private static final Path REVIEW = Path.of("docs/store/2026-09-24");
    private static final Slide[] SLIDES = {
        new Slide("01-weather.png", "Weather for your day",
            "Forecasts, current conditions and the days ahead.",
            "Počasí pro váš den", "Aktuální počasí i předpověď na další dny."),
        new Slide("02-widget.png", "Your own weather widget",
            "Choose the colours, layout and details you need.",
            "Widget podle vás", "Vyberte barvy, rozložení a údaje, které potřebujete."),
        new Slide("03-forecast-map.png", "See the rain ahead",
            "A model forecast map, separate from observed radar.",
            "Srážky v příštích hodinách", "Modelová předpověď oddělená od pozorovaného radaru."),
        new Slide("04-hourly.png", "Every hour, explained",
            "Rain chances, feels-like temperature, wind and more.",
            "Každá hodina přehledně", "Pravděpodobnost deště, pocitová teplota, vítr a další."),
        new Slide("05-history.png", "Explore five years",
            "Daily weather estimates. Your place, your date range.",
            "Prozkoumejte pět let", "Denní odhady počasí. Vaše místo, vaše období."),
        new Slide("06-ask-ai.png", "Ask AI with your data",
            "Share a weather CSV with a compatible AI app.",
            "Zeptejte se AI s daty", "Sdílejte CSV s počasím do kompatibilní AI aplikace."),
    };

    private RenderStoreScreenshots() {}

    public static void main(String[] args) throws IOException {
        if (args.length > 1 || (args.length == 1 && !List.of("en-US", "cs-CZ", "--check-copy").contains(args[0]))) {
            throw new IllegalArgumentException("Usage: java -Dfile.encoding=UTF-8 tools/RenderStoreScreenshots.java [en-US|cs-CZ|--check-copy]");
        }
        BufferedImage icon = read(Path.of("docs/assets/app-icon.png"));
        if (icon.getWidth() != 512 || icon.getHeight() != 512) throw new IOException("Store icon must be 512x512");
        if (args.length == 1 && args[0].equals("--check-copy")) {
            for (String locale : List.of("en-US", "cs-CZ")) {
                for (Slide slide : SLIDES) {
                    Graphics2D graphics = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB).createGraphics();
                    try { drawCopy(graphics, slide, icon, locale); } finally { graphics.dispose(); }
                }
            }
            System.out.println("All 12 headlines and subtitles fit their reserved space.");
            return;
        }
        String locale = args.length == 0 ? "en-US" : args[0];
        Path imageDir = OUTPUT.resolve(locale).resolve("images");
        List<BufferedImage> rendered = new ArrayList<>();
        for (Slide slide : SLIDES) {
            BufferedImage capture = read(CAPTURES.resolve(locale).resolve(slide.file));
            if (capture.getWidth() < 720 || capture.getHeight() < capture.getWidth() * 1.5) {
                throw new IOException("Expected a portrait Android capture: " + slide.file);
            }
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                configure(graphics);
                graphics.setColor(BACKGROUND);
                graphics.fillRect(0, 0, WIDTH, HEIGHT);
                drawCopy(graphics, slide, icon, locale);
                drawPhone(graphics, capture);
            } finally { graphics.dispose(); }
            write(image, imageDir.resolve("phoneScreenshots").resolve(slide.file));
            rendered.add(image);
        }
        writeFeatureGraphic(locale, icon, imageDir);
        writeContactSheet(rendered, REVIEW.resolve(locale).resolve("contact-sheet.png"));
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static void drawCopy(Graphics2D graphics, Slide slide, BufferedImage icon, String locale) {
        configure(graphics);
        graphics.drawImage(icon, 76, 62, 50, 50, null);
        graphics.setColor(TEXT);
        graphics.setFont(new Font("Segoe UI", Font.BOLD, 26));
        graphics.drawString("SELIA WEATHER", 144, 97);
        graphics.setFont(new Font("Segoe UI", Font.BOLD, 68));
        drawLine(graphics, locale.equals("cs-CZ") ? slide.csTitle : slide.enTitle, 76, 214, 928);
        graphics.setColor(SECONDARY);
        graphics.setFont(new Font("Segoe UI", Font.PLAIN, 30));
        drawLine(graphics, locale.equals("cs-CZ") ? slide.csSubtitle : slide.enSubtitle, 78, 273, 924);
    }

    private static void drawPhone(Graphics2D graphics, BufferedImage capture) {
        double scale = Math.min(756.0 / capture.getWidth(), 1478.0 / capture.getHeight());
        int width = (int) Math.round(capture.getWidth() * scale);
        int height = (int) Math.round(capture.getHeight() * scale);
        int x = (WIDTH - width) / 2;
        graphics.setColor(Color.BLACK);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.08f));
        graphics.fill(new RoundRectangle2D.Double(x - 12, 356, width + 40, height + 52, 76, 76));
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(0x050605));
        graphics.fill(new RoundRectangle2D.Double(x - 20, 340, width + 40, height + 52, 76, 76));
        graphics.setStroke(new BasicStroke(3f));
        graphics.setColor(new Color(0x282A28));
        graphics.draw(new RoundRectangle2D.Double(x - 20, 340, width + 40, height + 52, 76, 76));
        Shape oldClip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x, 366, width, height, 40, 40));
        graphics.drawImage(capture, x, 366, width, height, null);
        graphics.setClip(oldClip);
        graphics.setColor(new Color(0x383A38));
        graphics.fillRoundRect(480, 349, 120, 6, 6, 6);
    }

    private static void writeFeatureGraphic(String locale, BufferedImage icon, Path imageDir) throws IOException {
        BufferedImage feature = new BufferedImage(1024, 500, BufferedImage.TYPE_INT_RGB);
        BufferedImage capture = read(CAPTURES.resolve(locale).resolve(SLIDES[0].file));
        Graphics2D graphics = feature.createGraphics();
        try {
            configure(graphics);
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, 1024, 500);
            graphics.drawImage(icon, 58, 48, 56, 56, null);
            graphics.setColor(TEXT);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 30));
            graphics.drawString("SELIA WEATHER", 130, 87);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 60));
            drawLine(graphics, locale.equals("cs-CZ") ? "Váš den." : "Your day.", 58, 222, 530);
            drawLine(graphics, locale.equals("cs-CZ") ? "Vaše počasí." : "Your weather.", 58, 296, 530);
            graphics.setColor(SECONDARY);
            graphics.setFont(new Font("Segoe UI", Font.PLAIN, 27));
            drawLine(graphics, locale.equals("cs-CZ") ? "Předpověď, mapy a vlastní widget." : "Forecasts, maps and your own widget.", 60, 360, 530);
            graphics.setColor(TEXT);
            graphics.fillRoundRect(620, 38, 342, 720, 44, 44);
            graphics.setClip(new RoundRectangle2D.Double(632, 52, 318, 690, 28, 28));
            graphics.drawImage(capture, 632, 52, 318, (int) Math.round(capture.getHeight() * 318.0 / capture.getWidth()), null);
        } finally { graphics.dispose(); }
        write(feature, imageDir.resolve("featureGraphic.png"));
    }

    private static void drawLine(Graphics2D graphics, String text, int x, int baseline, int width) {
        if (graphics.getFontMetrics().stringWidth(text) > width) throw new IllegalArgumentException("Copy is too wide: " + text);
        graphics.drawString(text, x, baseline);
    }

    private static BufferedImage read(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Unreadable image: " + path);
        return image;
    }

    private static void write(BufferedImage image, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG writer unavailable");
        BufferedImage check = read(path);
        if (check.getWidth() != image.getWidth() || check.getHeight() != image.getHeight()) {
            throw new IOException("Output dimensions differ: " + path);
        }
        System.out.println(path.toAbsolutePath());
    }

    private static void writeContactSheet(List<BufferedImage> images, Path path) throws IOException {
        BufferedImage sheet = new BufferedImage(1080, 1280, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            configure(graphics);
            for (int index = 0; index < images.size(); index++) {
                graphics.drawImage(images.get(index), (index % 3) * 360, (index / 3) * 640, 360, 640, null);
            }
        } finally { graphics.dispose(); }
        write(sheet, path);
    }

    private record Slide(String file, String enTitle, String enSubtitle, String csTitle, String csSubtitle) {}
}
