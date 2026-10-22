package com.amflymelyric;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Third-party lyric aggregation.
 *
 * Several providers are searched in parallel (NetEase / QQ Music / Kugou / AMLL TTML DB);
 * the winner is picked by timing granularity first (karaoke/word-level beats line-level)
 * and provider priority second, mirroring the SPW-Lyrics plugin's LyricsSelectionPolicy.
 */
public final class ThirdPartyLyrics {

    public static final String TAG = "AMFlymeLyric";

    public static final int Q_PLAIN = 0;
    public static final int Q_LINE = 1;
    public static final int Q_KARAOKE = 2;

    private static final long TOTAL_BUDGET_MS = 9000L;
    /** after a karaoke result arrives, keep the window open a moment for higher-priority sources */
    private static final long KARAOKE_GRACE_MS = 1200L;

    private static volatile String sCacheDir;

    private ThirdPartyLyrics() { }

    /** route provider diagnostics through the module's xposed logger */
    public interface Logger { void log(String message); }

    private static volatile Logger sLogger;

    public static void setLogger(Logger logger) { sLogger = logger; }

    static void logLine(String message) {
        Logger l = sLogger;
        if (l != null) {
            try { l.log(message); } catch (Throwable ignore) { }
        }
    }

    /** files dir the module owns, used by providers that keep an on-disk index */
    public static void setCacheDir(String dir) { sCacheDir = dir; }

    static String cacheDir() { return sCacheDir; }

    // ------------------------------------------------------------------ model

    public static final class Track {
        public String id = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public long durationMs = 0L;

        @Override public String toString() {
            return "\"" + title + "\" / " + artist + " / " + album + " / " + durationMs + "ms";
        }
    }

    public static final class Word {
        public long begin;
        public long end;
        public String text;
    }

    public static final class Line {
        public long begin;
        public long end;
        public String text;
        public ArrayList<Word> words;
    }

    public static final class Result {
        public String provider = "";
        public int quality = Q_PLAIN;
        public double score = 0;
        public long remoteId;
        public long durationMs;
        public String matchedTitle = "";
        public String matchedArtist = "";
        public ArrayList<Line> lines = new ArrayList<Line>();
        public String ttml = "";
        public String ttmlLine = "";
        public int lineCount;

        @Override public String toString() {
            return provider + ":" + remoteId + " \"" + matchedTitle + "\" / " + matchedArtist
                    + " lines=" + lineCount + " quality=" + qualityName(quality)
                    + " score=" + String.format(Locale.ROOT, "%.2f", score);
        }
    }

    public static String qualityName(int q) {
        if (q == Q_KARAOKE) return "karaoke";
        if (q == Q_LINE) return "line";
        return "plain";
    }

    /** one lyric source (netease / qq / kugou / amll) */
    public interface Source {
        String name();
        int priority();
        Result resolve(Track track) throws Exception;
    }

    // ------------------------------------------------------------------ orchestration

    public static Result resolve(final Track track) throws Exception {
        if (track == null || isBlank(track.title)) throw new IllegalArgumentException("empty track");
        List<Source> sources = LyricSources.create();
        final Object lock = new Object();
        final ArrayList<Result> done = new ArrayList<Result>();
        final ArrayList<String> errors = new ArrayList<String>();
        final int total = sources.size();
        for (int i = 0; i < total; i++) {
            final Source s = sources.get(i);
            Thread th = new Thread(new Runnable() {
                public void run() {
                    try {
                        Result r = s.resolve(track);
                        if (r != null && r.lines.size() >= 2) {
                            if (!plausible(track, r)) {
                                logLine("third-party source " + s.name() + " -> implausible match, rejected: " + r);
                                synchronized (lock) { errors.add(s.name() + ":implausible"); lock.notifyAll(); }
                                return;
                            }
                            logLine("third-party source " + s.name() + " -> " + r);
                            synchronized (lock) { done.add(r); lock.notifyAll(); }
                        } else {
                            logLine("third-party source " + s.name() + " -> none");
                            synchronized (lock) { errors.add(s.name() + ":empty"); lock.notifyAll(); }
                        }
                    } catch (Throwable t) {
                        logLine("third-party source " + s.name() + " failed: " + t);
                        synchronized (lock) { errors.add(s.name() + ":" + t); lock.notifyAll(); }
                    }
                }
            }, "AMFlymeLyric-third-" + s.name());
            th.setDaemon(true);
            th.start();
        }
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        long karaokeSeenAt = 0L;
        synchronized (lock) {
            while (true) {
                Result best = pick(done);
                if (best != null && best.quality >= Q_KARAOKE) {
                    if (karaokeSeenAt == 0L) {
                        karaokeSeenAt = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - karaokeSeenAt >= KARAOKE_GRACE_MS) {
                        break;
                    }
                }
                if (done.size() + errors.size() >= total) break;
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                try { lock.wait(Math.min(wait, 250)); } catch (InterruptedException e) { break; }
            }
        }
        Result best;
        synchronized (lock) { best = pick(done); }
        if (best == null) throw new IllegalStateException("no third-party lyric matched (" + errors + ")");
        return best;
    }

    /** quality first (karaoke > line > plain), then provider priority, then match score */
    private static Result pick(List<Result> all) {
        Result best = null;
        for (int i = 0; i < all.size(); i++) {
            Result r = all.get(i);
            if (best == null) { best = r; continue; }
            if (r.quality != best.quality) {
                if (r.quality > best.quality) best = r;
                continue;
            }
            int pr = priorityOf(r.provider);
            int pb = priorityOf(best.provider);
            if (pr != pb) { if (pr < pb) best = r; continue; }
            if (r.score > best.score) best = r;
        }
        return best;
    }

    private static int priorityOf(String provider) {
        if ("amll".equals(provider)) return 0;
        if ("qq".equals(provider)) return 1;
        if ("kugou".equals(provider)) return 2;
        if ("netease".equals(provider)) return 3;
        return 9;
    }

    // ------------------------------------------------------------------ result assembly

    /** classify karaoke vs line-synced and build the two TTML variants */
    public static Result finish(Result r) {
        if (r == null) return null;
        r.lineCount = r.lines.size();
        if (r.lineCount == 0) return r;
        r.quality = classify(r.lines);
        r.ttmlLine = buildTtml(r, false);
        r.ttml = r.quality >= Q_KARAOKE ? buildTtml(r, true) : r.ttmlLine;
        return r;
    }

    private static int classify(List<Line> lines) {
        int timed = 0;
        int worded = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (isBlank(l.text)) continue;
            timed++;
            if (l.words != null && l.words.size() > 0 && validWords(l.words)) worded++;
        }
        if (timed == 0) return Q_PLAIN;
        if (worded == 0) return Q_LINE;
        return worded * 10 >= timed * 8 ? Q_KARAOKE : Q_LINE;
    }

    private static boolean validWords(List<Word> words) {
        long prevStart = Long.MIN_VALUE;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (w == null || w.text == null) return false;
            if (w.begin > w.end) return false;
            if (w.begin < prevStart) return false;
            prevStart = w.begin;
        }
        return true;
    }

    // ------------------------------------------------------------------ ttml

    private static String buildTtml(Result r, boolean wordLevel) {
        Track t = new Track();
        t.title = r.matchedTitle == null || r.matchedTitle.length() == 0 ? "" : r.matchedTitle;
        t.artist = r.matchedArtist == null ? "" : r.matchedArtist;
        long dur = 0;
        for (int i = 0; i < r.lines.size(); i++) {
            long e = r.lines.get(i).end;
            if (e > dur) dur = e;
        }
        if (dur <= 0) dur = r.lines.get(r.lines.size() - 1).begin + 5000;
        StringBuilder sb = new StringBuilder(1024 + r.lines.size() * 160);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<tt xmlns=\"http://www.w3.org/ns/ttml\"");
        sb.append(" xmlns:itunes=\"http://music.apple.com/lyric-ttml-internal\"");
        sb.append(" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"");
        sb.append(" itunes:timing=\"").append(wordLevel ? "Word" : "Line").append("\" xml:space=\"preserve\">\n");
        sb.append("<head><metadata>");
        sb.append("<ttm:title>").append(esc(t.title)).append("</ttm:title>");
        sb.append("<ttm:agent type=\"person\" xml:id=\"v1\"><ttm:name>")
                .append(esc(t.artist)).append("</ttm:name></ttm:agent>");
        sb.append("</metadata></head>\n");
        sb.append("<body dur=\"").append(clock(dur)).append("\"><div begin=\"")
                .append(clock(0)).append("\" end=\"").append(clock(dur)).append("\">\n");
        for (int i = 0; i < r.lines.size(); i++) {
            Line ln = r.lines.get(i);
            sb.append("<p begin=\"").append(clock(ln.begin)).append("\" end=\"").append(clock(ln.end))
                    .append("\" ttm:agent=\"v1\" itunes:key=\"L").append(i + 1).append("\">");
            if (wordLevel && ln.words != null && ln.words.size() > 0) {
                for (int k = 0; k < ln.words.size(); k++) {
                    Word w = ln.words.get(k);
                    sb.append("<span begin=\"").append(clock(w.begin)).append("\" end=\"")
                            .append(clock(w.end)).append("\">").append(esc(w.text)).append("</span>");
                }
            } else {
                sb.append(esc(ln.text));
            }
            sb.append("</p>\n");
        }
        sb.append("</div></body></tt>\n");
        return sb.toString();
    }

    private static String clock(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3600000L;
        long m = (ms / 60000L) % 60;
        long s = (ms / 1000L) % 60;
        long f = ms % 1000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, f);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** crude timed-line check used to decide whether an official TTML is good enough */
    public static boolean hasTimedLines(String ttml) {
        if (ttml == null) return false;
        int i = 0;
        while (true) {
            int p = ttml.indexOf("<p", i);
            if (p < 0) return false;
            int gt = ttml.indexOf('>', p);
            if (gt < 0) return false;
            String attrs = ttml.substring(p, gt);
            String b = attr(attrs, "begin");
            if (b != null && b.trim().length() > 0) return true;
            i = gt + 1;
        }
    }

    static String attr(String attrs, String name) {
        int i = attrs.indexOf(name + "=");
        if (i < 0) return null;
        int q = attrs.indexOf('"', i);
        char qc = '"';
        if (q < 0) { q = attrs.indexOf('\'', i); qc = '\''; }
        if (q < 0) return null;
        int e = attrs.indexOf(qc, q + 1);
        if (e < 0) return null;
        return attrs.substring(q + 1, e);
    }

    // ------------------------------------------------------------------ text helpers

    static boolean isBlank(String s) { return s == null || s.trim().length() == 0; }

    static String stripBrackets(String s) {
        if (s == null) return "";
        return s.replaceAll("\\([^)]*\\)|\\[[^]]*]|（[^）]*）|【[^】]*】", " ").trim();
    }

    static String normalize(String s) {
        if (s == null) return "";
        s = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** append a karaoke segment, restoring the spaces that per-word sources split away */
    static void appendWordText(StringBuilder sb, String seg) {
        if (seg == null || seg.length() == 0) return;
        if (sb.length() > 0) {
            char prev = sb.charAt(sb.length() - 1);
            char next = seg.charAt(0);
            boolean prevLatin = isLatin(prev);
            boolean nextLatin = isLatin(next);
            if (prevLatin && nextLatin) sb.append(' ');
        }
        sb.append(seg);
    }

    private static boolean isLatin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** strip inline timestamps but keep the segment's own spacing (word-level sources rely on it) */
    static String cleanWord(String s) {
        if (s == null) return "";
        s = s.replace("&#10;", " ").replace("&#13;", " ");
        return s.replaceAll("<\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?>", "");
    }

    static String cleanLineText(String s) {
        if (s == null) return "";
        s = s.replace("&#10;", " ").replace("&#13;", " ");
        s = s.replaceAll("<\\d{1,4}:\\d{1,2}(?:[.:]\\d{1,3})?>", "");
        return s.trim();
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");

    /** parse a plain LRC document (line timing only) */
    static ArrayList<Line> parseLrc(String raw, long durationMs) {
        ArrayList<Line> out = new ArrayList<Line>();
        if (raw == null) return out;
        raw = raw.replace("\r", "").replace("\uFEFF", "");
        long offset = 0L;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            if (line.startsWith("[offset:")) {
                try { offset = Long.parseLong(line.substring(8, line.indexOf(']')).trim()); } catch (Throwable ignore) { }
                continue;
            }
            ArrayList<Long> stamps = new ArrayList<Long>();
            Matcher m = STAMP.matcher(line);
            int cursor = 0;
            while (m.find()) {
                stamps.add(stampOf(m));
                cursor = m.end();
            }
            if (stamps.isEmpty()) continue;
            String text = cleanLineText(line.substring(cursor));
            text = text.replaceAll("\\[\\d+,\\d+]", "").trim();
            if (text.length() == 0) continue;
            for (int k = 0; k < stamps.size(); k++) {
                Line ln = new Line();
                ln.begin = Math.max(0, stamps.get(k).longValue() + offset);
                ln.text = text;
                out.add(ln);
            }
        }
        finishLineEnds(out, durationMs);
        return out;
    }

    private static long stampOf(Matcher m) {
        long min = Long.parseLong(m.group(1));
        long sec = Long.parseLong(m.group(2));
        String f = m.group(3) == null ? "0" : m.group(3);
        if (f.length() > 3) f = f.substring(0, 3);
        while (f.length() < 3) f = f + "0";
        return min * 60000L + sec * 1000L + Long.parseLong(f);
    }

    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+),(\\d+)\\)");

    /** NetEase YRC: [lineStart,lineDur](offset,dur,0)word(offset,dur,0)word... */
    static ArrayList<Line> parseYrc(String raw, long durationMs) {
        ArrayList<Line> out = new ArrayList<Line>();
        if (raw == null) return out;
        raw = raw.replace("\r", "").replace("\uFEFF", "");
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            Matcher lm = YRC_LINE.matcher(line);
            if (!lm.find()) continue;
            long lineStart = Long.parseLong(lm.group(1));
            long lineDur = Long.parseLong(lm.group(2));
            String rest = line.substring(lm.end());
            ArrayList<int[]> marks = new ArrayList<int[]>();
            Matcher wm = YRC_WORD.matcher(rest);
            while (wm.find()) {
                marks.add(new int[]{ wm.start(), wm.end(), Integer.parseInt(wm.group(1)), Integer.parseInt(wm.group(2)) });
            }
            if (marks.isEmpty()) {
                String text = cleanLineText(rest);
                if (text.length() == 0) continue;
                Line ln = new Line();
                ln.begin = lineStart;
                ln.text = text;
                out.add(ln);
                continue;
            }
            ArrayList<Word> words = new ArrayList<Word>();
            StringBuilder text = new StringBuilder();
            for (int k = 0; k < marks.size(); k++) {
                int[] cur = marks.get(k);
                int textEnd = k + 1 < marks.size() ? marks.get(k + 1)[0] : rest.length();
                String seg = cleanWord(rest.substring(cur[1], textEnd));
                if (seg.trim().length() == 0) continue;
                Word w = new Word();
                w.begin = lineStart + cur[2];
                w.end = w.begin + Math.max(1, cur[3]);
                w.text = seg;
                words.add(w);
                appendWordText(text, seg);
            }
            if (words.isEmpty()) continue;
            Line ln = new Line();
            ln.begin = lineStart;
            ln.end = Math.max(lineStart + Math.max(1, lineDur), words.get(words.size() - 1).end);
            ln.text = text.toString();
            ln.words = words;
            out.add(ln);
        }
        sortAndFixEnds(out, durationMs);
        return out;
    }

    static void sortAndFixEnds(ArrayList<Line> out, long durationMs) {
        java.util.Collections.sort(out, new java.util.Comparator<Line>() {
            public int compare(Line a, Line b) { return Long.compare(a.begin, b.begin); }
        });
        finishLineEnds(out, durationMs);
    }

    static void finishLineEnds(ArrayList<Line> out, long durationMs) {
        for (int i = 0; i < out.size(); i++) {
            Line ln = out.get(i);
            long end = ln.end;
            if (ln.words != null && ln.words.size() > 0) {
                long we = ln.words.get(ln.words.size() - 1).end;
                if (we > end) end = we;
            }
            if (i + 1 < out.size()) {
                long next = out.get(i + 1).begin;
                if (end <= ln.begin || end > next) end = next;
            } else if (end <= ln.begin) {
                end = ln.begin + 6000;
            }
            if (durationMs > 0 && ln.begin > durationMs + 5000) continue;
            ln.end = Math.max(ln.begin + 200, end);
        }
    }

    /** drop credit lines and intro title cards, keeping the word timing of the remaining lines */
    static void dropCredits(ArrayList<Line> lines, String title, String artist) {
        if (lines.size() <= 8) return;
        String nt = normalize(stripBrackets(title));
        String na = normalize(artist);
        ArrayList<Line> kept = new ArrayList<Line>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.begin <= 30000L && isCredit(l.text)) continue;
            if (i < 6 && nt.length() >= 3) {
                String n = normalize(l.text);
                boolean hasTitle = n.contains(nt) || (na.length() >= 2 && n.contains(na));
                if (hasTitle && (l.text.indexOf(" - ") > 0 || l.text.indexOf("—") > 0
                        || (na.length() >= 2 && n.contains(na)))) {
                    continue;
                }
            }
            kept.add(l);
        }
        if (kept.size() >= 8) {
            lines.clear();
            lines.addAll(kept);
        }
    }

    private static final String[] CREDIT_WORDS = {
            "出品", "演唱", "词曲", "作词", "作曲", "编曲", "制作人", "录音", "混音", "母带",
            "监制", "和声", "吉他", "贝斯", "键盘", "弦乐", "企划", "发行", "策划", "统筹",
            "原唱", "特别鸣谢", "编辑", "配唱", "人声", "器乐", "演奏", "后期", "调校",
            "未经许可", "翻录", "著作人", "鼓", "钢琴", "小提琴", "大提琴", "萨克斯", "合成器",
            "lyrics by", "composed by", "produced by", "mixed by", "mastered by", "written by",
            "arranged by", "performed by"
    };

    private static boolean isCredit(String s) {
        if (s == null) return false;
        String t = s.replace(" ", "").replace("：", ":").toLowerCase(Locale.ROOT);
        if (t.length() == 0 || t.length() > 90) return false;
        for (int i = 0; i < CREDIT_WORDS.length; i++) {
            String w = CREDIT_WORDS[i].replace(" ", "").replace("：", ":").toLowerCase(Locale.ROOT);
            if (t.contains(w)) return true;
        }
        int c = t.indexOf(':');
        return c > 0 && c <= 12;
    }

    // ------------------------------------------------------------------ codec helpers (krc / qrc / ttml)

    static byte[] inflate(byte[] deflated) throws Exception {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(deflated);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(deflated.length * 4 + 64);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
            }
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    static byte[] hexDecode(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String unescapeXml(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length());
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c == '&') {
                int semi = v.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = v.substring(i + 1, semi);
                    if (ent.startsWith("#x") || ent.startsWith("#X")) {
                        try { sb.append((char) Integer.parseInt(ent.substring(2), 16)); i = semi + 1; continue; } catch (Throwable ignore) { }
                    } else if (ent.startsWith("#")) {
                        try { sb.append((char) Integer.parseInt(ent.substring(1))); i = semi + 1; continue; } catch (Throwable ignore) { }
                    } else if ("amp".equals(ent)) { sb.append('&'); i = semi + 1; continue; }
                    else if ("lt".equals(ent)) { sb.append('<'); i = semi + 1; continue; }
                    else if ("gt".equals(ent)) { sb.append('>'); i = semi + 1; continue; }
                    else if ("quot".equals(ent)) { sb.append('"'); i = semi + 1; continue; }
                    else if ("apos".equals(ent)) { sb.append('\''); i = semi + 1; continue; }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    static String stripTags(String s) {
        if (s == null) return "";
        return unescapeXml(s.replaceAll("<[^>]*>", "")).trim();
    }

    private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern KRC_WORD = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");

    /** Kugou KRC (already decrypted): [lineStart,lineDur]<offset,dur,0>word... */
    static ArrayList<Line> parseKrc(String raw, long durationMs) {
        ArrayList<Line> out = new ArrayList<Line>();
        if (raw == null) return out;
        raw = raw.replace("\r", "").replace("\uFEFF", "");
        String[] rows = raw.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.length() == 0 || row.startsWith("[language:") || row.startsWith("[ti:") || row.startsWith("[ar:")) continue;
            Matcher lm = KRC_LINE.matcher(row);
            if (!lm.find()) continue;
            long lineStart = Long.parseLong(lm.group(1));
            long lineDur = Long.parseLong(lm.group(2));
            String rest = lm.group(3);
            ArrayList<Word> words = new ArrayList<Word>();
            StringBuilder text = new StringBuilder();
            Matcher wm = KRC_WORD.matcher(rest);
            while (wm.find()) {
                String seg = cleanWord(wm.group(3));
                long off = Long.parseLong(wm.group(1));
                long dur = Long.parseLong(wm.group(2));
                if (seg.trim().length() == 0) continue;
                Word w = new Word();
                w.begin = lineStart + off;
                w.end = w.begin + Math.max(1, dur);
                w.text = seg;
                words.add(w);
                appendWordText(text, seg);
            }
            Line ln = new Line();
            ln.begin = lineStart;
            ln.end = lineStart + Math.max(1, lineDur);
            if (words.isEmpty()) {
                ln.text = cleanLineText(stripTags(rest));
                if (ln.text.length() == 0) continue;
            } else {
                ln.text = text.toString().trim();
                ln.words = words;
            }
            out.add(ln);
        }
        sortAndFixEnds(out, durationMs);
        return out;
    }

    private static final Pattern QRC_CONTENT = Pattern.compile("LyricContent=\"(.*?)\"\\s*/>", Pattern.DOTALL);
    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern QRC_WORD = Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)");

    /** QQ QRC (already decrypted) or its plain-LRC fallback payload */
    static ArrayList<Line> parseQrc(String raw, long durationMs) {
        ArrayList<Line> out = new ArrayList<Line>();
        if (raw == null) return out;
        String content = raw;
        if (content.contains("LyricContent=")) {
            Matcher m = QRC_CONTENT.matcher(content);
            if (m.find()) content = unescapeXml(m.group(1));
        }
        content = content.replace("\r", "").replace("\uFEFF", "");
        String[] rows = content.split("\n");
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.length() == 0) continue;
            Matcher lm = QRC_LINE.matcher(row);
            if (!lm.find()) continue;
            long lineStart = Long.parseLong(lm.group(1));
            long lineDur = Long.parseLong(lm.group(2));
            String rest = lm.group(3);
            ArrayList<Word> words = new ArrayList<Word>();
            StringBuilder text = new StringBuilder();
            Matcher wm = QRC_WORD.matcher(rest);
            while (wm.find()) {
                String seg = cleanWord(wm.group(1));
                if (seg.trim().length() == 0) continue;
                long start = Long.parseLong(wm.group(2));
                long dur = Long.parseLong(wm.group(3));
                Word w = new Word();
                w.begin = start;
                w.end = start + Math.max(1, dur);
                w.text = seg;
                words.add(w);
                appendWordText(text, seg);
            }
            Line ln = new Line();
            ln.begin = lineStart;
            ln.end = lineStart + Math.max(1, lineDur);
            if (words.isEmpty()) {
                ln.text = cleanLineText(stripTags(rest));
                if (ln.text.length() == 0) continue;
            } else {
                ln.text = text.toString();
                ln.words = words;
            }
            out.add(ln);
        }
        if (out.size() >= 2) {
            sortAndFixEnds(out, durationMs);
            return out;
        }
        return parseLrc(content, durationMs);
    }

    // ------------------------------------------------------------------ amll ttml

    private static final Pattern TTML_P = Pattern.compile("<p\\b([^>]*)>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern TTML_SPAN = Pattern.compile("<span\\b([^>]*)>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern TTML_TIME = Pattern.compile("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:[.,](\\d+))?$");
    private static final Pattern TTML_TIME2 = Pattern.compile("^(\\d+):(\\d{1,2})(?:[.,](\\d+))?$");
    private static final Pattern TTML_TIME3 = Pattern.compile("^([0-9.]+)(ms|s|m|h)$");
    private static final Pattern TTML_TIME4 = Pattern.compile("^([0-9.]+)$");

    static long ttmlTime(String v) {
        if (v == null) return -1;
        v = v.trim();
        if (v.length() == 0) return -1;
        Matcher m = TTML_TIME.matcher(v);
        if (m.matches()) return Long.parseLong(m.group(1)) * 3600000L + Long.parseLong(m.group(2)) * 60000L
                + Long.parseLong(m.group(3)) * 1000L + frac(m.group(4));
        m = TTML_TIME2.matcher(v);
        if (m.matches()) return Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L + frac(m.group(3));
        m = TTML_TIME3.matcher(v);
        if (m.matches()) {
            double mul = "ms".equals(m.group(2)) ? 1d : "s".equals(m.group(2)) ? 1000d : "m".equals(m.group(2)) ? 60000d : 3600000d;
            return (long) (Double.parseDouble(m.group(1)) * mul);
        }
        m = TTML_TIME4.matcher(v);
        if (m.matches()) return (long) (Double.parseDouble(v) * 1000d);
        return -1;
    }

    private static long frac(String f) {
        if (f == null) return 0;
        if (f.length() > 3) f = f.substring(0, 3);
        while (f.length() < 3) f = f + "0";
        return Long.parseLong(f);
    }

    private static final Pattern TTML_TAG = Pattern.compile("<[^>]*>");

    /** AMLL TTML DB documents: <p begin end><span begin end>word</span>... (+ x-translation / x-roman / x-bg spans) */
    static ArrayList<Line> parseTtmlDocument(String xml, long durationMs) {
        ArrayList<Line> out = new ArrayList<Line>();
        if (xml == null) return out;
        Matcher pm = TTML_P.matcher(xml);
        while (pm.find()) {
            String attrs = pm.group(1);
            String inner = pm.group(2);
            String role = attr(attrs, "role");
            if (role != null) {
                String r = role.toLowerCase(Locale.ROOT);
                if (r.contains("translation") || r.contains("roman") || r.contains("translit") || r.contains("bg")) continue;
            }
            long begin = ttmlTime(attr(attrs, "begin"));
            long end = ttmlTime(attr(attrs, "end"));
            long dur = ttmlTime(attr(attrs, "dur"));
            ParsedSpan parsed = parseSpans(inner);
            ArrayList<Word> words = parsed.words;
            String plain = parsed.text;
            if (plain.length() == 0) continue;
            if (begin < 0 && !words.isEmpty()) begin = words.get(0).begin;
            if (end < 0 && dur > 0 && begin >= 0) end = begin + dur;
            if (end < 0 && !words.isEmpty()) end = words.get(words.size() - 1).end;
            Line ln = new Line();
            ln.begin = Math.max(0, begin);
            ln.end = end > ln.begin ? end : ln.begin + 4000;
            ln.text = plain;
            if (!words.isEmpty()) ln.words = words;
            out.add(ln);
        }
        if (out.isEmpty()) return out;
        sortAndFixEnds(out, durationMs);
        return out;
    }

    static final class ParsedSpan {
        ArrayList<Word> words = new ArrayList<Word>();
        String text = "";
    }

    /** walk one paragraph, collecting timed word spans while skipping translation/romanization/background spans */
    private static ParsedSpan parseSpans(String inner) {
        ParsedSpan out = new ParsedSpan();
        StringBuilder lineText = new StringBuilder();
        ArrayList<StringBuilder> wordBuffers = new ArrayList<StringBuilder>();
        ArrayList<Word> openWords = new ArrayList<Word>();
        ArrayList<Boolean> skipStack = new ArrayList<Boolean>();
        Matcher tm = TTML_TAG.matcher(inner);
        int last = 0;
        while (tm.find()) {
            String chunk = inner.substring(last, tm.start());
            last = tm.end();
            if (chunk.length() > 0 && currentSkip(skipStack) == 0) {
                String text = unescapeXml(chunk);
                if (wordBuffers.isEmpty()) appendWordText(lineText, text);
                else wordBuffers.get(wordBuffers.size() - 1).append(text);
            }
            String tag = tm.group();
            if (tag.startsWith("</")) {
                if (!skipStack.isEmpty()) {
                    boolean wasSkip = skipStack.remove(skipStack.size() - 1).booleanValue();
                    if (!wasSkip && !openWords.isEmpty()) {
                        Word w = openWords.remove(openWords.size() - 1);
                        StringBuilder buf = wordBuffers.remove(wordBuffers.size() - 1);
                        String text = cleanWord(buf.toString()).trim();
                        if (text.length() > 0) {
                            w.text = text;
                            out.words.add(w);
                            appendWordText(lineText, text);
                        }
                    }
                }
                continue;
            }
            String name = tagName(tag);
            boolean isSpan = "span".equals(name);
            if ("br".equals(name)) {
                if (currentSkip(skipStack) == 0) appendWordText(lineText, " ");
                continue;
            }
            String tagRole = attr(tag, "role");
            boolean skip = false;
            if (tagRole != null) {
                String r = tagRole.toLowerCase(Locale.ROOT);
                skip = r.contains("translation") || r.contains("roman") || r.contains("translit") || r.contains("bg");
            }
            skipStack.add(Boolean.valueOf(skip));
            if (isSpan && !skip && currentSkip(skipStack) == 0) {
                long sb = ttmlTime(attr(tag, "begin"));
                long se = ttmlTime(attr(tag, "end"));
                long sd = ttmlTime(attr(tag, "dur"));
                if (se < 0 && sd > 0 && sb >= 0) se = sb + sd;
                if (sb >= 0 && se >= sb) {
                    Word w = new Word();
                    w.begin = sb;
                    w.end = se;
                    w.text = "";
                    openWords.add(w);
                    wordBuffers.add(new StringBuilder());
                }
            }
        }
        String text = lineText.toString().trim();
        out.text = text.replaceAll("[\\t\\r ]+", " ");
        return out;
    }

    private static int currentSkip(ArrayList<Boolean> stack) {
        int depth = 0;
        for (int i = 0; i < stack.size(); i++) if (stack.get(i).booleanValue()) depth++;
        return depth;
    }

    private static String tagName(String tag) {
        int i = 1;
        while (i < tag.length() && (tag.charAt(i) == '/' || tag.charAt(i) == ' ')) i++;
        int e = i;
        while (e < tag.length()) {
            char c = tag.charAt(e);
            if (c == ' ' || c == '/' || c == '>') break;
            e++;
        }
        return tag.substring(i, e).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ scoring

    private static final String[] BAD_WORDS = {
            "live", "remix", "instrumental", "karaoke", "cover", "demo", "acoustic",
            "伴奏", "纯音乐", "翻唱", "现场", "演唱会", "dj版", "dj"
    };

    /** shared title/artist/duration matcher used by every provider (SPW-Lyrics MatchEngine style) */
    static double score(Track t, String songTitle, String songArtist, String songAlbum, long songDurationMs) {
        double s = 0;
        String normTitle = normalize(stripBrackets(t.title));
        String normName = normalize(stripBrackets(songTitle));
        if (normTitle.length() > 0 && normTitle.equals(normName)) s += 0.55;
        else if (normTitle.length() > 2 && (normName.contains(normTitle) || normTitle.contains(normName))) s += 0.38;
        else s += 0.25 * similarity(normTitle, normName);

        String trackArtist = normalize(t.artist);
        String candArtist = normalize(songArtist);
        if (candArtist.length() > 0 && trackArtist.length() > 0) {
            if (candArtist.contains(trackArtist) || trackArtist.contains(candArtist)) s += 0.25;
            else {
                String[] a = candArtist.split("&|,|/|、| ");
                String[] b = trackArtist.split("&|,|/|、| ");
                int hit = 0;
                for (int i = 0; i < b.length; i++) {
                    if (b[i].length() == 0) continue;
                    for (int j = 0; j < a.length; j++) {
                        if (a[j].length() > 0 && (a[j].contains(b[i]) || b[i].contains(a[j]))) { hit++; break; }
                    }
                }
                if (b.length > 0) s += 0.25 * hit / b.length;
            }
        }
        if (t.durationMs > 0 && songDurationMs > 0) {
            long diff = Math.abs(songDurationMs - t.durationMs);
            if (diff <= 1000) s += 0.2;
            else if (diff <= 3000) s += 0.12;
            else if (diff <= 8000) s += 0.04;
            else s -= 0.05;
        }
        String all = (songTitle + " " + songArtist + " " + (songAlbum == null ? "" : songAlbum)).toLowerCase(Locale.ROOT);
        for (int i = 0; i < BAD_WORDS.length; i++) {
            if (all.contains(BAD_WORDS[i])) { s -= 0.45; break; }
        }
        if (t.title != null) {
            String lt = t.title.toLowerCase(Locale.ROOT);
            for (int i = 0; i < BAD_WORDS.length; i++) {
                if (lt.contains(BAD_WORDS[i])) { s -= 0.25; break; }
            }
        }
        return s;
    }

    /** bigram dice coefficient, works well for CJK and latin titles */
    static double dice(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1.0;
        if (a.length() < 2 || b.length() < 2) return 0;
        java.util.HashMap<String, Integer> grams = new java.util.HashMap<String, Integer>();
        for (int i = 0; i + 1 < a.length(); i++) {
            String g = a.substring(i, i + 2);
            Integer c = grams.get(g);
            grams.put(g, c == null ? 1 : c + 1);
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String g = b.substring(i, i + 2);
            Integer c = grams.get(g);
            if (c != null && c > 0) {
                hit++;
                grams.put(g, c - 1);
            }
            total++;
        }
        if (total == 0) return 0;
        return 2.0 * hit / (Math.max(1, a.length() - 1) + total);
    }

    /** 0..1 share of the query's artist tokens found in the candidate's artist string */
    static double artistOverlap(String queryArtist, String candArtist) {
        String q = normalize(queryArtist);
        String c = normalize(candArtist);
        if (q.length() == 0 || c.length() == 0) return 0;
        if (q.contains(c) || c.contains(q)) return 1.0;
        String[] a = (candArtist == null ? "" : candArtist).toLowerCase(Locale.ROOT).split("&|,|/|、| ");
        String[] b = (queryArtist == null ? "" : queryArtist).toLowerCase(Locale.ROOT).split("&|,|/|、| ");
        int hit = 0;
        int total = 0;
        for (int i = 0; i < b.length; i++) {
            String token = normalize(b[i]);
            if (token.length() < 2) continue;
            total++;
            for (int k = 0; k < a.length; k++) {
                String cand = normalize(a[k]);
                if (cand.length() == 0) continue;
                if (cand.contains(token) || token.contains(cand)) { hit++; break; }
            }
        }
        if (total == 0) return 0;
        return (double) hit / total;
    }

    /** final guard: refuse lyrics whose title/artist clearly belong to another song */
    static boolean plausible(Track t, Result r) {
        if (t == null || r == null) return false;
        return plausibleMatch(t, r.matchedTitle, r.matchedArtist, r.durationMs);
    }

    /** pre-fetch guard shared by every provider */
    static boolean plausibleMatch(Track t, String candTitle, String candArtist, long candDurationMs) {
        if (t == null) return false;
        String a = normalize(stripBrackets(t.title));
        String b = normalize(stripBrackets(candTitle));
        String aRaw = normalize(t.title);
        String bRaw = normalize(candTitle);
        if (a.length() == 0 && aRaw.length() == 0) return false;
        if (b.length() == 0 && bRaw.length() == 0) return false;
        double sim = dice(a, b);
        boolean titleEquals = a.length() > 0 && a.equals(b);
        // providers often carry a translated title in brackets ("Brand New Sky (新世界的天空)"),
        // so compare both the bracketed and the plain forms in either direction
        boolean titleContains = containsEither(a, b) || containsEither(aRaw, bRaw)
                || containsEither(a, bRaw) || containsEither(aRaw, b);
        double artist = artistOverlap(t.artist, candArtist);
        boolean titleWeak = !titleEquals && !titleContains && sim < 0.72;
        if (titleWeak) {
            // titles may be translated ("Brand New Sky" vs "新世界的天空"); allow it only when the
            // artist matches exactly and the durations line up
            if (artist < 0.9) return false;
            if (t.durationMs > 0 && candDurationMs > 0 && Math.abs(t.durationMs - candDurationMs) > 3000L) return false;
            return true;
        }
        if (artist >= 0.34) return true;
        return titleEquals && sim >= 0.85;
    }

    static boolean containsEither(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() < 2 || b.length() < 2) return false;
        return a.contains(b) || b.contains(a);
    }

    private static double similarity(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return 0;
        int common = 0;
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) if (a.charAt(i) == b.charAt(i)) common++;
        return (double) common / Math.max(a.length(), b.length());
    }

    static double threshold() { return 0.28; }
}
