import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NOVA Smart Media Player — Java Backend (with JSON Database)
 *
 * API:
 *   GET    /                      → Serves frontend HTML
 *   GET    /api/db                → Full nova_database.json
 *   GET    /api/playlist          → Playlist array
 *   GET    /api/stats             → Stats object
 *   POST   /api/upload            → Upload media file (multipart)
 *   DELETE /api/delete?file=name  → Delete a track
 *   POST   /api/settings          → Save settings JSON
 *   POST   /api/history           → Log a play event
 *   GET    /media/{filename}      → Stream media (range requests supported)
 *
 * Run:
 *   javac NovaMediaServer.java
 *   java NovaMediaServer
 */
public class NovaMediaServer {

    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
    static final String MEDIA_DIR  = "media_files";
    static final String STATIC_DIR = ".";
    static final String DB_FILE    = "nova_database.json";
    static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static final AtomicInteger idCounter = new AtomicInteger(100);

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(MEDIA_DIR));
        initDatabase();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/",             new StaticHandler());
        server.createContext("/api/db",       new FullDbHandler());
        server.createContext("/api/playlist", new PlaylistHandler());
        server.createContext("/api/stats",    new StatsHandler());
        server.createContext("/api/upload",   new UploadHandler());
        server.createContext("/api/delete",   new DeleteHandler());
        server.createContext("/api/settings", new SettingsHandler());
        server.createContext("/api/history",  new HistoryHandler());
        server.createContext("/media/",       new MediaStreamHandler());
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║    NOVA Smart Media Player — Server      ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║  http://localhost:" + PORT + "                    ║");
        System.out.println("║  Database : " + DB_FILE + "              ║");
        System.out.println("║  Media    : ./" + MEDIA_DIR + "/              ║");
        System.out.println("╚══════════════════════════════════════════╝");
    }

    // ─── Database Helpers ────────────────────────────────────────────────────

    static void initDatabase() throws IOException {
        Path dbPath = Paths.get(DB_FILE);
        if (!Files.exists(dbPath)) {
            String def = "{\n" +
                "  \"app\": { \"name\": \"NOVA Smart Media Player\", \"version\": \"1.0.0\", \"theme\": \"dark-neon\" },\n" +
                "  \"settings\": { \"volume\": 80, \"shuffle\": false, \"repeat\": false, \"autoplay\": true, \"visualizer\": true, \"lastTrackIndex\": 0 },\n" +
                "  \"playlist\": [],\n" +
                "  \"history\": [],\n" +
                "  \"stats\": { \"total_tracks\": 0, \"total_played\": 0, \"last_played_id\": null, \"last_session\": null }\n" +
                "}";
            Files.writeString(dbPath, def);
            System.out.println("[DB] Created: " + DB_FILE);
        } else {
            System.out.println("[DB] Loaded: " + DB_FILE);
        }
    }

    static String readDb() throws IOException {
        return Files.readString(Paths.get(DB_FILE));
    }

    static synchronized void writeDb(String json) throws IOException {
        Files.writeString(Paths.get(DB_FILE), json);
    }

    /** Extract a JSON array by key name */
    static String extractArray(String db, String key) {
        int ki = db.indexOf("\"" + key + "\"");
        if (ki < 0) return "[]";
        int s = db.indexOf('[', ki);
        if (s < 0) return "[]";
        int depth = 0;
        for (int i = s; i < db.length(); i++) {
            char c = db.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') { depth--; if (depth == 0) return db.substring(s, i + 1); }
        }
        return "[]";
    }

    /** Extract a JSON object by key name */
    static String extractObj(String db, String key) {
        int ki = db.indexOf("\"" + key + "\"");
        if (ki < 0) return "{}";
        int s = db.indexOf('{', ki);
        if (s < 0) return "{}";
        int depth = 0;
        for (int i = s; i < db.length(); i++) {
            char c = db.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return db.substring(s, i + 1); }
        }
        return "{}";
    }

    /** Replace the value of "key": <value> with newVal */
    static String replaceSection(String db, String key, String newVal) {
        int ki = db.indexOf("\"" + key + "\"");
        if (ki < 0) return db;
        int vs = ki + key.length() + 2;
        while (vs < db.length() && db.charAt(vs) != '[' && db.charAt(vs) != '{') vs++;
        if (vs >= db.length()) return db;
        char op = db.charAt(vs), cl = op == '[' ? ']' : '}';
        int depth = 0;
        for (int i = vs; i < db.length(); i++) {
            char c = db.charAt(i);
            if (c == op) depth++;
            else if (c == cl) { depth--; if (depth == 0) return db.substring(0, vs) + newVal + db.substring(i + 1); }
        }
        return db;
    }

    /** Append an entry to a JSON array section in the DB file */
    static synchronized void appendToArray(String section, String entry) throws IOException {
        String db = readDb();
        String arr = extractArray(db, section);
        String newArr = arr.equals("[]")
            ? "[\n    " + entry + "\n  ]"
            : arr.substring(0, arr.lastIndexOf(']')).stripTrailing() + ",\n    " + entry + "\n  ]";
        writeDb(replaceSection(db, section, newArr));
    }

    /** Count objects in a JSON array string */
    static int countObjects(String arr) {
        int count = 0, depth = 0;
        for (char c : arr.toCharArray()) {
            if (c == '{') { if (depth++ == 0) count++; }
            else if (c == '}') depth--;
        }
        return count;
    }

    /** Remove the object containing matchStr from a JSON array */
    static String removeFromArray(String arr, String matchStr) {
        List<String> items = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') {
                if (--depth == 0 && start >= 0) {
                    String item = arr.substring(start, i + 1);
                    if (!item.contains(matchStr)) items.add(item);
                    start = -1;
                }
            }
        }
        return items.isEmpty() ? "[]" : "[\n    " + String.join(",\n    ", items) + "\n  ]";
    }

    // ─── Handlers ────────────────────────────────────────────────────────────

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String p = ex.getRequestURI().getPath();
            if (p.equals("/") || p.equals("/index.html")) {
                File f = new File(STATIC_DIR + "/smart-media-player.html");
                if (f.exists()) { serveFile(ex, f, "text/html"); return; }
                sendText(ex, 200, "text/html", "<h1 style='font-family:monospace;color:#00f5ff'>NOVA Backend Running ✅</h1><p>Place smart-media-player.html here.</p>");
            } else {
                sendText(ex, 404, "text/plain", "Not found");
            }
        }
    }

    static class FullDbHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            byte[] b = readDb().getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        }
    }

    static class PlaylistHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            if (isOptions(ex)) return;
            sendJson(ex, 200, "{\"tracks\":" + extractArray(readDb(), "playlist") + "}");
        }
    }

    static class StatsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            sendJson(ex, 200, "{\"stats\":" + extractObj(readDb(), "stats") + "}");
        }
    }

    static class UploadHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            if (isOptions(ex)) return;
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendJson(ex,405,"{\"error\":\"POST only\"}"); return; }

            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.contains("multipart/form-data")) { sendJson(ex,400,"{\"error\":\"multipart required\"}"); return; }

            String boundary = "--" + ct.split("boundary=")[1].trim();
            String body = new String(ex.getRequestBody().readAllBytes(), "ISO-8859-1");
            List<String> added = new ArrayList<>();

            for (String part : body.split(boundary)) {
                if (!part.contains("filename=\"")) continue;
                String filename = part.split("filename=\"")[1].split("\"")[0].replaceAll("[^a-zA-Z0-9._-]","_");
                if (filename.isEmpty()) continue;

                String lf = filename.toLowerCase();
                boolean isVid = lf.endsWith(".mp4") || lf.endsWith(".webm") || lf.endsWith(".mov");
                boolean isAud = lf.endsWith(".mp3") || lf.endsWith(".wav") || lf.endsWith(".ogg") || lf.endsWith(".aac") || lf.endsWith(".flac");
                if (!isVid && !isAud) continue;

                int ds = part.indexOf("\r\n\r\n"); if (ds < 0) continue; ds += 4;
                String dp = part.substring(ds); if (dp.endsWith("\r\n")) dp = dp.substring(0, dp.length()-2);
                byte[] fb = dp.getBytes("ISO-8859-1");
                Files.write(Paths.get(MEDIA_DIR, filename), fb);

                int id = idCounter.incrementAndGet();
                String name = filename.replaceAll("\\.[^.]+$","").replace("_"," ");
                String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                String rawType = isVid ? "mp4" : "mp3";
                String type    = isVid ? "video" : "audio";
                long sizeKb = fb.length / 1024;

                String entry = "{\"id\":" + id + ",\"name\":\"" + name + "\",\"filename\":\"" + filename +
                    "\",\"type\":\"" + type + "\",\"rawType\":\"" + rawType + "\",\"url\":\"/media/" + filename +
                    "\",\"artist\":\"Unknown\",\"album\":\"Unknown\",\"duration\":\"--:--\",\"size_kb\":" + sizeKb +
                    ",\"added_on\":\"" + today + "\"}";

                appendToArray("playlist", entry);
                added.add(entry);
                System.out.println("[UPLOAD] " + filename + " (" + sizeKb + "KB)");
            }

            // Update total_tracks stat
            String db = readDb();
            int total = countObjects(extractArray(db, "playlist"));
            String stats = extractObj(db, "stats").replaceAll("\"total_tracks\":\\s*\\d+", "\"total_tracks\":" + total);
            writeDb(replaceSection(db, "stats", stats));

            sendJson(ex, 200, "{\"uploaded\":" + added.size() + ",\"tracks\":[" + String.join(",", added) + "]}");
        }
    }

    static class DeleteHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            if (isOptions(ex)) return;
            String q = ex.getRequestURI().getQuery();
            if (q == null || !q.startsWith("file=")) { sendJson(ex,400,"{\"error\":\"?file= required\"}"); return; }
            String filename = q.substring(5).replaceAll("[^a-zA-Z0-9._-]","_");

            String db = readDb();
            String newPl = removeFromArray(extractArray(db, "playlist"), "\"filename\":\"" + filename + "\"");
            db = replaceSection(db, "playlist", newPl);
            int total = countObjects(newPl);
            String stats = extractObj(db, "stats").replaceAll("\"total_tracks\":\\s*\\d+", "\"total_tracks\":" + total);
            writeDb(replaceSection(db, "stats", stats));

            Path p = Paths.get(MEDIA_DIR, filename);
            if (Files.exists(p)) Files.delete(p);
            System.out.println("[DELETE] " + filename);
            sendJson(ex, 200, "{\"deleted\":true,\"filename\":\"" + filename + "\"}");
        }
    }

    static class SettingsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            if (isOptions(ex)) return;
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendJson(ex,405,"{}"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8").trim();
            String db = readDb();
            writeDb(replaceSection(db, "settings", body));
            System.out.println("[SETTINGS] Saved");
            sendJson(ex, 200, "{\"saved\":true}");
        }
    }

    static class HistoryHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            if (isOptions(ex)) return;
            if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { sendJson(ex,405,"{}"); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), "UTF-8").trim();
            String now = LocalDateTime.now().format(DT_FMT);
            String entry = body.substring(0, body.lastIndexOf('}')) + ",\"played_at\":\"" + now + "\"}";
            appendToArray("history", entry);

            String db = readDb();
            int total = countObjects(extractArray(db, "history"));
            String stats = extractObj(db, "stats")
                .replaceAll("\"total_played\":\\s*\\d+", "\"total_played\":" + total)
                .replaceAll("\"last_session\":\\s*\"[^\"]*\"", "\"last_session\":\"" + now + "\"");
            writeDb(replaceSection(db, "stats", stats));
            sendJson(ex, 200, "{\"logged\":true}");
        }
    }

    static class MediaStreamHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            cors(ex);
            String filename = ex.getRequestURI().getPath().replaceFirst("/media/","").replaceAll("[^a-zA-Z0-9._-]","_");
            File f = new File(MEDIA_DIR, filename);
            if (!f.exists()) { sendText(ex,404,"text/plain","Not found"); return; }

            String mime = mime(filename);
            long size = f.length();
            String range = ex.getRequestHeaders().getFirst("Range");

            if (range != null && range.startsWith("bytes=")) {
                String[] rp = range.substring(6).split("-");
                long start = Long.parseLong(rp[0]);
                long end   = rp.length>1 && !rp[1].isEmpty() ? Long.parseLong(rp[1]) : size-1;
                end = Math.min(end, size-1);
                long len = end - start + 1;
                ex.getResponseHeaders().set("Content-Type", mime);
                ex.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+size);
                ex.getResponseHeaders().set("Accept-Ranges","bytes");
                ex.getResponseHeaders().set("Content-Length",String.valueOf(len));
                ex.sendResponseHeaders(206, len);
                try (InputStream in = new FileInputStream(f); OutputStream out = ex.getResponseBody()) {
                    in.skip(start); byte[] buf=new byte[8192]; long rem=len; int r;
                    while (rem>0 && (r=in.read(buf,0,(int)Math.min(buf.length,rem)))!=-1) { out.write(buf,0,r); rem-=r; }
                }
            } else {
                ex.getResponseHeaders().set("Content-Type",mime);
                ex.getResponseHeaders().set("Content-Length",String.valueOf(size));
                ex.getResponseHeaders().set("Accept-Ranges","bytes");
                ex.sendResponseHeaders(200, size);
                try (InputStream in=new FileInputStream(f); OutputStream out=ex.getResponseBody()) {
                    byte[] buf=new byte[8192]; int r; while ((r=in.read(buf))!=-1) out.write(buf,0,r);
                }
            }
        }
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    static String mime(String f) {
        String lf = f.toLowerCase();
        if (lf.endsWith(".mp3"))  return "audio/mpeg";
        if (lf.endsWith(".wav"))  return "audio/wav";
        if (lf.endsWith(".ogg"))  return "audio/ogg";
        if (lf.endsWith(".aac"))  return "audio/aac";
        if (lf.endsWith(".flac")) return "audio/flac";
        if (lf.endsWith(".mp4"))  return "video/mp4";
        if (lf.endsWith(".webm")) return "video/webm";
        return "application/octet-stream";
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin","*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,DELETE,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type");
    }

    static boolean isOptions(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) { ex.sendResponseHeaders(204,-1); return true; }
        return false;
    }

    static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
        cors(ex); ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendText(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void serveFile(HttpExchange ex, File f, String mime) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, f.length());
        try (InputStream in=new FileInputStream(f); OutputStream out=ex.getResponseBody()) {
            byte[] buf=new byte[8192]; int r; while ((r=in.read(buf))!=-1) out.write(buf,0,r);
        }
    }
}
