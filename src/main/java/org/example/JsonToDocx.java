package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonToDocx {

    // ---- ПУТИ (поставь свои) ----
    static final Path INPUT_JSON = Paths.get("C:\\Users\\Kate\\IdeaProjects\\Diary\\vk-diary-export\\out\\topic.json");
    static final Path OUT_DOCX   = Paths.get("C:\\Users\\Kate\\IdeaProjects\\Diary\\vk-diary-export\\out\\vk-diary.docx");
    // Если файл часто открыт и мешает — включи уникальное имя:
    // static final Path OUT_DOCX = Paths.get("C:\\Users\\Kate\\IdeaProjects\\Diary\\vk-diary-export\\out\\vk-diary-" + System.currentTimeMillis() + ".docx");

    // ---- НАСТРОЙКИ ----
    static final boolean EMBED_EMOJIS = true;        // эмодзи стараемся встраивать (маленькие)
    static final boolean EMBED_PHOTOS = true;        // фото стараемся встраивать (широкие)
    static final int EMOJI_SIZE_PX = 18;             // размер эмодзи в тексте
    static final int MAX_PHOTO_WIDTH_PX = 600;       // ширина фото
    static final long MAX_IMAGE_BYTES = 12_000_000;  // 12MB на картинку
    static final int HTTP_TIMEOUT_SEC = 30;

    static final String VK_BASE = "https://vk.com";

    // Маркер эмодзи в тексте: [[EMOJI:URL]]
    static final Pattern EMOJI_MARK = Pattern.compile("\\[\\[EMOJI:(.+?)]]");

    // Дата из VK: "25 июл 2017 в 21:31" / "25 июл 2017 21:31" / "25 июл 2017"
    static final Pattern VK_DATE = Pattern.compile(
            "^(\\d{1,2})\\s+([а-яё]{3})\\s+(\\d{4})(?:\\s+(?:в\\s+)??(\\d{1,2}):(\\d{2}))?.*$",
            Pattern.CASE_INSENSITIVE
    );

    static final Map<String, Integer> MONTH_ABBR_TO_NUM = Map.ofEntries(
            Map.entry("янв", 1), Map.entry("фев", 2), Map.entry("мар", 3), Map.entry("апр", 4),
            Map.entry("май", 5), Map.entry("июн", 6), Map.entry("июл", 7), Map.entry("авг", 8),
            Map.entry("сен", 9), Map.entry("окт", 10), Map.entry("ноя", 11), Map.entry("дек", 12)
    );

    static final String[] MONTH_NOM = { // заголовок месяца
            "", "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    };

    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
            .build();

    public static void main(String[] args) throws Exception {
        if (!Files.exists(INPUT_JSON)) {
            throw new RuntimeException("Не найден файл: " + INPUT_JSON.toAbsolutePath());
        }

        ObjectMapper om = new ObjectMapper();
        List<Map<String, Object>> raw;
        try (FileInputStream fis = new FileInputStream(INPUT_JSON.toFile())) {
            raw = om.readValue(fis, new TypeReference<>() {});
        }

        List<Entry> entries = new ArrayList<>();
        for (Map<String, Object> it : raw) {
            String text = str(it.get("text")).trim();
            String dateRaw = str(it.get("date")).trim(); // в твоём JSON поле "date"
            if (dateRaw.isEmpty()) dateRaw = str(it.get("dateText")).trim(); // на всякий, если вдруг названо dateText

            // автор не нужен — игнорим
            String postId = str(it.get("postId")).trim();

            if (text.isEmpty()) continue;

            List<String> photos = dedupe(toStringList(it.get("photos")));
            if (photos.isEmpty()) {
                // на случай старого формата, где было imgs:
                photos = dedupe(toStringList(it.get("imgs")));
            }

            ParsedDate pd = parseVkDate(dateRaw);
            entries.add(new Entry(dateRaw, pd, postId, text, photos));
        }

        // Группируем год -> месяц (берём из распарсенной даты; если не получилось — "Без даты")
        LinkedHashMap<String, LinkedHashMap<String, List<Entry>>> grouped = new LinkedHashMap<>();
        for (Entry e : entries) {
            String yearKey = (e.parsed != null) ? String.valueOf(e.parsed.year) : "Без даты";
            String monthKey = (e.parsed != null) ? MONTH_NOM[e.parsed.month] : "Без даты";

            grouped.putIfAbsent(yearKey, new LinkedHashMap<>());
            grouped.get(yearKey).putIfAbsent(monthKey, new ArrayList<>());
            grouped.get(yearKey).get(monthKey).add(e);
        }

        // DOCX
        XWPFDocument doc = new XWPFDocument();

        addTitle(doc, "Personal Archive");
        addMeta(doc, "Источник: " + INPUT_JSON);
        doc.createParagraph();

        // Контент
        for (var y : grouped.entrySet()) {
            addHeading(doc, 1, y.getKey());           // ГОД = Heading 1

            for (var m : y.getValue().entrySet()) {
                addHeading(doc, 2, m.getKey());       // МЕСЯЦ = Heading 2

                for (Entry e : m.getValue()) {
                    // ДАТА = Heading 3 (как есть, без “перевода месяцев”)
                    addHeading(doc, 3, safeTitle(e.dateRaw));

                    // Текст с эмодзи в местах
                    addTextWithInlineEmojis(doc, e.text);

                    // Фото (крупные) отдельно
                    if (!e.photos.isEmpty()) {
                        addPhotosBlock(doc, e.photos);
                    }

                    doc.createParagraph();
                }
            }
            doc.createParagraph();
        }

        // ВАЖНО: если файл открыт — будет ошибка. Закрой Word/Docs перед запуском.
        try (FileOutputStream fos = new FileOutputStream(OUT_DOCX.toFile())) {
            doc.write(fos);
        }
        doc.close();

        System.out.println("ГОТОВО: " + OUT_DOCX.toAbsolutePath());
    }

    // ---------- ТЕКСТ + ЭМОДЗИ ВНУТРИ ----------
    static void addTextWithInlineEmojis(XWPFDocument doc, String rawText) {
        if (rawText == null) return;

        String text = rawText.replace("\r\n", "\n").replace("\r", "\n");
        // Мы сохраняем переносы строк, но эмодзи вставляем прямо в run.

        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingAfter(0);

            if (line.isEmpty()) {
                // пустая строка
                continue;
            }

            Matcher m = EMOJI_MARK.matcher(line);
            int pos = 0;

            while (m.find()) {
                // текст до эмодзи
                String before = line.substring(pos, m.start());
                if (!before.isEmpty()) {
                    XWPFRun r = p.createRun();
                    r.setFontSize(11);
                    r.setText(before);
                }

                // эмодзи url
                String emojiUrl = m.group(1).trim();
                emojiUrl = normalizeVkUrl(emojiUrl);

                if (EMBED_EMOJIS) {
                    boolean ok = tryAddInlineImage(p, emojiUrl, EMOJI_SIZE_PX);
                    if (!ok) {
                        // если не вышло — вставим хотя бы символ-заглушку
                        XWPFRun r = p.createRun();
                        r.setFontSize(11);
                        r.setText("🙂");
                    }
                } else {
                    XWPFRun r = p.createRun();
                    r.setFontSize(11);
                    r.setText("🙂");
                }

                pos = m.end();
            }

            // хвост после последнего эмодзи
            String tail = line.substring(pos);
            if (!tail.isEmpty()) {
                XWPFRun r = p.createRun();
                r.setFontSize(11);
                r.setText(tail);
            }
        }
    }

    static void addPhotosBlock(XWPFDocument doc, List<String> urls) {
        XWPFParagraph ph = doc.createParagraph();
        XWPFRun phr = ph.createRun();
        phr.setBold(true);
        phr.setFontSize(11);
        phr.setText("Фото:");

        for (String raw : urls) {
            String url = normalizeVkUrl(raw);

            boolean embedded = false;
            if (EMBED_PHOTOS) {
                embedded = tryAddBlockImage(doc, url, MAX_PHOTO_WIDTH_PX);
            }

            if (!embedded) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setFontSize(10);
                r.setText(url);
            }
        }
    }

    // ---------- КАРТИНКИ ----------
    static boolean tryAddInlineImage(XWPFParagraph p, String url, int sizePx) {
        try {
            ImageData img = downloadImage(url);
            if (img == null || img.pictureType == -1) return false;

            int w = sizePx;
            int h = img.scaledHeightPx(w);

            XWPFRun run = p.createRun();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(img.bytes)) {
                run.addPicture(
                        bis,
                        img.pictureType,
                        "emoji",
                        Units.toEMU(pxToPoints(w)),
                        Units.toEMU(pxToPoints(h))
                );
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean tryAddBlockImage(XWPFDocument doc, String url, int targetWidthPx) {
        try {
            ImageData img = downloadImage(url);
            if (img == null || img.pictureType == -1) return false;

            int w = targetWidthPx;
            int h = img.scaledHeightPx(w);

            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();

            try (ByteArrayInputStream bis = new ByteArrayInputStream(img.bytes)) {
                r.addPicture(
                        bis,
                        img.pictureType,
                        "photo",
                        Units.toEMU(pxToPoints(w)),
                        Units.toEMU(pxToPoints(h))
                );
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static class ImageData {
        final byte[] bytes;
        final int pictureType; // Document.PICTURE_TYPE_*
        final int widthPx;
        final int heightPx;

        ImageData(byte[] bytes, int pictureType, int widthPx, int heightPx) {
            this.bytes = bytes;
            this.pictureType = pictureType;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }

        int scaledHeightPx(int targetWidthPx) {
            if (widthPx <= 0 || heightPx <= 0) return targetWidthPx;
            double k = (double) targetWidthPx / (double) widthPx;
            return (int) Math.round(heightPx * k);
        }
    }

    static ImageData downloadImage(String url) throws Exception {
        if (url == null || url.isBlank()) return null;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) return null;

        byte[] bytes = resp.body();
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_IMAGE_BYTES) return null;

        String ct = resp.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        int pictureType = detectPictureType(ct, bytes);
        if (pictureType == -1) return null; // webp/unknown

        int[] wh = readImageSizeSafe(bytes);
        return new ImageData(bytes, pictureType, wh[0], wh[1]);
    }

    static int detectPictureType(String contentType, byte[] bytes) {
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return Document.PICTURE_TYPE_JPEG;
        if (contentType.contains("png")) return Document.PICTURE_TYPE_PNG;
        if (contentType.contains("gif")) return Document.PICTURE_TYPE_GIF;
        if (contentType.contains("bmp")) return Document.PICTURE_TYPE_BMP;

        if (startsWith(bytes, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF})) return Document.PICTURE_TYPE_JPEG;
        if (startsWith(bytes, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47})) return Document.PICTURE_TYPE_PNG;
        if (startsWith(bytes, "GIF8".getBytes())) return Document.PICTURE_TYPE_GIF;

        // WEBP: "RIFF....WEBP" — POI не вставит напрямую
        if (bytes.length > 12) {
            String riff = new String(bytes, 0, 4);
            String webp = new String(bytes, 8, 4);
            if ("RIFF".equals(riff) && "WEBP".equals(webp)) return -1;
        }
        return -1;
    }

    static boolean startsWith(byte[] a, byte[] prefix) {
        if (a == null || prefix == null || a.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (a[i] != prefix[i]) return false;
        return true;
    }

    static int[] readImageSizeSafe(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            var img = javax.imageio.ImageIO.read(bis);
            if (img == null) return new int[]{0, 0};
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    static double pxToPoints(int px) {
        return px * 72.0 / 96.0; // 96dpi
    }

    // ---------- ДАТЫ ----------
    static ParsedDate parseVkDate(String dateRaw) {
        if (dateRaw == null) return null;
        String s = dateRaw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;

        Matcher m = VK_DATE.matcher(s);
        if (!m.matches()) return null;

        int day = Integer.parseInt(m.group(1));
        String abbr = m.group(2);

        Integer month = MONTH_ABBR_TO_NUM.get(abbr);
        if (month == null) return null;

        int year = Integer.parseInt(m.group(3));

        boolean hasTime = (m.group(4) != null && m.group(5) != null);
        int hour = 0, minute = 0;
        if (hasTime) {
            hour = Integer.parseInt(m.group(4));
            minute = Integer.parseInt(m.group(5));
        }
        return new ParsedDate(day, month, year, hasTime, hour, minute);
    }

    static String safeTitle(String dateRaw) {
        if (dateRaw != null && !dateRaw.isBlank()) return dateRaw.trim();
        return "Без даты";
    }

    // ---------- URL ----------
    static String normalizeVkUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("/")) return VK_BASE + u;
        return u;
    }

    // ---------- DOCX helpers ----------
    static void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Title");
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(18);
        r.setText(text);
    }

    static void addMeta(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(10);
        r.setText(text);
    }

    static void addHeading(XWPFDocument doc, int level, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(level == 1 ? 16 : (level == 2 ? 14 : 12));
        r.setText(text);
    }

    // ---------- misc ----------
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    static List<String> toStringList(Object o) {
        if (!(o instanceof List<?> list)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object x : list) {
            if (x == null) continue;
            String s = String.valueOf(x).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static List<String> dedupe(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    static class ParsedDate {
        final int day, month, year;
        final boolean hasTime;
        final int hour, minute;
        ParsedDate(int day, int month, int year, boolean hasTime, int hour, int minute) {
            this.day = day;
            this.month = month;
            this.year = year;
            this.hasTime = hasTime;
            this.hour = hour;
            this.minute = minute;
        }
    }

    static class Entry {
        final String dateRaw;
        final ParsedDate parsed;
        final String postId;
        final String text;
        final List<String> photos;

        Entry(String dateRaw, ParsedDate parsed, String postId, String text, List<String> photos) {
            this.dateRaw = dateRaw;
            this.parsed = parsed;
            this.postId = postId;
            this.text = text;
            this.photos = photos;
        }
    }
}
