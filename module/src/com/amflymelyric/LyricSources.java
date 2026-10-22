package com.amflymelyric;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** HTTP helpers plus the concrete lyric providers (amll / qq / kugou / netease). */
final class LyricSources {

    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int CONNECT_MS = 5000;
    private static final int READ_MS = 7000;

    private LyricSources() { }

    static List<ThirdPartyLyrics.Source> create() {
        ArrayList<ThirdPartyLyrics.Source> list = new ArrayList<ThirdPartyLyrics.Source>();
        list.add(new AmllSource());
        list.add(new QqSource());
        list.add(new KugouSource());
        list.add(new NeteaseSource());
        return list;
    }

    // ------------------------------------------------------------------ http

    static String http(String url, String referer, String cookie, String userAgent, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", userAgent == null ? UA_PC : userAgent);
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            try { in.close(); } catch (Throwable ignore) { }
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return out.toString("UTF-8");
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    static String httpPost(String url, String body, String referer, String userAgent, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", userAgent == null ? UA_PC : userAgent);
        if (referer != null) c.setRequestProperty("Referer", referer);
        c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        c.setRequestProperty("Accept", "*/*");
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            try { in.close(); } catch (Throwable ignore) { }
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return out.toString("UTF-8");
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) { }
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    static String joinArtists(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name", "");
            if (n.length() == 0) continue;
            if (sb.length() > 0) sb.append(" & ");
            sb.append(n);
        }
        return sb.toString();
    }

    static long durationMs(JSONObject song, String key, boolean seconds) {
        long v = song == null ? 0L : song.optLong(key, 0L);
        if (v <= 0) return 0L;
        return seconds ? v * 1000L : v;
    }

    static int[] rankAll(final ThirdPartyLyrics.Track t, String[] titles, String[] artists, String[] albums, long[] durs) {
        final double[] scores = new double[titles.length];
        Integer[] idx = new Integer[titles.length];
        for (int i = 0; i < titles.length; i++) {
            scores[i] = ThirdPartyLyrics.score(t, titles[i], artists[i], albums[i], durs[i]);
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return Double.compare(scores[b], scores[a]); }
        });
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = idx[i];
        return out;
    }

    static ThirdPartyLyrics.Result build(String provider, long remoteId, double score,
                                         String title, String artist, List<ThirdPartyLyrics.Line> lines) {
        return build(provider, remoteId, score, title, artist, lines, 0L);
    }

    static ThirdPartyLyrics.Result build(String provider, long remoteId, double score,
                                         String title, String artist, List<ThirdPartyLyrics.Line> lines,
                                         long durationMs) {
        if (lines == null || lines.size() < 2) return null;
        ThirdPartyLyrics.dropCredits((ArrayList<ThirdPartyLyrics.Line>) lines, title, artist);
        ThirdPartyLyrics.Result r = new ThirdPartyLyrics.Result();
        r.provider = provider;
        r.remoteId = remoteId;
        r.score = score;
        r.durationMs = durationMs;
        r.matchedTitle = title == null ? "" : title;
        r.matchedArtist = artist == null ? "" : artist;
        r.lines = new ArrayList<ThirdPartyLyrics.Line>(lines);
        return ThirdPartyLyrics.finish(r);
    }

    // ------------------------------------------------------------------ amll

    static final class AmllSource implements ThirdPartyLyrics.Source {
        static final String[] INDEX_URLS = {
                "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics/index.jsonl",
                "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics/index.jsonl"
        };
        static final String[] RAW_BASES = {
                "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/am-lyrics",
                "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/am-lyrics"
        };
        private static final long TTL_MS = 7L * 24 * 3600 * 1000;

        private static volatile ArrayList<AmllRecord> sRecords;

        public String name() { return "amll"; }
        public int priority() { return 0; }

        public ThirdPartyLyrics.Result resolve(ThirdPartyLyrics.Track t) throws Exception {
            ArrayList<AmllRecord> records = records();
            if (records == null || records.isEmpty()) return null;
            String wantTitle = ThirdPartyLyrics.normalize(ThirdPartyLyrics.stripBrackets(t.title));
            String wantTitleRaw = ThirdPartyLyrics.normalize(t.title);
            AmllRecord best = null;
            double bestScore = 0;
            for (int i = 0; i < records.size(); i++) {
                AmllRecord rec = records.get(i);
                double titleSim = titleSimilarity(wantTitle, wantTitleRaw, rec.titles);
                double artistSim = ThirdPartyLyrics.artistOverlap(t.artist, rec.artistText());
                double sc = 0.7 * titleSim + 0.3 * artistSim;
                if (sc > bestScore) { bestScore = sc; best = rec; }
            }
            if (best == null || bestScore < 0.5) return null;
            double titleSim = titleSimilarity(wantTitle, wantTitleRaw, best.titles);
            double artistSim = ThirdPartyLyrics.artistOverlap(t.artist, best.artistText());
            ThirdPartyLyrics.logLine("third-party amll candidate id=" + best.id
                    + " title=" + (best.titles.isEmpty() ? "" : best.titles.get(0))
                    + " artist=" + best.artistText()
                    + " titleSim=" + String.format(Locale.ROOT, "%.2f", titleSim)
                    + " artistSim=" + String.format(Locale.ROOT, "%.2f", artistSim));
            if (titleSim < 0.72 && artistSim < 0.68) return null;
            if (titleSim < 0.55) return null;
            if (!ThirdPartyLyrics.plausibleMatch(t, best.titles.isEmpty() ? "" : best.titles.get(0), best.artistText(), 0L)
                    && titleSim < 0.9) {
                return null;
            }
            for (int b = 0; b < RAW_BASES.length; b++) {
                try {
                    String xml = http(RAW_BASES[b] + "/" + best.id + ".ttml", null, null, UA_PC, CONNECT_MS, READ_MS);
                    ArrayList<ThirdPartyLyrics.Line> lines = ThirdPartyLyrics.parseTtmlDocument(xml, t.durationMs);
                    ThirdPartyLyrics.logLine("third-party amll ttml fetched from " + RAW_BASES[b]
                            + " bytes=" + (xml == null ? 0 : xml.length()) + " lines=" + lines.size());
                    ThirdPartyLyrics.Result r = build(name(), 0L, bestScore, best.titles.isEmpty() ? "" : best.titles.get(0),
                            best.artistText(), lines);
                    if (r != null) return r;
                } catch (Throwable ex) {
                    ThirdPartyLyrics.logLine("third-party amll ttml fetch failed (" + RAW_BASES[b] + "): " + ex);
                }
            }
            return null;
        }

        private static synchronized ArrayList<AmllRecord> records() {
            if (sRecords != null) return sRecords;
            long started = System.currentTimeMillis();
            String dir = ThirdPartyLyrics.cacheDir();
            File cache = dir == null ? null : new File(dir, "amll_index.jsonl");
            File compact = dir == null ? null : new File(dir, "amll_index.txt");
            if (compact != null && cache != null && compact.isFile() && compact.length() > 1024
                    && compact.lastModified() >= cache.lastModified()) {
                ArrayList<AmllRecord> compacted = readCompact(compact);
                if (!compacted.isEmpty()) {
                    sRecords = compacted;
                    ThirdPartyLyrics.logLine("third-party amll index: " + compacted.size()
                            + " records from compact cache in " + (System.currentTimeMillis() - started) + "ms");
                    return compacted;
                }
            }
            String content = null;
            if (cache != null && cache.isFile() && cache.length() > 1024
                    && System.currentTimeMillis() - cache.lastModified() < TTL_MS) {
                content = readFile(cache);
            }
            if (content == null) {
                for (int i = 0; i < INDEX_URLS.length && content == null; i++) {
                    try {
                        String body = http(INDEX_URLS[i], null, null, UA_PC, CONNECT_MS, READ_MS);
                        if (body != null && body.length() > 1024 && body.indexOf('{') >= 0) {
                            content = body;
                            if (cache != null) writeFile(cache, body);
                        }
                    } catch (Throwable ignore) { }
                }
                if (content == null && cache != null && cache.isFile()) content = readFile(cache);
            }
            if (content == null) return new ArrayList<AmllRecord>();
            ArrayList<AmllRecord> out = new ArrayList<AmllRecord>();
            String[] rows = content.split("\n");
            for (int i = 0; i < rows.length; i++) {
                String row = rows[i].trim();
                if (row.length() < 2 || row.charAt(0) != '{') continue;
                AmllRecord rec = AmllRecord.parse(row);
                if (rec != null) out.add(rec);
            }
            sRecords = out;
            if (compact != null && !out.isEmpty()) writeCompact(compact, out);
            ThirdPartyLyrics.logLine("third-party amll index: " + out.size()
                    + " records parsed in " + (System.currentTimeMillis() - started) + "ms");
            return out;
        }

        private static double titleSimilarity(String wantTitle, String wantTitleRaw, ArrayList<String> titles) {
            double best = 0;
            for (int i = 0; i < titles.size(); i++) {
                String cand = ThirdPartyLyrics.normalize(ThirdPartyLyrics.stripBrackets(titles.get(i)));
                String candRaw = ThirdPartyLyrics.normalize(titles.get(i));
                double sim = Math.max(ThirdPartyLyrics.dice(wantTitle, cand), ThirdPartyLyrics.dice(wantTitleRaw, candRaw));
                if (ThirdPartyLyrics.containsEither(wantTitle, cand)
                        || ThirdPartyLyrics.containsEither(wantTitleRaw, candRaw)) {
                    sim = Math.max(sim, 0.9);
                }
                if (sim > best) best = sim;
            }
            return best;
        }

        private static ArrayList<AmllRecord> readCompact(File f) {
            ArrayList<AmllRecord> out = new ArrayList<AmllRecord>();
            String content = readFile(f);
            if (content == null) return out;
            String[] rows = content.split("\n");
            for (int i = 0; i < rows.length; i++) {
                String row = rows[i];
                if (row.length() < 3) continue;
                int t1 = row.indexOf('\t');
                if (t1 <= 0) continue;
                int t2 = row.indexOf('\t', t1 + 1);
                int t3 = t2 < 0 ? -1 : row.indexOf('\t', t2 + 1);
                if (t2 < 0 || t3 < 0) continue;
                AmllRecord rec = new AmllRecord();
                rec.id = row.substring(0, t1);
                splitInto(rec.titles, row.substring(t1 + 1, t2));
                splitInto(rec.artists, row.substring(t2 + 1, t3));
                splitInto(rec.albums, row.substring(t3 + 1));
                if (!rec.titles.isEmpty()) out.add(rec);
            }
            return out;
        }

        private static void splitInto(ArrayList<String> dst, String packed) {
            if (packed == null || packed.length() == 0) return;
            int start = 0;
            while (start <= packed.length()) {
                int bar = packed.indexOf('|', start);
                String v = bar < 0 ? packed.substring(start) : packed.substring(start, bar);
                if (v.length() > 0) dst.add(v);
                if (bar < 0) break;
                start = bar + 1;
            }
        }

        private static void writeCompact(File f, ArrayList<AmllRecord> records) {
            StringBuilder sb = new StringBuilder(records.size() * 96);
            for (int i = 0; i < records.size(); i++) {
                AmllRecord r = records.get(i);
                sb.append(r.id).append('\t');
                pack(sb, r.titles);
                sb.append('\t');
                pack(sb, r.artists);
                sb.append('\t');
                pack(sb, r.albums);
                sb.append('\n');
            }
            writeFile(f, sb.toString());
        }

        private static void pack(StringBuilder sb, ArrayList<String> values) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append('|');
                String v = values.get(i).replace('\t', ' ').replace('\n', ' ').replace('|', '/');
                sb.append(v);
            }
        }

        static final class AmllRecord {
            String id = "";
            ArrayList<String> titles = new ArrayList<String>();
            ArrayList<String> artists = new ArrayList<String>();
            ArrayList<String> albums = new ArrayList<String>();

            String artistText() {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < artists.size(); i++) {
                    if (sb.length() > 0) sb.append(" & ");
                    sb.append(artists.get(i));
                }
                return sb.toString();
            }

            String albumText() { return albums.isEmpty() ? "" : albums.get(0); }

            static AmllRecord parse(String row) {
                try {
                    JSONObject o = new JSONObject(row);
                    String id = o.optString("id", "");
                    if (id.length() == 0) return null;
                    AmllRecord rec = new AmllRecord();
                    rec.id = id;
                    JSONArray meta = o.optJSONArray("metadata");
                    if (meta != null) {
                        for (int i = 0; i < meta.length(); i++) {
                            JSONArray pair = meta.optJSONArray(i);
                            if (pair == null || pair.length() < 2) continue;
                            String key = pair.optString(0, "");
                            JSONArray vals = pair.optJSONArray(1);
                            if (vals == null) continue;
                            for (int k = 0; k < vals.length(); k++) {
                                String v = vals.optString(k, "").trim();
                                if (v.length() == 0) continue;
                                if ("musicName".equals(key)) rec.titles.add(v);
                                else if ("artists".equals(key)) rec.artists.add(v);
                                else if ("album".equals(key)) rec.albums.add(v);
                            }
                        }
                    }
                    if (rec.titles.isEmpty()) return null;
                    return rec;
                } catch (Throwable t) {
                    return null;
                }
            }
        }
    }

    // ------------------------------------------------------------------ qq

    static final class QqSource implements ThirdPartyLyrics.Source {
        static final String SEARCH_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
        static final String SEARCH_FALLBACK = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
        static final String LYRIC_URL = "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg";
        static final String LYRIC_LRC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";
        static final String REFERER = "https://y.qq.com/";
        static final byte[] KEY = bytes("!@#)(*$%123ZXC!@!@#)(NHL");

        public String name() { return "qq"; }
        public int priority() { return 1; }

        public ThirdPartyLyrics.Result resolve(ThirdPartyLyrics.Track t) throws Exception {
            ArrayList<String> queries = new ArrayList<String>();
            String clean = ThirdPartyLyrics.stripBrackets(t.title).trim();
            add(queries, t.title + " " + t.artist);
            add(queries, clean + " " + t.artist);
            add(queries, clean);
            for (int q = 0; q < queries.size(); q++) {
                ArrayList<JSONObject> songs = search(queries.get(q));
                if (songs.isEmpty()) continue;
                String[] titles = new String[songs.size()];
                String[] artists = new String[songs.size()];
                String[] albums = new String[songs.size()];
                long[] durs = new long[songs.size()];
                for (int i = 0; i < songs.size(); i++) {
                    JSONObject s = songs.get(i);
                    titles[i] = firstOf(s, "title", "name", "songname");
                    artists[i] = joinArtists(s.optJSONArray("singer"));
                    JSONObject album = s.optJSONObject("album");
                    albums[i] = album != null ? album.optString("name", "") : s.optString("albumname", "");
                    long interval = s.optLong("interval", 0L);
                    durs[i] = interval > 0 ? interval * 1000L : 0L;
                }
                int[] order = rankAll(t, titles, artists, albums, durs);
                int tried = 0;
                for (int k = 0; k < order.length && tried < 3; k++) {
                    int idx = order[k];
                    double sc = ThirdPartyLyrics.score(t, titles[idx], artists[idx], albums[idx], durs[idx]);
                    if (sc < ThirdPartyLyrics.threshold()) continue;
                    if (!ThirdPartyLyrics.plausibleMatch(t, titles[idx], artists[idx], durs[idx])) continue;
                    tried++;
                    ThirdPartyLyrics.Result r = fetch(t, songs.get(idx), titles[idx], artists[idx], sc);
                    if (r != null) return r;
                }
            }
            return null;
        }

        private ArrayList<JSONObject> search(String keywords) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            String kw = keywords.trim();
            try {
                JSONObject query = new JSONObject();
                query.put("query", kw);
                query.put("num_per_page", 20);
                query.put("page_num", 1);
                query.put("search_type", 0);
                JSONObject call = new JSONObject();
                call.put("module", "music.search.SearchCgiService");
                call.put("method", "DoSearchForQQMusicDesktop");
                call.put("param", query);
                JSONObject body = new JSONObject();
                body.put("req_1", call);
                JSONObject comm = new JSONObject();
                comm.put("ct", "19");
                comm.put("cv", "1859");
                body.put("comm", comm);
                JSONObject root = new JSONObject(httpPost(SEARCH_URL, body.toString(), REFERER, UA_PC, CONNECT_MS, READ_MS));
                out = songs(root.optJSONObject("req_1"));
                if (!out.isEmpty()) return out;
            } catch (Throwable ignore) { }
            try {
                JSONObject root = new JSONObject(http(SEARCH_FALLBACK + "?format=json&w=" + enc(kw) + "&n=20&p=1",
                        REFERER, null, UA_PC, CONNECT_MS, READ_MS));
                out = songs(root.optJSONObject("data"));
                if (!out.isEmpty()) return out;
            } catch (Throwable ignore) { }
            return out;
        }

        private ArrayList<JSONObject> songs(JSONObject node) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            if (node == null) return out;
            JSONObject data = node.optJSONObject("data");
            if (data == null) data = node;
            JSONObject body = data.optJSONObject("body");
            if (body == null && data.optJSONObject("song") == null) body = data;
            JSONObject song = body == null ? null : body.optJSONObject("song");
            if (song == null) song = data.optJSONObject("song");
            if (song == null) return out;
            JSONArray list = song.optJSONArray("list");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                out.add(o);
                JSONArray grp = o.optJSONArray("grp");
                if (grp == null) grp = o.optJSONArray("group");
                if (grp != null) {
                    for (int k = 0; k < grp.length(); k++) {
                        JSONObject g = grp.optJSONObject(k);
                        if (g != null) out.add(g);
                    }
                }
            }
            return out;
        }

        private ThirdPartyLyrics.Result fetch(ThirdPartyLyrics.Track t, JSONObject song, String title, String artist, double score) {
            long musicId = song.optLong("id", 0L);
            if (musicId <= 0) musicId = song.optLong("songid", 0L);
            String mid = firstOf(song, "mid", "songmid");
            if (musicId <= 0 && mid.length() == 0) return null;
            // 1) qrc (word level) via lyric_download.fcg
            if (musicId > 0) {
                try {
                    String url = LYRIC_URL + "?version=15&miniversion=82&lrctype=4&musicid=" + musicId;
                    String xml = http(url, REFERER, null, UA_PC, CONNECT_MS, READ_MS);
                    String content = xmlTag(xml, "content");
                    if (content.length() > 0) {
                        String plain = null;
                        try {
                            if (content.length() % 2 == 0 && content.matches("[0-9a-fA-F]+")) {
                                byte[] enc = ThirdPartyLyrics.hexDecode(content.trim());
                                if (enc.length % 8 != 0) throw new IllegalStateException("qrc block " + enc.length);
                                byte[] dec = QqDes.decrypt(enc, KEY);
                                plain = new String(ThirdPartyLyrics.inflate(dec), "UTF-8").replace("\uFEFF", "");
                            } else {
                                plain = content;
                            }
                        } catch (Throwable ignore) {
                            plain = null;
                        }
                        if (plain != null && plain.length() > 0) {
                            ArrayList<ThirdPartyLyrics.Line> lines = ThirdPartyLyrics.parseQrc(plain, t.durationMs);
                            ThirdPartyLyrics.Result r = build(name(), musicId, score, title, artist, lines,
                    durationMs(song, "interval", true));
                            if (r != null) return r;
                        }
                    }
                } catch (Throwable ignore) { }
            }
            // 2) plain LRC fallback
            if (mid.length() > 0) {
                try {
                    String url = LYRIC_LRC_URL + "?songmid=" + mid + "&format=json&nobase64=1&g_tk=5381";
                    String body = http(url, REFERER, null, UA_PC, CONNECT_MS, READ_MS);
                    JSONObject root = new JSONObject(body.trim());
                    String lrc = root.optString("lyric", "");
                    if (lrc.length() > 0) {
                        ArrayList<ThirdPartyLyrics.Line> lines = ThirdPartyLyrics.parseLrc(ThirdPartyLyrics.unescapeXml(lrc), t.durationMs);
                        ThirdPartyLyrics.Result r = build(name(), musicId, score, title, artist, lines,
                    durationMs(song, "interval", true));
                        if (r != null) return r;
                    }
                } catch (Throwable ignore) { }
            }
            return null;
        }

        private static String xmlTag(String xml, String tag) {
            if (xml == null) return "";
            int open = xml.indexOf("<" + tag);
            if (open < 0) return "";
            int gt = xml.indexOf('>', open);
            if (gt < 0) return "";
            int close = xml.indexOf("</" + tag + ">", gt);
            String raw;
            if (close < 0) {
                int self = xml.indexOf("/>", gt);
                if (self < 0) return "";
                raw = xml.substring(gt + 1, self);
            } else {
                raw = xml.substring(gt + 1, close);
            }
            raw = raw.trim();
            if (raw.startsWith("<![CDATA[")) raw = raw.substring(9);
            if (raw.endsWith("]]>")) raw = raw.substring(0, raw.length() - 3);
            return ThirdPartyLyrics.unescapeXml(raw.trim());
        }

        private static String firstOf(JSONObject o, String... keys) {
            for (int i = 0; i < keys.length; i++) {
                String v = o.optString(keys[i], "");
                if (v != null && v.length() > 0) return v;
            }
            return "";
        }

        private static void add(ArrayList<String> list, String q) {
            String v = q == null ? "" : q.trim().replaceAll("\\s+", " ");
            if (v.length() > 0 && !list.contains(v)) list.add(v);
        }
    }

    // ------------------------------------------------------------------ kugou

    static final class KugouSource implements ThirdPartyLyrics.Source {
        static final String SEARCH_URL = "https://songsearch.kugou.com/song_search_v2";
        static final String LYRIC_SEARCH = "https://lyrics.kugou.com/search";
        static final String LYRIC_DOWNLOAD = "https://lyrics.kugou.com/download";
        static final byte[] KEY = {
                0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
                0x51, 0x36, 0x31, 0x2d, (byte) 0xce, (byte) 0xd2, 0x6e, 0x69
        };

        public String name() { return "kugou"; }
        public int priority() { return 2; }

        public ThirdPartyLyrics.Result resolve(ThirdPartyLyrics.Track t) throws Exception {
            ArrayList<String> queries = new ArrayList<String>();
            String clean = ThirdPartyLyrics.stripBrackets(t.title).trim();
            add(queries, t.title + " " + t.artist);
            add(queries, clean + " " + t.artist);
            add(queries, clean);
            for (int q = 0; q < queries.size(); q++) {
                ArrayList<JSONObject> songs = search(queries.get(q));
                if (songs.isEmpty()) continue;
                String[] titles = new String[songs.size()];
                String[] artists = new String[songs.size()];
                String[] albums = new String[songs.size()];
                long[] durs = new long[songs.size()];
                for (int i = 0; i < songs.size(); i++) {
                    JSONObject s = songs.get(i);
                    titles[i] = s.optString("SongName", s.optString("SongName_original", ""));
                    artists[i] = s.optString("SingerName", "");
                    albums[i] = s.optString("AlbumName", "");
                    long sec = s.optLong("Duration", 0L);
                    durs[i] = sec > 0 ? sec * 1000L : 0L;
                }
                int[] order = rankAll(t, titles, artists, albums, durs);
                int tried = 0;
                for (int k = 0; k < order.length && tried < 3; k++) {
                    int idx = order[k];
                    double sc = ThirdPartyLyrics.score(t, titles[idx], artists[idx], albums[idx], durs[idx]);
                    if (sc < ThirdPartyLyrics.threshold()) continue;
                    if (!ThirdPartyLyrics.plausibleMatch(t, titles[idx], artists[idx], durs[idx])) continue;
                    tried++;
                    ThirdPartyLyrics.Result r = fetch(t, songs.get(idx), titles[idx], artists[idx], sc);
                    if (r != null) return r;
                }
            }
            return null;
        }

        private ArrayList<JSONObject> search(String keywords) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            try {
                JSONObject root = new JSONObject(http(SEARCH_URL + "?keyword=" + enc(keywords.trim()) + "&page=1&pagesize=10",
                        null, null, UA_PC, CONNECT_MS, READ_MS));
                JSONObject data = root.optJSONObject("data");
                JSONArray lists = data == null ? null : data.optJSONArray("lists");
                if (lists == null) return out;
                for (int i = 0; i < lists.length(); i++) {
                    JSONObject o = lists.optJSONObject(i);
                    if (o != null) out.add(o);
                }
            } catch (Throwable ignore) { }
            return out;
        }

        private ThirdPartyLyrics.Result fetch(ThirdPartyLyrics.Track t, JSONObject song, String title, String artist, double score) {
            String hash = song.optString("FileHash", song.optString("EMixSongID", ""));
            if (hash.length() == 0) return null;
            long durationMs = t.durationMs;
            try {
                String keyword = artist.replace(" & ", "、") + " - " + title;
                String url = LYRIC_SEARCH + "?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                        + "&duration=" + durationMs + "&hash=" + hash;
                JSONObject root = new JSONObject(http(url, null, null, UA_PC, CONNECT_MS, READ_MS));
                JSONArray cands = root.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) return null;
                for (int c = 0; c < Math.min(2, cands.length()); c++) {
                    JSONObject cand = cands.optJSONObject(c);
                    if (cand == null) continue;
                    String id = cand.optString("id", "");
                    String key = cand.optString("accesskey", "");
                    if (id.length() == 0 || key.length() == 0) continue;
                    String dl = LYRIC_DOWNLOAD + "?ver=1&client=pc&id=" + id + "&accesskey=" + enc(key) + "&fmt=krc&charset=utf8";
                    JSONObject down = new JSONObject(http(dl, null, null, UA_PC, CONNECT_MS, READ_MS));
                    String content = down.optString("content", "");
                    String fmt = down.optString("fmt", "");
                    if (content.length() == 0) continue;
                    ArrayList<ThirdPartyLyrics.Line> lines = null;
                    if ("krc".equalsIgnoreCase(fmt)) {
                        try {
                            lines = ThirdPartyLyrics.parseKrc(decryptKrc(content), t.durationMs);
                        } catch (Throwable ignore) {
                            lines = null;
                        }
                    }
                    if (lines == null || lines.size() < 2) {
                        String plain = content;
                        if ("krc".equalsIgnoreCase(fmt)) {
                            try { plain = decryptKrc(content); } catch (Throwable ignore) { plain = content; }
                        }
                        lines = ThirdPartyLyrics.parseLrc(plain, t.durationMs);
                    }
                    ThirdPartyLyrics.Result r = build(name(), Long.parseLong(id.isEmpty() ? "0" : id), score, title, artist, lines,
                            durationMs(song, "Duration", true));
                    if (r != null) return r;
                }
            } catch (Throwable ignore) { }
            return null;
        }

        static String decryptKrc(String base64) throws Exception {
            byte[] raw = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            if (raw.length <= 4) throw new IllegalStateException("krc too short");
            if (!(raw[0] == 'k' && raw[1] == 'r' && raw[2] == 'c' && raw[3] == '1')) {
                throw new IllegalStateException("krc header");
            }
            byte[] body = new byte[raw.length - 4];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) (raw[i + 4] ^ KEY[i % KEY.length]);
            }
            return new String(ThirdPartyLyrics.inflate(body), "UTF-8").replace("\uFEFF", "");
        }

        private static void add(ArrayList<String> list, String q) {
            String v = q == null ? "" : q.trim().replaceAll("\\s+", " ");
            if (v.length() > 0 && !list.contains(v)) list.add(v);
        }
    }

    // ------------------------------------------------------------------ netease

    static final class NeteaseSource implements ThirdPartyLyrics.Source {
        static final String SEARCH_PRIMARY = "https://music.163.com/api/cloudsearch/pc";
        static final String SEARCH_LEGACY = "https://music.163.com/api/search/get";
        static final String LYRIC_URL = "https://music.163.com/api/song/lyric";
        static final String REFERER = "https://music.163.com/";

        public String name() { return "netease"; }
        public int priority() { return 3; }

        public ThirdPartyLyrics.Result resolve(ThirdPartyLyrics.Track t) throws Exception {
            ArrayList<String> queries = new ArrayList<String>();
            String clean = ThirdPartyLyrics.stripBrackets(t.title).trim();
            add(queries, t.title + " " + t.artist);
            add(queries, clean + " " + t.artist);
            add(queries, clean);
            for (int q = 0; q < queries.size(); q++) {
                ArrayList<JSONObject> songs = search(queries.get(q));
                if (songs.isEmpty()) continue;
                String[] titles = new String[songs.size()];
                String[] artists = new String[songs.size()];
                String[] albums = new String[songs.size()];
                long[] durs = new long[songs.size()];
                for (int i = 0; i < songs.size(); i++) {
                    JSONObject o = songs.get(i);
                    titles[i] = o.optString("name", "");
                    artists[i] = joinArtists(o.optJSONArray("ar"));
                    JSONObject al = o.optJSONObject("al");
                    albums[i] = al != null ? al.optString("name", "") : "";
                    long dt = o.optLong("dt", 0L);
                    if (dt <= 0) dt = o.optLong("duration", 0L);
                    durs[i] = dt;
                }
                int[] order = rankAll(t, titles, artists, albums, durs);
                int tried = 0;
                for (int k = 0; k < order.length && tried < 3; k++) {
                    int idx = order[k];
                    double sc = ThirdPartyLyrics.score(t, titles[idx], artists[idx], albums[idx], durs[idx]);
                    if (sc < ThirdPartyLyrics.threshold()) continue;
                    if (!ThirdPartyLyrics.plausibleMatch(t, titles[idx], artists[idx], durs[idx])) continue;
                    tried++;
                    ThirdPartyLyrics.Result r = fetch(t, songs.get(idx), titles[idx], artists[idx], sc);
                    if (r != null) return r;
                }
            }
            return null;
        }

        private ArrayList<JSONObject> search(String keywords) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            try {
                return songs(new JSONObject(http(SEARCH_PRIMARY + "?s=" + enc(keywords) + "&type=1&offset=0&total=true&limit=10",
                        REFERER, null, UA_PC, CONNECT_MS, READ_MS)));
            } catch (Throwable ignore) { }
            try {
                return songs(new JSONObject(http(SEARCH_LEGACY + "?s=" + enc(keywords) + "&type=1&limit=10",
                        REFERER, null, UA_PC, CONNECT_MS, READ_MS)));
            } catch (Throwable ignore) { }
            return out;
        }

        private ArrayList<JSONObject> songs(JSONObject root) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            JSONObject result = root.optJSONObject("result");
            JSONArray arr = result == null ? null : result.optJSONArray("songs");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
            return out;
        }

        private ThirdPartyLyrics.Result fetch(ThirdPartyLyrics.Track t, JSONObject song, String title, String artist, double score) {
            try {
                long id = song.optLong("id", 0L);
                if (id == 0L) return null;
                String url = LYRIC_URL + "?id=" + id + "&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1&ytv=-1&yrv=-1";
                JSONObject root = new JSONObject(http(url, REFERER, null, UA_PC, CONNECT_MS, READ_MS));
                String yrc = null;
                JSONObject y = root.optJSONObject("yrc");
                if (y != null) yrc = y.optString("lyric", "");
                String lrc = null;
                JSONObject l = root.optJSONObject("lrc");
                if (l != null) lrc = l.optString("lyric", "");
                ArrayList<ThirdPartyLyrics.Line> lines = null;
                if (!ThirdPartyLyrics.isBlank(yrc)) {
                    ArrayList<ThirdPartyLyrics.Line> wl = ThirdPartyLyrics.parseYrc(yrc, t.durationMs);
                    if (wl.size() >= 2) lines = wl;
                }
                if (lines == null) {
                    if (ThirdPartyLyrics.isBlank(lrc)) return null;
                    lines = ThirdPartyLyrics.parseLrc(lrc, t.durationMs);
                }
                return build(name(), id, score, title, artist, lines, durationMs(song, "dt", false));
            } catch (Throwable ignore) {
                return null;
            }
        }

        private static void add(ArrayList<String> list, String q) {
            String v = q == null ? "" : q.trim().replaceAll("\\s+", " ");
            if (v.length() > 0 && !list.contains(v)) list.add(v);
        }
    }

    // ------------------------------------------------------------------ misc

    static byte[] bytes(String s) {
        try { return s.getBytes("US-ASCII"); } catch (Throwable t) { return s.getBytes(); }
    }

    static String readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    static void writeFile(File f, String content) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignore) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
        }
    }
}
