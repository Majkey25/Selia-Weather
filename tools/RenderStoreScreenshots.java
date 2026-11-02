import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
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
    private static final Color TEXT = new Color(0x111310);
    private static final Color SECONDARY = new Color(0x5F6560);
    private static final Path CAPTURES = Path.of("docs/store/captures/2026-11-02");
    private static final Path OUTPUT = Path.of("fastlane/metadata/android");
    private static final Path REVIEW = Path.of("docs/store/2026-11-02");
    private static final Slide[] SLIDES = {
        new Slide("01-weather.png", "Your weather.\nAt a glance.",
            "The forecast, feels-like temperature and the days ahead.",
            "Vaše počasí.\nNa první pohled.", "Předpověď, pocitová teplota a výhled na další dny.", 0xDCEFF8, 0xF7F5F1),
        new Slide("03-forecast-map.png", "Rain on the map.\nSee what's ahead.",
            "Observed rain and model forecasts, clearly separated.",
            "Srážky na mapě.\nS výhledem dopředu.", "Pozorovaný déšť a modelová předpověď, jasně oddělené.", 0xD8ECE7, 0xF6F8F4),
        new Slide("02-widget.png", "Your widget.\nYour way.",
            "Colours, fonts and layouts for your home screen.",
            "Váš widget.\nVáš styl.", "Barvy, písmo a rozložení pro vaši domovskou obrazovku.", 0xF5E4CD, 0xFBF8F1),
        new Slide("04-hourly.png", "Every hour.\nClearly explained.",
            "Rain, wind, UV and the details behind each hour.",
            "Každá hodina.\nSrozumitelně.", "Srážky, vítr, UV a podrobnosti pro každou hodinu.", 0xE0E9F4, 0xF7F8FB),
        new Slide("05-history.png", "Five years.\nOne place.",
            "Explore daily estimates for your chosen place and dates.",
            "Pět let počasí.\nPro vaše místo.", "Prozkoumejte denní odhady počasí pro vybrané období.", 0xE2EDDD, 0xF8FAF4),
        new Slide("06-ask-ai.png", "Your weather data.\nAsk your AI.",
            "Share a weather CSV with a compatible AI app.",
            "Vaše data o počasí.\nZeptejte se své AI.", "Sdílejte CSV s počasím do kompatibilní AI aplikace.", 0xDFEAF2, 0xF8F9FB),
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
                drawBackground(graphics, WIDTH, HEIGHT, slide.start, slide.end);
                drawCopy(graphics, slide, icon, locale);
                drawPhone(graphics, capture);
                if (slide.file.equals("03-forecast-map.png")) {
                    graphics.setColor(SECONDARY);
                    graphics.setFont(new Font("Segoe UI", Font.PLAIN, 17));
                    graphics.drawString("Data Source: Environment and Climate Change Canada.", 76, 1892);
                    graphics.drawString("Map: OpenFreeMap. © OpenMapTiles. © OpenStreetMap contributors.", 76, 1914);
                }
            } finally { graphics.dispose(); }
            write(image, imageDir.resolve("phoneScreenshots").resolve(slide.file));
            rendered.add(image);
        }
        writeFeatureGraphic(locale, icon, imageDir);
        writeSocialGraphic(locale, icon);
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
        graphics.setFont(new Font("Segoe UI", Font.BOLD, 80));
        String[] title = (locale.equals("cs-CZ") ? slide.csTitle : slide.enTitle).split("\n");
        if (title.length != 2) throw new IllegalArgumentException("Expected a two-line title");
        for (int line = 0; line < title.length; line++) drawLine(graphics, title[line], 76, 216 + line * 90, 928);
        graphics.setColor(SECONDARY);
        graphics.setFont(new Font("Segoe UI", Font.PLAIN, 30));
        drawWrapped(graphics, locale.equals("cs-CZ") ? slide.csSubtitle : slide.enSubtitle, 78, 365, 924, 38, 2);
    }

    private static void drawBackground(Graphics2D graphics, int width, int height, int start, int end) {
        graphics.setPaint(new GradientPaint(0, 0, new Color(start), width * 0.3f, height, new Color(end)));
        graphics.fillRect(0, 0, width, height);
    }

    private static void drawPhone(Graphics2D graphics, BufferedImage capture) {
        double scale = Math.min(800.0 / capture.getWidth(), 1400.0 / capture.getHeight());
        int width = (int) Math.round(capture.getWidth() * scale);
        int height = (int) Math.round(capture.getHeight() * scale);
        int x = (WIDTH - width) / 2;
        graphics.setColor(Color.BLACK);
        graphics.setComposite(AlphaComposite.SrcOver.derive(0.08f));
        graphics.fill(new RoundRectangle2D.Double(x - 12, 452, width + 40, height + 48, 68, 68));
        graphics.setComposite(AlphaComposite.SrcOver);
        graphics.setColor(new Color(0x050605));
        graphics.fill(new RoundRectangle2D.Double(x - 16, 440, width + 32, height + 32, 64, 64));
        graphics.setStroke(new BasicStroke(3f));
        graphics.setColor(new Color(0x282A28));
        graphics.draw(new RoundRectangle2D.Double(x - 16, 440, width + 32, height + 32, 64, 64));
        Shape oldClip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x, 456, width, height, 36, 36));
        graphics.drawImage(capture, x, 456, width, height, null);
        graphics.setClip(oldClip);
        graphics.setColor(new Color(0x383A38));
        graphics.fillRoundRect(492, 446, 96, 4, 4, 4);
    }

    private static void writeFeatureGraphic(String locale, BufferedImage icon, Path imageDir) throws IOException {
        BufferedImage feature = new BufferedImage(1024, 500, BufferedImage.TYPE_INT_RGB);
        BufferedImage capture = read(CAPTURES.resolve(locale).resolve(SLIDES[0].file));
        Graphics2D graphics = feature.createGraphics();
        try {
            configure(graphics);
            drawBackground(graphics, 1024, 500, 0xDCEFF8, 0xF7F5F1);
            graphics.drawImage(icon, 58, 48, 56, 56, null);
            graphics.setColor(TEXT);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 30));
            graphics.drawString("SELIA WEATHER", 130, 87);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 60));
            drawLine(graphics, locale.equals("cs-CZ") ? "Vaše počasí." : "Your weather.", 58, 222, 530);
            drawLine(graphics, locale.equals("cs-CZ") ? "Váš styl." : "Your way.", 58, 296, 530);
            graphics.setColor(SECONDARY);
            graphics.setFont(new Font("Segoe UI", Font.PLAIN, 27));
            drawWrapped(graphics, locale.equals("cs-CZ") ? "Předpověď, radar a vlastní widget." : "Forecasts, radar and your own widget.", 60, 360, 510, 34, 2);
            graphics.setColor(TEXT);
            graphics.fillRoundRect(620, 38, 342, 720, 44, 44);
            graphics.setClip(new RoundRectangle2D.Double(632, 52, 318, 690, 28, 28));
            graphics.drawImage(capture, 632, 52, 318, (int) Math.round(capture.getHeight() * 318.0 / capture.getWidth()), null);
        } finally { graphics.dispose(); }
        write(feature, imageDir.resolve("featureGraphic.png"));
    }

    private static void writeSocialGraphic(String locale, BufferedImage icon) throws IOException {
        BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            configure(graphics);
            drawBackground(graphics, 1600, 1200, 0xDCEFF8, 0xF7F5F1);
            graphics.drawImage(icon, 88, 96, 64, 64, null);
            graphics.setColor(TEXT);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 32));
            graphics.drawString("SELIA WEATHER", 174, 140);
            graphics.setFont(new Font("Segoe UI", Font.BOLD, 90));
            drawLine(graphics, locale.equals("cs-CZ") ? "Vaše počasí." : "Your weather.", 88, 330, 660);
            drawLine(graphics, locale.equals("cs-CZ") ? "Váš styl." : "Your way.", 88, 438, 660);
            graphics.setFont(new Font("Segoe UI", Font.PLAIN, 34));
            graphics.setColor(SECONDARY);
            drawWrapped(graphics, locale.equals("cs-CZ")
                ? "Nativní radar, podrobná předpověď a widget podle vás."
                : "Native radar, detailed forecasts and a widget you can make your own.", 90, 540, 620, 48, 3);
            graphics.setFont(new Font("Segoe UI", Font.PLAIN, 28));
            graphics.drawString("Android 10+", 90, 1080);
            graphics.setFont(new Font("Segoe UI", Font.PLAIN, 18));
            graphics.drawString("Data Source: Environment and Climate Change Canada.", 90, 1120);
            graphics.drawString("Map: OpenFreeMap. © OpenMapTiles. © OpenStreetMap contributors.", 90, 1148);
            drawSocialPhone(graphics, read(CAPTURES.resolve(locale).resolve("01-weather.png")), 788, 234, 320);
            drawSocialPhone(graphics, read(CAPTURES.resolve(locale).resolve("03-forecast-map.png")), 1160, 120, 320);
        } finally { graphics.dispose(); }
        write(image, REVIEW.resolve(locale).resolve("social-cover.png"));
    }

    private static void drawSocialPhone(Graphics2D graphics, BufferedImage capture, int x, int y, int width) {
        int height = (int) Math.round(capture.getHeight() * (double) width / capture.getWidth());
        graphics.setColor(new Color(0x111713));
        graphics.fillRoundRect(x - 12, y - 12, width + 24, height + 24, 44, 44);
        Shape clip = graphics.getClip();
        graphics.clip(new RoundRectangle2D.Double(x, y, width, height, 28, 28));
        graphics.drawImage(capture, x, y, width, height, null);
        graphics.setClip(clip);
    }

    private static void drawWrapped(Graphics2D graphics, String text, int x, int baseline, int width, int lineHeight, int maxLines) {
        StringBuilder line = new StringBuilder();
        int count = 1;
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (graphics.getFontMetrics().stringWidth(next) > width && !line.isEmpty()) {
                drawLine(graphics, line.toString(), x, baseline, width);
                baseline += lineHeight;
                if (++count > maxLines) throw new IllegalArgumentException("Copy is too tall: " + text);
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        drawLine(graphics, line.toString(), x, baseline, width);
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

    private record Slide(String file, String enTitle, String enSubtitle, String csTitle, String csSubtitle, int start, int end) {}
}
