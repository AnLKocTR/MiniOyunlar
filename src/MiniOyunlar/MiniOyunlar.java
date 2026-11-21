package MiniOyunlar;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Mini Oyun Kütüphanesi: Java 21 ve Swing ile beş oyun.
 *
 * Yenilikler:
 *  - Animasyonlu ana menü (hareketli arka plan, hover efektli kartlar, rekor kartı)
 *  - Parçacık efektleri (tıklama, patlama, yem yeme, eşleşme, mayın)
 *  - Kalıcı rekor sistemi (kullanıcı klasöründe .mini_oyunlar_rekor.properties)
 *  - İsteğe bağlı resim desteği: "resimler" klasörüne PNG/JPG koyarsan kullanılır.
 *      resimler/arkaplan.png          -> ana menü arka planı
 *      resimler/ikon_click.png        -> Tıklama kartı ikonu
 *      resimler/ikon_bubble.png       -> Balon kartı ikonu
 *      resimler/ikon_snake.png        -> Yılan kartı ikonu
 *      resimler/ikon_memory.png       -> Hafıza kartı ikonu
 *      resimler/ikon_mines.png        -> Mayın kartı ikonu
 *      resimler/ikon_stats.png        -> Rekorlar kartı ikonu
 *    Resim yoksa ikonlar otomatik olarak kodla çizilir.
 */
public class MiniOyunlar extends JFrame {
    static final Color BG = new Color(15, 23, 42), PANEL = new Color(30, 41, 59), TEXT = new Color(241, 245, 249),
            MUTED = new Color(148, 163, 184), CYAN = new Color(34, 211, 238);

    final CardLayout layout = new CardLayout();
    final JPanel root = new JPanel(layout);
    final Fx fx = new Fx();
    HomePanel homePanel;
    boolean enlarged = false;
    SnakeGame snake; MemoryGame memory; MineGame mines; ClickGame click; BubbleGame bubble;

    public MiniOyunlar() {
        super("Mini Oyun Kütüphanesi");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(760, 650));
        root.setBackground(BG);
        homePanel = new HomePanel();
        root.add(homePanel, "home");
        setContentPane(root);
        setGlassPane(fx);
        fx.setVisible(true);
        setSize(900, 720);
        setLocationRelativeTo(null);
        homePanel.run(true);
    }

    // ---------------------------------------------------------------- Yardımcılar
    static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    static String time(int s) { return (s / 60) + ":" + String.format("%02d", s % 60); }
    static String timeOrDash(int s) { return s < 0 ? "–" : time(s); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static JLabel label(String s, int size, Color c, boolean bold) {
        JLabel l = new JLabel(s);
        l.setForeground(c);
        l.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size));
        return l;
    }

    static RoundButton button(String s, Color c, Runnable r) {
        RoundButton b = new RoundButton(s);
        b.setForeground(TEXT);
        b.setBackground(c);
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> r.run());
        return b;
    }

    static void sound(int frequency, int durationMs) {
        Thread audioThread = new Thread(() -> {
            AudioFormat format = new AudioFormat(8000, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                int samples = 8000 * durationMs / 1000;
                byte[] data = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    double envelope = Math.min(1.0, Math.min(i / 120.0, (samples - i) / 250.0));
                    short value = (short) (Math.sin(2 * Math.PI * frequency * i / 8000.0) * 9000 * envelope);
                    data[i * 2] = (byte) value;
                    data[i * 2 + 1] = (byte) (value >> 8);
                }
                line.write(data, 0, data.length);
                line.drain();
            } catch (Exception ignored) { }
        }, "MiniOyunlar-audio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    void popup(Component c, String title, String msg) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(c, msg, title, JOptionPane.INFORMATION_MESSAGE));
    }

    // ---------------------------------------------------------------- Rekorlar
    static final class Rekor {
        static final File FILE = new File(System.getProperty("user.home"), ".mini_oyunlar_rekor.properties");
        static final Properties P = new Properties();
        static {
            try (InputStream in = new FileInputStream(FILE)) { P.load(in); } catch (Exception ignored) { }
        }
        static int get(String key, int def) {
            try { return Integer.parseInt(P.getProperty(key)); } catch (Exception e) { return def; }
        }
        /** Büyük değer daha iyi (puan). Yeni rekor ise true döner. */
        static boolean submitHigh(String key, int value) {
            if (value > get(key, 0)) { P.setProperty(key, String.valueOf(value)); save(); return true; }
            return false;
        }
        /** Küçük değer daha iyi (hamle, süre). Yeni rekor ise true döner. */
        static boolean submitLow(String key, int value) {
            int old = get(key, -1);
            if (old < 0 || value < old) { P.setProperty(key, String.valueOf(value)); save(); return true; }
            return false;
        }
        static void save() {
            try (OutputStream out = new FileOutputStream(FILE)) { P.store(out, "Mini Oyun Rekorlari"); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- Resimler
    static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    static BufferedImage image(String name) {
        if (IMAGES.containsKey(name)) return IMAGES.get(name);
        BufferedImage img = null;
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            File f = new File("resimler", name + ext);
            if (f.isFile()) {
                try { img = ImageIO.read(f); } catch (IOException ignored) { }
                if (img != null) break;
            }
        }
        IMAGES.put(name, img);
        return img;
    }

    /** Kart ikonu: resim varsa onu, yoksa kodla çizilmiş vektör ikonu çizer. */
    static void icon(Graphics2D g, String key, int x, int y, int s, Color accent) {
        BufferedImage img = image("ikon_" + key);
        if (img != null) {
            Shape old = g.getClip();
            g.clip(new RoundRectangle2D.Float(x, y, s, s, s * .3f, s * .3f));
            g.drawImage(img, x, y, s, s, null);
            g.setClip(old);
            return;
        }
        g.setPaint(new GradientPaint(x, y, accent.brighter(), x, y + s, accent.darker()));
        g.fill(new RoundRectangle2D.Float(x, y, s, s, s * .3f, s * .3f));
        float u = s / 100f;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(2f, s * .06f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (key) {
            case "click" -> {
                g.draw(new Ellipse2D.Float(x + 18 * u, y + 18 * u, 64 * u, 64 * u));
                g.draw(new Ellipse2D.Float(x + 32 * u, y + 32 * u, 36 * u, 36 * u));
                g.fill(new Ellipse2D.Float(x + 45 * u, y + 45 * u, 10 * u, 10 * u));
            }
            case "bubble" -> {
                g.setColor(alpha(Color.WHITE, 235));
                g.fill(new Ellipse2D.Float(x + 14 * u, y + 44 * u, 36 * u, 36 * u));
                g.setColor(alpha(Color.WHITE, 190));
                g.fill(new Ellipse2D.Float(x + 44 * u, y + 18 * u, 34 * u, 34 * u));
                g.setColor(alpha(Color.WHITE, 150));
                g.fill(new Ellipse2D.Float(x + 56 * u, y + 52 * u, 28 * u, 28 * u));
            }
            case "snake" -> {
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + 20 * u, y + 74 * u);
                p.curveTo(x + 20 * u, y + 40 * u, x + 50 * u, y + 84 * u, x + 52 * u, y + 48 * u);
                p.curveTo(x + 54 * u, y + 30 * u, x + 76 * u, y + 30 * u, x + 78 * u, y + 44 * u);
                g.setStroke(new BasicStroke(10 * u, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(p);
                g.setColor(accent.darker().darker());
                g.fill(new Ellipse2D.Float(x + 72 * u, y + 38 * u, 6 * u, 6 * u));
            }
            case "memory" -> {
                g.draw(new RoundRectangle2D.Float(x + 14 * u, y + 24 * u, 36 * u, 50 * u, 8 * u, 8 * u));
                g.setColor(alpha(Color.WHITE, 230));
                g.fill(new RoundRectangle2D.Float(x + 50 * u, y + 30 * u, 36 * u, 50 * u, 8 * u, 8 * u));
                g.setColor(Color.WHITE);
                g.setFont(new Font("Segoe UI", Font.BOLD, Math.max(10, (int) (30 * u))));
                g.drawString("?", x + (int) (26 * u), y + (int) (60 * u));
            }
            case "mines" -> {
                g.fill(new Ellipse2D.Float(x + 28 * u, y + 36 * u, 44 * u, 44 * u));
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g.draw(new Line2D.Double(x + 50 * u + Math.cos(a) * 24 * u, y + 58 * u + Math.sin(a) * 24 * u,
                            x + 50 * u + Math.cos(a) * 34 * u, y + 58 * u + Math.sin(a) * 34 * u));
                }
                g.draw(new Line2D.Float(x + 60 * u, y + 40 * u, x + 70 * u, y + 26 * u));
                g.setColor(new Color(250, 204, 21));
                g.fill(new Ellipse2D.Float(x + 66 * u, y + 16 * u, 12 * u, 12 * u));
            }
            default -> { // stats
                g.fill(new RoundRectangle2D.Float(x + 20 * u, y + 56 * u, 16 * u, 26 * u, 5 * u, 5 * u));
                g.fill(new RoundRectangle2D.Float(x + 42 * u, y + 38 * u, 16 * u, 44 * u, 5 * u, 5 * u));
                g.fill(new RoundRectangle2D.Float(x + 64 * u, y + 20 * u, 16 * u, 62 * u, 5 * u, 5 * u));
            }
        }
    }

    // ---------------------------------------------------------------- Yuvarlak buton
    static class RoundButton extends JButton {
        int arc = 14;
        Color edge = null;
        boolean hoverFx = true, hover = false;
        RoundButton(String s) {
            super(s);
            setContentAreaFilled(false);
            setOpaque(false);
            setFocusPainted(false);
            setBorder(new EmptyBorder(9, 15, 9, 15));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            Color c = getBackground();
            if (hover && hoverFx && isEnabled()) c = mix(c, Color.WHITE, .14f);
            if (getModel().isPressed() && hoverFx) c = c.darker();
            g.setColor(c);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (edge != null) { g.setColor(edge); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc); }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    // ---------------------------------------------------------------- Parçacık efekti katmanı (glass pane)
    final class Fx extends JComponent {
        final class Spark { double x, y, vx, vy, size; int life, max; Color c; }
        final ArrayList<Spark> sparks = new ArrayList<>();
        final Random rnd = new Random();
        final Timer timer = new Timer(16, e -> step());

        Fx() { setOpaque(false); }
        @Override public boolean contains(int x, int y) { return false; } // fare olaylarını engelleme

        void burst(Component src, int x, int y, Color c, int n) {
            if (src == null || !src.isShowing()) return;
            Point p = SwingUtilities.convertPoint(src, x, y, this);
            for (int i = 0; i < n; i++) {
                Spark s = new Spark();
                double a = rnd.nextDouble() * Math.PI * 2, v = 1.5 + rnd.nextDouble() * 4.5;
                s.x = p.x; s.y = p.y;
                s.vx = Math.cos(a) * v; s.vy = Math.sin(a) * v - 1;
                s.size = 3 + rnd.nextDouble() * 4;
                s.max = s.life = 24 + rnd.nextInt(22);
                s.c = i % 4 == 0 ? Color.WHITE : c;
                sparks.add(s);
            }
            if (!timer.isRunning()) timer.start();
        }
        void step() {
            for (Iterator<Spark> it = sparks.iterator(); it.hasNext(); ) {
                Spark s = it.next();
                s.x += s.vx; s.y += s.vy; s.vy += .14; s.vx *= .98;
                if (--s.life <= 0) it.remove();
            }
            if (sparks.isEmpty()) timer.stop();
            repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            for (Spark s : sparks) {
                float t = s.life / (float) s.max;
                g.setColor(alpha(s.c, (int) (255 * t)));
                double d = s.size * (.4 + .6 * t);
                g.fill(new Ellipse2D.Double(s.x - d / 2, s.y - d / 2, d, d));
            }
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- Ana menü
    static final class Card extends JComponent {
        final String key, title, desc;
        final Color accent;
        final Supplier<String> footer;
        final Runnable action;
        float hover = 0;
        boolean over = false;

        Card(String key, String title, String desc, Color accent, Supplier<String> footer, Runnable action) {
            this.key = key; this.title = title; this.desc = desc; this.accent = accent; this.footer = footer; this.action = action;
            setPreferredSize(new Dimension(240, 190));
            if (action != null) setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { if (Card.this.action != null) over = true; }
                public void mouseExited(MouseEvent e) { over = false; }
                public void mouseClicked(MouseEvent e) { if (Card.this.action != null) Card.this.action.run(); }
            });
        }
        void tick() {
            float target = over ? 1 : 0;
            hover += (target - hover) * .22f;
            if (Math.abs(target - hover) < .01f) hover = target;
        }
        static ArrayList<String> wrap(String s, FontMetrics fm, int maxW) {
            ArrayList<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String word : s.split(" ")) {
                String t = cur.length() == 0 ? word : cur + " " + word;
                if (fm.stringWidth(t) > maxW && cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(word); }
                else cur = new StringBuilder(t);
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            int lift = -Math.round(hover * 5);
            int x = 6, y = 8 + lift, cw = w - 12, ch = h - 20;
            g.setColor(alpha(accent, (int) (hover * 70)));
            g.fillRoundRect(x - 3, y - 3, cw + 6, ch + 6, 30, 30);
            g.setColor(new Color(0, 0, 0, 60));
            g.fillRoundRect(x + 2, y + 6 - lift / 2, cw - 2, ch, 26, 26);
            g.setPaint(new GradientPaint(x, y, mix(PANEL, accent, .10f + .18f * hover), x, y + ch, mix(PANEL, BG, .45f)));
            g.fillRoundRect(x, y, cw, ch, 26, 26);
            g.setColor(alpha(accent, 90 + (int) (hover * 120)));
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(x, y, cw, ch, 26, 26);
            icon(g, key, x + 16, y + 16, 54, accent);
            g.setColor(TEXT);
            g.setFont(new Font("Segoe UI", Font.BOLD, 17));
            g.drawString(title, x + 82, y + 50);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            int ty = y + 92;
            if (action != null) {
                g.setColor(MUTED);
                for (String line : wrap(desc, fm, cw - 32)) { g.drawString(line, x + 16, ty); ty += 16; }
                g.setColor(accent.brighter());
                g.setFont(new Font("Segoe UI", Font.BOLD, 12));
                g.drawString("\uD83C\uDFC6 " + footer.get(), x + 16, y + ch - 14);
            } else {
                g.setColor(MUTED);
                for (String line : footer.get().split("\n")) { g.drawString(line, x + 16, ty - 6); ty += 17; }
            }
            g.dispose();
        }
    }

    final class HomePanel extends JPanel {
        final float[][] orbs = new float[18][];
        final Color[] tints = {CYAN, new Color(236, 72, 153), new Color(139, 92, 246), new Color(16, 185, 129), new Color(249, 115, 22)};
        final ArrayList<Card> cards = new ArrayList<>();
        final Timer timer;

        HomePanel() {
            setOpaque(true);
            setLayout(new GridBagLayout());
            Random r = new Random();
            for (int i = 0; i < orbs.length; i++)
                orbs[i] = new float[]{r.nextFloat(), r.nextFloat() * 1.2f, .05f + r.nextFloat() * .13f, .0006f + r.nextFloat() * .0014f, r.nextInt(tints.length)};

            JPanel c = new JPanel();
            c.setOpaque(false);
            c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
            JLabel tag = label("JAVA 21  •  SWING ARCADE", 13, CYAN, true); tag.setAlignmentX(.5f);
            JLabel title = label("Mini Oyun Kütüphanesi", 38, TEXT, true); title.setAlignmentX(.5f);
            JLabel sub = label("Bir oyun seç ve rekorunu kır.", 16, MUTED, false); sub.setAlignmentX(.5f);
            c.add(tag); c.add(Box.createVerticalStrut(8)); c.add(title); c.add(Box.createVerticalStrut(7)); c.add(sub); c.add(Box.createVerticalStrut(24));

            cards.add(new Card("click", "Tıklama Hızı", "15 saniyede hedefe olabildiğince çok tıkla", new Color(14, 165, 233),
                    () -> "Rekor: " + Rekor.get("click", 0) + " tıklama", () -> openClick()));
            cards.add(new Card("bubble", "Balon Patlatma", "Aynı renkten 3 balonu eşleştirip patlat", new Color(236, 72, 153),
                    () -> "Rekor: " + Rekor.get("bubble", 0) + " puan", () -> openBubble()));
            cards.add(new Card("snake", "Yılan Oyunu", "Yiyecekleri topla ve büyü", new Color(16, 185, 129),
                    () -> "Rekor: " + Rekor.get("snake", 0) + " puan", () -> openSnake()));
            cards.add(new Card("memory", "Hafıza Kartları", "Eşleşen kart çiftlerini bul", new Color(139, 92, 246),
                    () -> { int b = Rekor.get("memory", -1); return b < 0 ? "Henüz rekor yok" : "En iyi: " + b + " hamle"; }, () -> openMemory()));
            cards.add(new Card("mines", "Mayın Tarlası", "Sayı ipuçlarıyla mayınlardan kaç", new Color(249, 115, 22),
                    () -> "K " + timeOrDash(Rekor.get("mines_0", -1)) + "  O " + timeOrDash(Rekor.get("mines_1", -1)) + "  Z " + timeOrDash(Rekor.get("mines_2", -1)), () -> openMines()));
            cards.add(new Card("stats", "Rekorların", "", new Color(234, 179, 8),
                    () -> "Tıklama:  " + Rekor.get("click", 0) + "\nBalon:  " + Rekor.get("bubble", 0) + "\nYılan:  " + Rekor.get("snake", 0)
                            + "\nHafıza:  " + (Rekor.get("memory", -1) < 0 ? "–" : Rekor.get("memory", -1) + " hamle")
                            + "\nMayın (Kolay):  " + timeOrDash(Rekor.get("mines_0", -1)), null));

            JPanel grid = new JPanel(new GridLayout(2, 3, 10, 0));
            grid.setOpaque(false);
            grid.setAlignmentX(.5f);
            for (Card card : cards) grid.add(card);
            c.add(grid);
            add(c);
            timer = new Timer(33, e -> step());
        }
        void run(boolean on) { if (on) { if (!timer.isRunning()) timer.start(); } else timer.stop(); }
        void step() {
            for (float[] o : orbs) { o[1] -= o[3]; if (o[1] < -.25f) o[1] = 1.25f; }
            for (Card card : cards) card.tick();
            repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            BufferedImage bg = image("arkaplan");
            if (bg != null) {
                double sc = Math.max((double) w / bg.getWidth(), (double) h / bg.getHeight());
                int iw = (int) (bg.getWidth() * sc), ih = (int) (bg.getHeight() * sc);
                g.drawImage(bg, (w - iw) / 2, (h - ih) / 2, iw, ih, null);
                g.setColor(new Color(8, 12, 24, 190));
                g.fillRect(0, 0, w, h);
            } else {
                g.setPaint(new GradientPaint(0, 0, new Color(9, 14, 32), w, h, new Color(26, 22, 64)));
                g.fillRect(0, 0, w, h);
            }
            for (float[] o : orbs) {
                float cx = o[0] * w, cy = o[1] * h, rad = Math.max(10f, o[2] * Math.min(w, h));
                Color t = tints[(int) o[4]];
                g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), rad, new float[]{0f, 1f}, new Color[]{alpha(t, 70), alpha(t, 0)}));
                g.fill(new Ellipse2D.Float(cx - rad, cy - rad, rad * 2, rad * 2));
            }
            g.dispose();
        }
    }

    JPanel header(String title, Runnable back) {
        JPanel h = new JPanel(new BorderLayout());
        h.setOpaque(false);
        h.add(label(title, 23, TEXT, true), BorderLayout.WEST);
        h.add(button("⌂  Ana Menü", new Color(51, 65, 85), back), BorderLayout.EAST);
        return h;
    }

    void stopAll() {
        if (snake != null) snake.stop();
        if (memory != null) memory.stop();
        if (mines != null) mines.stop();
        if (click != null) click.stop();
        if (bubble != null) bubble.stop();
    }

    void showHome() {
        stopAll();
        if (enlarged) { setSize(900, 720); setLocationRelativeTo(null); enlarged = false; }
        layout.show(root, "home");
        homePanel.run(true);
        homePanel.repaint();
    }

    void show(String key, JPanel game) {
        Component old = null;
        for (Component x : root.getComponents()) if (x.getName() != null && x.getName().equals(key)) old = x;
        if (old != null) root.remove(old);
        game.setName(key);
        root.add(game, key);
        layout.show(root, key);
    }

    void prepare() { stopAll(); homePanel.run(false); }
    void openSnake() { prepare(); snake = new SnakeGame(); show("snake", snake); snake.requestFocusInWindow(); }
    void openMemory() { prepare(); memory = new MemoryGame(); show("memory", memory); }
    void openMines() { prepare(); mines = new MineGame(); show("mines", mines); mines.fitMineWindow(); }
    void openClick() { prepare(); click = new ClickGame(); show("click", click); }
    void openBubble() { prepare(); bubble = new BubbleGame(); show("bubble", bubble); }

    // ================================================================ 1. Tıklama hızı
    final class ClickGame extends JPanel {
        final JPanel field = new JPanel(null) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                g.setPaint(new GradientPaint(0, 0, new Color(8, 15, 28), 0, getHeight(), new Color(18, 28, 52)));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(255, 255, 255, 10));
                for (int x = 0; x < getWidth(); x += 40) g.drawLine(x, 0, x, getHeight());
                for (int y = 0; y < getHeight(); y += 40) g.drawLine(0, y, getWidth(), y);
                g.dispose();
            }
        };
        final Target target = new Target();
        final JLabel info = label("Başlamak için Başla'ya bas", 16, MUTED, true);
        final JLabel countLabel = label("Tıklama  0", 16, CYAN, true);
        final JLabel bestLabel = label("", 13, MUTED, false);
        final Timer clock, pulse;
        int seconds = 15, count = 0;
        boolean active = false;
        final Random random = new Random();
        final Color red = new Color(244, 63, 94);

        ClickGame() {
            setLayout(new BorderLayout(12, 12));
            setBackground(BG);
            setBorder(new EmptyBorder(16, 22, 20, 22));
            JPanel north = new JPanel(new BorderLayout(8, 8));
            north.setOpaque(false);
            north.add(header("Tıklama Hızı Oyunu", MiniOyunlar.this::showHome), BorderLayout.NORTH);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(info, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            right.setOpaque(false);
            right.add(bestLabel);
            right.add(countLabel);
            top.add(right, BorderLayout.EAST);
            north.add(top, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            field.setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 2, true));
            field.add(target);
            add(field, BorderLayout.CENTER);

            JPanel controls = new JPanel(new FlowLayout());
            controls.setOpaque(false);
            controls.add(button("Oyuna Başla", new Color(14, 165, 233), this::start));
            controls.add(button("Ana Menü", new Color(51, 65, 85), MiniOyunlar.this::showHome));
            add(controls, BorderLayout.SOUTH);

            clock = new Timer(1000, e -> {
                seconds--;
                info.setText("Kalan süre: " + seconds + " saniye");
                if (seconds <= 0) finish();
            });
            pulse = new Timer(40, e -> target.repaint());
            pulse.start();
            field.addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent e) { moveTarget(); } });
            updateBest();
            moveTarget();
        }
        void updateBest() { bestLabel.setText("Rekor " + Rekor.get("click", 0)); }
        void start() {
            count = 0; seconds = 15; active = true;
            countLabel.setText("Tıklama  0");
            info.setText("Kalan süre: 15 saniye");
            moveTarget();
            clock.restart();
        }
        void hit() {
            if (!active) return;
            sound(700, 60);
            count++;
            countLabel.setText("Tıklama  " + count);
            fx.burst(field, target.getX() + 28, target.getY() + 28, red, 16);
            moveTarget();
        }
        void moveTarget() {
            if (field.getWidth() < 80 || field.getHeight() < 80) return;
            int x = random.nextInt(Math.max(1, field.getWidth() - 60)), y = random.nextInt(Math.max(1, field.getHeight() - 60));
            target.setBounds(x, y, 56, 56);
            field.repaint();
        }
        void finish() {
            clock.stop();
            active = false;
            String animal = count < 10 ? "Kaplumbağa" : count < 20 ? "Tavşan" : count < 35 ? "Sincap" : "Çita";
            boolean rec = Rekor.submitHigh("click", count);
            updateBest();
            info.setText("Süre bitti! " + animal + " hızındasın.");
            popup(this, "Oyun sonucu", "15 saniyede " + count + " tıklama yaptın.\nHayvan hızın: " + animal + (rec ? "\n\uD83C\uDF89 Yeni rekor!" : ""));
        }
        void stop() { clock.stop(); pulse.stop(); active = false; }

        final class Target extends JComponent {
            Target() {
                setSize(56, 56);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { hit(); } });
            }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                double p = (System.currentTimeMillis() % 900) / 900.0;
                float pul = (float) (.5 + .5 * Math.sin(p * Math.PI * 2));
                g.setColor(alpha(red, (int) (40 + pul * 60)));
                g.fillOval(0, 0, 56, 56);
                g.setPaint(new RadialGradientPaint(new Point2D.Float(24, 22), 24f, new float[]{0f, 1f},
                        new Color[]{new Color(253, 164, 175), new Color(225, 29, 72)}));
                g.fillOval(8, 8, 40, 40);
                g.setColor(alpha(Color.WHITE, 170));
                g.setStroke(new BasicStroke(2f));
                g.drawOval(15, 15, 26, 26);
                g.fillOval(25, 25, 6, 6);
                g.dispose();
            }
        }
    }

    // ================================================================ 2. Balon eşleştirme
    final class BubbleGame extends JPanel {
        final int C = 11, R = 9, S = 42;
        final Color[] palette = {new Color(248, 82, 112), new Color(59, 130, 246), new Color(250, 204, 21), new Color(34, 197, 94), new Color(168, 85, 247)};
        final Color[][] grid = new Color[R][C];
        final Random random = new Random();
        final JLabel info = label("Nişan al ve tıkla • 3+ aynı renk patlar", 15, MUTED, true);
        final JLabel scoreLabel = label("Puan 0", 15, CYAN, true);
        final JLabel bestLabel = label("", 13, MUTED, false);
        final BubbleBoard board = new BubbleBoard();
        final Timer anim;
        Color current;
        int score = 0, shots = 0, aimX = -1, aimY = -1;
        boolean ended = false, flying = false;
        double bx, by, bvx, bvy;

        BubbleGame() {
            setLayout(new BorderLayout(10, 12));
            setBackground(BG);
            setBorder(new EmptyBorder(15, 20, 18, 20));
            JPanel north = new JPanel(new BorderLayout(8, 8));
            north.setOpaque(false);
            north.add(header("Balon Patlatma", MiniOyunlar.this::showHome), BorderLayout.NORTH);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(info, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            right.setOpaque(false);
            right.add(bestLabel);
            right.add(scoreLabel);
            top.add(right, BorderLayout.EAST);
            north.add(top, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);
            board.setPreferredSize(new Dimension(C * S + 10, R * S + 70));
            add(board, BorderLayout.CENTER);
            JPanel bottom = new JPanel(new FlowLayout());
            bottom.setOpaque(false);
            bottom.add(button("Yeniden Başlat", new Color(236, 72, 153), this::reset));
            add(bottom, BorderLayout.SOUTH);
            anim = new Timer(16, e -> animate());
            reset();
        }
        Color randomColor() { return palette[random.nextInt(palette.length)]; }
        int ox() { return Math.max(0, (board.getWidth() - C * S) / 2); }
        void updateBest() { bestLabel.setText("Rekor " + Rekor.get("bubble", 0)); }

        void reset() {
            anim.stop();
            for (Color[] row : grid) Arrays.fill(row, null);
            for (int r = 0; r < 4; r++) for (int c = 0; c < C; c++) grid[r][c] = randomColor();
            score = shots = 0;
            ended = flying = false;
            current = randomColor();
            info.setText("Nişan al ve tıkla • 3+ aynı renk patlar");
            scoreLabel.setText("Puan 0");
            updateBest();
            board.repaint();
        }
        void stop() { anim.stop(); flying = false; }

        void shoot(int mx, int my) {
            if (ended || flying) return;
            double sx = board.getWidth() / 2.0, sy = board.getHeight() - 43;
            double dx = mx - sx, dy = my - sy;
            if (dy > -30) dy = -30;
            double len = Math.hypot(dx, dy);
            bvx = dx / len * 10; bvy = dy / len * 10;
            bx = sx; by = sy;
            flying = true;
            anim.start();
        }
        void animate() {
            bx += bvx; by += bvy;
            int left = ox() + 14, right = ox() + C * S - 14;
            if (bx < left) { bx = left; bvx = -bvx; }
            if (bx > right) { bx = right; bvx = -bvx; }
            boolean hit = by <= S / 2.0;
            for (int r = 0; r < R && !hit; r++)
                for (int c = 0; c < C; c++)
                    if (grid[r][c] != null && Math.hypot(bx - (ox() + c * S + S / 2.0), by - (r * S + S / 2.0)) < S - 8) { hit = true; break; }
            if (hit) { flying = false; anim.stop(); attach(); }
            board.repaint();
        }
        void attach() {
            int row = -1, col = -1;
            double best = Double.MAX_VALUE;
            for (int r = 0; r < R; r++)
                for (int c = 0; c < C; c++)
                    if (grid[r][c] == null) {
                        boolean attached = r == 0
                                || (r > 0 && grid[r - 1][c] != null) || (r + 1 < R && grid[r + 1][c] != null)
                                || (c > 0 && grid[r][c - 1] != null) || (c + 1 < C && grid[r][c + 1] != null);
                        if (!attached) continue;
                        double d = Math.hypot(bx - (ox() + c * S + S / 2.0), by - (r * S + S / 2.0));
                        if (d < best) { best = d; row = r; col = c; }
                    }
            if (row < 0) { finishGame(false); return; }
            grid[row][col] = current;
            shots++;
            sound(320, 55);
            ArrayList<Point> group = cluster(row, col, current);
            if (group.size() >= 3) {
                sound(880, 130);
                for (Point p : group) {
                    fx.burst(board, ox() + p.x * S + S / 2, p.y * S + S / 2, grid[p.y][p.x], 10);
                    grid[p.y][p.x] = null;
                }
                score += group.size() * 10;
                dropFloating();
            }
            current = randomColor();
            scoreLabel.setText("Puan " + score);
            if (empty()) finishGame(true);
            else if (shots >= 35) finishGame(false);
            else info.setText("Atış " + shots + "/35 • Aynı renkten 3 balonu eşleştir");
            board.repaint();
        }
        ArrayList<Point> cluster(int sr, int sc, Color color) {
            ArrayList<Point> out = new ArrayList<>();
            boolean[][] seen = new boolean[R][C];
            ArrayDeque<Point> q = new ArrayDeque<>();
            q.add(new Point(sc, sr));
            seen[sr][sc] = true;
            int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
            while (!q.isEmpty()) {
                Point p = q.remove();
                out.add(p);
                for (int i = 0; i < 4; i++) {
                    int y = p.y + dr[i], x = p.x + dc[i];
                    if (y >= 0 && y < R && x >= 0 && x < C && !seen[y][x] && color.equals(grid[y][x])) { seen[y][x] = true; q.add(new Point(x, y)); }
                }
            }
            return out;
        }
        void dropFloating() {
            boolean[][] connected = new boolean[R][C];
            ArrayDeque<Point> q = new ArrayDeque<>();
            for (int c = 0; c < C; c++) if (grid[0][c] != null) { q.add(new Point(c, 0)); connected[0][c] = true; }
            int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
            while (!q.isEmpty()) {
                Point p = q.remove();
                for (int i = 0; i < 4; i++) {
                    int y = p.y + dr[i], x = p.x + dc[i];
                    if (y >= 0 && y < R && x >= 0 && x < C && !connected[y][x] && grid[y][x] != null) { connected[y][x] = true; q.add(new Point(x, y)); }
                }
            }
            for (int r = 0; r < R; r++)
                for (int c = 0; c < C; c++)
                    if (grid[r][c] != null && !connected[r][c]) {
                        fx.burst(board, ox() + c * S + S / 2, r * S + S / 2, grid[r][c], 6);
                        grid[r][c] = null;
                        score += 5;
                    }
        }
        boolean empty() { for (Color[] row : grid) for (Color b : row) if (b != null) return false; return true; }

        void finishGame(boolean win) {
            ended = true;
            anim.stop();
            if (!win) sound(160, 300);
            boolean rec = Rekor.submitHigh("bubble", score);
            updateBest();
            info.setText(win ? "Kazandın! Tüm balonlar temizlendi." : "Oyun bitti • yeniden deneyebilirsin");
            popup(this, win ? "Kazandın" : "Oyun bitti",
                    (win ? "Harika! Puanın: " : "Atış hakkın bitti. Puanın: ") + score + (rec ? "\n\uD83C\uDF89 Yeni rekor!" : ""));
        }

        final class BubbleBoard extends JPanel {
            BubbleBoard() {
                setBackground(new Color(8, 15, 28));
                setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 2, true));
                addMouseListener(new MouseAdapter() { public void mousePressed(MouseEvent e) { shoot(e.getX(), e.getY()); } });
                addMouseMotionListener(new MouseMotionAdapter() {
                    public void mouseMoved(MouseEvent e) { aimX = e.getX(); aimY = e.getY(); repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics gg) {
                super.paintComponent(gg);
                Graphics2D g = (Graphics2D) gg.create();
                aa(g);
                g.setPaint(new GradientPaint(0, 0, new Color(8, 15, 28), 0, getHeight(), new Color(22, 18, 52)));
                g.fillRect(0, 0, getWidth(), getHeight());
                for (int r = 0; r < R; r++)
                    for (int c = 0; c < C; c++)
                        if (grid[r][c] != null) drawBall(g, ox() + c * S + S / 2, r * S + S / 2, grid[r][c], S - 5);
                int cx = getWidth() / 2, cy = getHeight() - 43;
                // nişan çizgisi
                if (!flying && !ended) {
                    double dx = (aimX < 0 ? 0 : aimX - cx), dy = (aimX < 0 ? -1 : aimY - cy);
                    if (dy > -30) dy = -30;
                    double len = Math.hypot(dx, dy);
                    g.setColor(new Color(148, 163, 184, 110));
                    g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 8}, 0));
                    g.drawLine(cx, cy, (int) (cx + dx / len * 150), (int) (cy + dy / len * 150));
                }
                g.setColor(new Color(71, 85, 105));
                g.fillRoundRect(cx - 26, getHeight() - 26, 52, 22, 10, 10);
                g.setColor(new Color(100, 116, 139));
                g.fillRoundRect(cx - 8, cy - 28, 16, 30, 8, 8);
                drawBall(g, cx, cy, current, 30);
                if (flying) drawBall(g, (int) bx, (int) by, current, 30);
                g.dispose();
            }
            void drawBall(Graphics2D g, int x, int y, Color c, int d) {
                g.setPaint(new RadialGradientPaint(new Point2D.Float(x - d * .15f, y - d * .2f), d * .75f, new float[]{0f, 1f},
                        new Color[]{mix(c, Color.WHITE, .55f), mix(c, Color.BLACK, .25f)}));
                g.fillOval(x - d / 2, y - d / 2, d, d);
                g.setColor(alpha(Color.WHITE, 70));
                g.setStroke(new BasicStroke(1.2f));
                g.drawOval(x - d / 2, y - d / 2, d, d);
                g.setColor(alpha(Color.WHITE, 150));
                g.fillOval(x - (int) (d * .3), y - (int) (d * .32), Math.max(3, (int) (d * .24)), Math.max(2, (int) (d * .14)));
            }
        }
    }

    // ================================================================ 3. Yılan
    final class SnakeGame extends JPanel {
        final int cols = 24, rows = 17, cell = 25;
        final ArrayDeque<Point> body = new ArrayDeque<>();
        final Random random = new Random();
        Point food;
        int dx = 1, dy = 0, ndx = 1, ndy = 0, score = 0;
        boolean running = false, over = false;
        final Timer timer;
        final JLabel status = label("Başlamak için yön tuşuna bas", 14, MUTED, true);
        final JLabel points = label("PUAN 0", 15, CYAN, true);
        final JLabel bestLabel = label("", 13, MUTED, false);
        final SnakeBoard board = new SnakeBoard();

        SnakeGame() {
            setLayout(new BorderLayout(10, 12));
            setBackground(BG);
            setBorder(new EmptyBorder(15, 20, 18, 20));
            JPanel north = new JPanel(new BorderLayout(8, 8));
            north.setOpaque(false);
            north.add(header("Yılan Oyunu", MiniOyunlar.this::showHome), BorderLayout.NORTH);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(status, BorderLayout.WEST);
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            right.setOpaque(false);
            right.add(bestLabel);
            right.add(points);
            top.add(right, BorderLayout.EAST);
            north.add(top, BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);
            board.setPreferredSize(new Dimension(cols * cell, rows * cell));
            add(board, BorderLayout.CENTER);
            JPanel bot = new JPanel();
            bot.setOpaque(false);
            bot.add(button("Yeniden Başlat", new Color(16, 185, 129), this::reset));
            bot.add(label("Ok tuşları veya WASD ile hareket et", 14, MUTED, false));
            add(bot, BorderLayout.SOUTH);
            timer = new Timer(125, e -> tick());
            bind("UP", 0, -1); bind("DOWN", 0, 1); bind("LEFT", -1, 0); bind("RIGHT", 1, 0);
            bind("W", 0, -1); bind("S", 0, 1); bind("A", -1, 0); bind("D", 1, 0);
            reset();
        }
        void bind(String key, int x, int y) {
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), "snake-" + key);
            getActionMap().put("snake-" + key, new AbstractAction() {
                public void actionPerformed(ActionEvent e) { turn(x, y); }
            });
        }
        void turn(int x, int y) {
            if (over) return;
            if (!running) { running = true; timer.start(); status.setText("Oyun devam ediyor"); }
            if (x != -dx || y != -dy) { ndx = x; ndy = y; }
        }
        void spawnFood() {
            do { food = new Point(random.nextInt(cols), random.nextInt(rows)); } while (body.contains(food));
        }
        void reset() {
            timer.stop();
            timer.setDelay(125);
            body.clear();
            body.add(new Point(cols / 2, rows / 2));
            body.add(new Point(cols / 2 - 1, rows / 2));
            body.add(new Point(cols / 2 - 2, rows / 2));
            dx = ndx = 1; dy = ndy = 0;
            score = 0;
            running = over = false;
            spawnFood();
            points.setText("PUAN 0");
            bestLabel.setText("Rekor " + Rekor.get("snake", 0));
            status.setText("Başlamak için yön tuşuna bas");
            board.repaint();
        }
        void stop() { timer.stop(); running = false; }
        void tick() {
            dx = ndx; dy = ndy;
            Point h = body.peekFirst(), n = new Point(h.x + dx, h.y + dy);
            boolean eating = n.equals(food);
            Point tail = body.peekLast();
            boolean hitsBody = body.contains(n) && !(n.equals(tail) && !eating);
            if (n.x < 0 || n.y < 0 || n.x >= cols || n.y >= rows || hitsBody) {
                timer.stop();
                running = false;
                over = true;
                sound(150, 300);
                fx.burst(board, h.x * cell + cell / 2, h.y * cell + cell / 2, new Color(52, 211, 153), 26);
                boolean rec = Rekor.submitHigh("snake", score);
                bestLabel.setText("Rekor " + Rekor.get("snake", 0));
                status.setText("Oyun bitti • Puan " + score);
                popup(this, "Oyun bitti", "Yılan çarptı! Puanın: " + score + (rec ? "\n\uD83C\uDF89 Yeni rekor!" : ""));
                return;
            }
            body.addFirst(n);
            if (eating) {
                sound(760, 100);
                fx.burst(board, food.x * cell + cell / 2, food.y * cell + cell / 2, new Color(251, 113, 133), 16);
                score += 10;
                points.setText("PUAN " + score);
                spawnFood();
                if (score % 50 == 0) timer.setDelay(Math.max(70, timer.getDelay() - 8));
            } else body.removeLast();
            board.repaint();
        }

        final class SnakeBoard extends JPanel {
            SnakeBoard() {
                setBackground(new Color(8, 15, 28));
                setBorder(BorderFactory.createLineBorder(new Color(51, 65, 85), 2, true));
                new Timer(50, e -> { if (food != null && isShowing()) repaint(); }).start(); // yem nabzı
            }
            @Override protected void paintComponent(Graphics gg) {
                super.paintComponent(gg);
                Graphics2D g = (Graphics2D) gg.create();
                aa(g);
                g.setPaint(new GradientPaint(0, 0, new Color(8, 15, 28), 0, getHeight(), new Color(14, 28, 40)));
                g.fillRect(0, 0, getWidth(), getHeight());
                for (int x = 0; x < cols; x++)
                    for (int y = 0; y < rows; y++) {
                        g.setColor((x + y) % 2 == 0 ? new Color(22, 34, 52, 160) : new Color(18, 28, 44, 160));
                        g.fillRoundRect(x * cell + 1, y * cell + 1, cell - 2, cell - 2, 6, 6);
                    }
                // yem
                double pul = .5 + .5 * Math.sin(System.currentTimeMillis() / 160.0);
                int fcx = food.x * cell + cell / 2, fcy = food.y * cell + cell / 2;
                g.setColor(new Color(251, 113, 133, (int) (40 + pul * 50)));
                g.fillOval(fcx - 13, fcy - 13, 26, 26);
                g.setPaint(new RadialGradientPaint(new Point2D.Float(fcx - 2, fcy - 3), 10f, new float[]{0f, 1f},
                        new Color[]{new Color(254, 205, 211), new Color(244, 63, 94)}));
                int fr = 8 + (int) (pul * 2);
                g.fillOval(fcx - fr, fcy - fr, fr * 2, fr * 2);
                // yılan
                int i = 0, size = body.size();
                Color headC = new Color(110, 231, 183), tailC = new Color(5, 150, 105);
                for (Point p : body) {
                    g.setColor(mix(headC, tailC, i / (float) Math.max(1, size - 1)));
                    int inset = i == 0 ? 1 : 2;
                    g.fillRoundRect(p.x * cell + inset, p.y * cell + inset, cell - inset * 2, cell - inset * 2, 11, 11);
                    i++;
                }
                Point h = body.peekFirst();
                int hx = h.x * cell + cell / 2, hy = h.y * cell + cell / 2;
                int[][] eyes = dx != 0 ? new int[][]{{hx + dx * 5, hy - 5}, {hx + dx * 5, hy + 5}} : new int[][]{{hx - 5, hy + dy * 5}, {hx + 5, hy + dy * 5}};
                for (int[] e : eyes) {
                    g.setColor(Color.WHITE);
                    g.fillOval(e[0] - 3, e[1] - 3, 7, 7);
                    g.setColor(new Color(15, 23, 42));
                    g.fillOval(e[0] - 1 + dx, e[1] - 1 + dy, 4, 4);
                }
                g.dispose();
            }
        }
    }

    // ================================================================ 4. Hafıza kartları
    final class MemoryGame extends JPanel {
        final String[] icons = {"🍓", "🍋", "🍇", "🍒", "🥝", "🍉", "🍍", "🍑"};
        final ArrayList<String> deck = new ArrayList<>();
        final ArrayList<RoundButton> tiles = new ArrayList<>();
        boolean[] matched;
        int first = -1, moves = 0, found = 0, seconds = 0;
        boolean locked = false;
        final Timer clock, hide;
        final JLabel info = label("", 14, MUTED, true);
        final JPanel grid = new JPanel(new GridLayout(4, 4, 10, 10));
        final Color closed = new Color(49, 46, 129), open = new Color(109, 40, 217), done = new Color(5, 150, 105);

        MemoryGame() {
            setLayout(new BorderLayout(10, 12));
            setBackground(BG);
            setBorder(new EmptyBorder(15, 22, 18, 22));
            add(header("Hafıza Kartları", MiniOyunlar.this::showHome), BorderLayout.NORTH);
            grid.setOpaque(false);
            grid.setBorder(new EmptyBorder(15, 50, 15, 50));
            add(grid, BorderLayout.CENTER);
            JPanel bot = new JPanel(new BorderLayout());
            bot.setOpaque(false);
            bot.add(info, BorderLayout.WEST);
            bot.add(button("Yeniden Başlat", new Color(139, 92, 246), this::newGame), BorderLayout.EAST);
            add(bot, BorderLayout.SOUTH);
            clock = new Timer(1000, e -> { seconds++; updateInfo(); });
            hide = new Timer(800, e -> hidePair());
            hide.setRepeats(false);
            newGame();
        }
        void newGame() {
            stop();
            deck.clear();
            ArrayList<String> a = new ArrayList<>(Arrays.asList(icons));
            Collections.shuffle(a);
            for (int i = 0; i < 8; i++) { deck.add(a.get(i)); deck.add(a.get(i)); }
            Collections.shuffle(deck);
            matched = new boolean[16];
            tiles.clear();
            first = -1;
            moves = found = seconds = 0;
            locked = false;
            grid.removeAll();
            for (int i = 0; i < 16; i++) {
                final int k = i;
                RoundButton b = new RoundButton("?");
                b.arc = 20;
                b.edge = new Color(99, 102, 241);
                b.setBorder(BorderFactory.createEmptyBorder());
                b.setFont(new Font("Segoe UI Emoji", Font.BOLD, 30));
                b.setForeground(TEXT);
                b.setBackground(closed);
                b.addActionListener(e -> flip(k));
                tiles.add(b);
                grid.add(b);
            }
            clock.start();
            updateInfo();
            grid.revalidate();
            grid.repaint();
        }
        void flip(int i) {
            if (locked || matched[i] || i == first) return;
            sound(520, 45);
            tiles.get(i).setText(deck.get(i));
            tiles.get(i).setBackground(open);
            if (first < 0) { first = i; return; }
            moves++;
            if (deck.get(first).equals(deck.get(i))) {
                sound(840, 120);
                matched[first] = matched[i] = true;
                found++;
                for (int t : new int[]{first, i}) {
                    RoundButton b = tiles.get(t);
                    b.setBackground(done);
                    b.hoverFx = false;
                    fx.burst(b, b.getWidth() / 2, b.getHeight() / 2, new Color(52, 211, 153), 14);
                }
                first = -1;
                if (found == 8) {
                    clock.stop();
                    boolean rec = Rekor.submitLow("memory", moves);
                    updateInfo();
                    popup(this, "Kazandın", "Tüm çiftleri " + moves + " hamlede buldun!" + (rec ? "\n\uD83C\uDF89 Yeni rekor!" : ""));
                }
            } else { sound(250, 100); locked = true; hide.start(); }
            updateInfo();
        }
        void hidePair() {
            for (int i = 0; i < 16; i++)
                if (!matched[i]) { tiles.get(i).setText("?"); tiles.get(i).setBackground(closed); }
            first = -1;
            locked = false;
        }
        void updateInfo() {
            int best = Rekor.get("memory", -1);
            info.setText("Hamle " + moves + " • Eşleşme " + found + "/8 • Süre " + time(seconds) + " • Rekor " + (best < 0 ? "–" : best + " hamle"));
        }
        void stop() { if (clock != null) clock.stop(); if (hide != null) hide.stop(); }
    }

    // ================================================================ 5. Mayın tarlası
    final class MineGame extends JPanel {
        int rows = 9, cols = 9, mineCount = 10, flags = 0, opened = 0, seconds = 0;
        boolean first = true, ended = false;
        boolean[][] mine, revealed, flagged;
        RoundButton[][] cells;
        final JLabel info = label("", 14, MUTED, true);
        final JPanel grid = new JPanel();
        final JComboBox<String> level = new JComboBox<>(new String[]{"Kolay", "Orta", "Zor"});
        final Timer clock;
        final Color hiddenC = new Color(51, 65, 85);

        MineGame() {
            setLayout(new BorderLayout(10, 12));
            setBackground(BG);
            setBorder(new EmptyBorder(15, 18, 18, 18));
            add(header("Mayın Tarlası", MiniOyunlar.this::showHome), BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout());
            center.setOpaque(false);
            center.add(grid, BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);
            JPanel bot = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
            bot.setOpaque(false);
            bot.add(info);
            level.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            bot.add(level);
            bot.add(button("Yeni Oyun", new Color(249, 115, 22), this::newGame));
            add(bot, BorderLayout.SOUTH);
            clock = new Timer(1000, e -> { seconds++; updateInfo(); });
            level.addActionListener(e -> {
                int i = level.getSelectedIndex();
                if (i == 0) { rows = 9; cols = 9; mineCount = 10; }
                else if (i == 1) { rows = 16; cols = 16; mineCount = 40; }
                else { rows = 16; cols = 30; mineCount = 99; }
                newGame();
                fitMineWindow();
            });
            newGame();
        }
        void fitMineWindow() {
            int width = Math.max(760, cols * 36 + 80), height = Math.max(650, rows * 36 + 190);
            MiniOyunlar.this.setSize(width, height);
            MiniOyunlar.this.setLocationRelativeTo(null);
            enlarged = true;
        }
        void newGame() {
            if (clock != null) clock.stop();
            first = true; ended = false;
            flags = opened = seconds = 0;
            mine = new boolean[rows][cols];
            revealed = new boolean[rows][cols];
            flagged = new boolean[rows][cols];
            cells = new RoundButton[rows][cols];
            grid.removeAll();
            grid.setOpaque(false);
            grid.setLayout(new GridLayout(rows, cols, 2, 2));
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    final int rr = r, cc = c;
                    RoundButton b = new RoundButton("");
                    b.arc = 8;
                    b.setBorder(BorderFactory.createEmptyBorder());
                    b.setMargin(new Insets(0, 0, 0, 0));
                    b.setPreferredSize(new Dimension(34, 34));
                    b.setBackground(hiddenC);
                    b.edge = new Color(71, 85, 105);
                    b.setFont(new Font("Segoe UI", Font.BOLD, rows > 9 ? 16 : 18));
                    b.addMouseListener(new MouseAdapter() {
                        public void mousePressed(MouseEvent e) {
                            if (SwingUtilities.isRightMouseButton(e)) toggleFlag(rr, cc); else open(rr, cc);
                        }
                    });
                    cells[r][c] = b;
                    grid.add(b);
                }
            updateInfo();
            grid.revalidate();
            grid.repaint();
        }
        void placeMines(int sr, int sc) {
            Random rnd = new Random();
            int n = 0;
            while (n < mineCount) {
                int r = rnd.nextInt(rows), c = rnd.nextInt(cols);
                if (mine[r][c] || Math.abs(r - sr) <= 1 && Math.abs(c - sc) <= 1) continue;
                mine[r][c] = true;
                n++;
            }
        }
        int count(int r, int c) {
            int n = 0;
            for (int dr = -1; dr <= 1; dr++)
                for (int dc = -1; dc <= 1; dc++) {
                    int y = r + dr, x = c + dc;
                    if (y >= 0 && y < rows && x >= 0 && x < cols && mine[y][x]) n++;
                }
            return n;
        }
        void open(int r, int c) {
            if (ended || flagged[r][c] || revealed[r][c]) return;
            if (first) { placeMines(r, c); first = false; clock.start(); }
            revealed[r][c] = true;
            opened++;
            RoundButton b = cells[r][c];
            b.hoverFx = false;
            if (mine[r][c]) {
                b.setText("💣");
                b.setBackground(new Color(127, 29, 29));
                finish(false, r, c);
                return;
            }
            int n = count(r, c);
            if (n > 0) {
                Color[] colors = {TEXT, new Color(59, 130, 246), new Color(22, 163, 74), new Color(220, 38, 38), new Color(126, 34, 206),
                        new Color(190, 24, 93), new Color(8, 145, 178), Color.BLACK, new Color(100, 116, 139)};
                b.setText("" + n);
                b.setForeground(colors[n]);
                b.setBackground(new Color(203, 213, 225));
            } else {
                b.setBackground(new Color(148, 163, 184));
                for (int dr = -1; dr <= 1; dr++)
                    for (int dc = -1; dc <= 1; dc++) {
                        int y = r + dr, x = c + dc;
                        if (y >= 0 && y < rows && x >= 0 && x < cols && !revealed[y][x] && !flagged[y][x]) open(y, x);
                    }
            }
            if (opened == rows * cols - mineCount) finish(true, r, c);
        }
        void toggleFlag(int r, int c) {
            if (ended || revealed[r][c]) return;
            if (flagged[r][c]) { flagged[r][c] = false; flags--; cells[r][c].setText(""); }
            else if (flags < mineCount) {
                flagged[r][c] = true; flags++;
                sound(600, 70);
                cells[r][c].setText("⚑");
                cells[r][c].setForeground(new Color(251, 191, 36));
            }
            updateInfo();
        }
        void finish(boolean win, int lr, int lc) {
            if (ended) return;
            ended = true;
            clock.stop();
            boolean rec = false;
            if (win) {
                sound(880, 200);
                rec = Rekor.submitLow("mines_" + level.getSelectedIndex(), seconds);
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        if (mine[r][c] && r % 2 == c % 2) fx.burst(cells[r][c], 17, 17, new Color(250, 204, 21), 8);
            } else {
                sound(130, 350);
                fx.burst(cells[lr][lc], 17, 17, new Color(249, 115, 22), 34);
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        if (mine[r][c]) { cells[r][c].setText("💣"); cells[r][c].setBackground(new Color(127, 29, 29)); }
            }
            updateInfo();
            popup(this, win ? "Kazandın" : "Oyun bitti",
                    win ? "Tüm güvenli kareleri açtın! Süre: " + time(seconds) + (rec ? "\n\uD83C\uDF89 Yeni rekor!" : "")
                        : "Mayına bastın! Yeni oyunda tekrar dene.");
        }
        void updateInfo() {
            int best = Rekor.get("mines_" + level.getSelectedIndex(), -1);
            info.setText("Bayrak " + flags + "/" + mineCount + " • Süre " + time(seconds) + " • Rekor " + timeOrDash(best));
        }
        void stop() { if (clock != null) clock.stop(); }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MiniOyunlar().setVisible(true));
    }
}