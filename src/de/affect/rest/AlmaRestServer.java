package de.affect.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.affect.manage.AffectManager;
import de.affect.util.AppraisalTag;
import de.affect.xml.AffectInputDocument.AffectInput;
import de.affect.xml.AffectOutputDocument;
import de.affect.xml.AffectOutputDocument.AffectOutput.CharacterAffect;
import de.affect.xml.EmotionType;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class AlmaRestServer {

  private final AffectManager am;
  private final HttpServer http;

  public AlmaRestServer(String compSpec, String defSpec, int port) throws Exception {
    this.am = new AffectManager(compSpec, defSpec, false);
    this.http = HttpServer.create(new InetSocketAddress(port), 0);
    this.http.setExecutor(Executors.newFixedThreadPool(4));
    registerHandlers();
  }

  public void start() {
    http.start();
    System.out.println("[alma-rest] listening on http://localhost:" + http.getAddress().getPort());
  }

  private void registerHandlers() {
    http.createContext("/health",     ex -> ok(ex, "{\"status\":\"ok\",\"alma_version\":\"3.0\"}"));
    http.createContext("/characters", this::handleCharacters);
    http.createContext("/affect",     this::handleAffect);
    http.createContext("/event",      this::handleEvent);
    http.createContext("/pad",        this::handlePad);
    http.createContext("/pause",      this::handlePause);
    http.createContext("/resume",     this::handleResume);
  }

  private void handleCharacters(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "method not allowed"); return; }
    AffectOutputDocument aod = am.sInterface.getCurrentAffect();
    if (aod == null) { ok(ex, "{\"characters\":[]}"); return; }
    StringBuilder sb = new StringBuilder("{\"characters\":[");
    boolean first = true;
    for (CharacterAffect c : aod.getAffectOutput().getCharacterAffectList()) {
      if (!first) sb.append(",");
      sb.append("\"").append(escape(c.getName())).append("\"");
      first = false;
    }
    sb.append("]}");
    ok(ex, sb.toString());
  }

  private void handleAffect(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "method not allowed"); return; }
    String path = ex.getRequestURI().getPath();
    String target = path.length() > "/affect/".length() ? path.substring("/affect/".length()) : null;

    AffectOutputDocument aod = am.sInterface.getCurrentAffect();
    if (aod == null) { fail(ex, 503, "no affect state yet — wait for first update"); return; }

    if (target == null || target.isEmpty()) {
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (CharacterAffect c : aod.getAffectOutput().getCharacterAffectList()) {
        if (!first) sb.append(",");
        sb.append(characterAffectJson(c));
        first = false;
      }
      sb.append("]");
      ok(ex, sb.toString());
      return;
    }

    for (CharacterAffect c : aod.getAffectOutput().getCharacterAffectList()) {
      if (target.equals(c.getName())) { ok(ex, characterAffectJson(c)); return; }
    }
    fail(ex, 404, "character not found: " + target);
  }

  private String characterAffectJson(CharacterAffect c) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"name\":\"").append(escape(c.getName())).append("\",");
    sb.append("\"dominant_emotion\":{")
      .append("\"name\":\"").append(escape(c.getDominantEmotion().getName().toString())).append("\",")
      .append("\"intensity\":").append(c.getDominantEmotion().getValue())
      .append("},");
    sb.append("\"mood\":{")
      .append("\"word\":\"").append(escape(c.getMood().getMoodword().toString())).append("\",")
      .append("\"intensity\":\"").append(escape(c.getMood().getIntensity().toString())).append("\",")
      .append("\"tendency\":\"").append(escape(c.getMoodTendency().getMoodword().toString())).append("\"")
      .append("},");
    sb.append("\"emotions\":[");
    boolean first = true;
    for (Iterator<EmotionType> it = c.getEmotions().getEmotionList().iterator(); it.hasNext();) {
      EmotionType et = it.next();
      if (!first) sb.append(",");
      sb.append("{\"name\":\"").append(escape(et.getName().toString()))
        .append("\",\"intensity\":").append(et.getValue()).append("}");
      first = false;
    }
    sb.append("]}");
    return sb.toString();
  }

  private void handleEvent(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    Map<String, String> body = parseJsonFlat(readBody(ex));
    String character = body.get("character");
    String tag       = body.get("tag");
    String intensity = body.getOrDefault("intensity", "1.0");
    String elicitor  = body.getOrDefault("elicitor", "rest-api");
    if (character == null || tag == null) {
      fail(ex, 400, "required fields: character, tag (intensity/elicitor optional)"); return;
    }
    try {
      AffectInput ai = AppraisalTag.instance().makeAffectInput(character, tag, intensity, elicitor);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character) + "\",\"tag\":\"" + escape(tag) + "\"}");
    } catch (Exception e) {
      fail(ex, 400, "processSignal failed: " + e.getMessage());
    }
  }

  private void handlePad(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    Map<String, String> body = parseJsonFlat(readBody(ex));
    String character = body.get("character");
    String p = body.get("p"), a = body.get("a"), d = body.get("d");
    String intensity = body.getOrDefault("intensity", "1.0");
    String elicitor  = body.getOrDefault("elicitor", "rest-api-pad");
    if (character == null || p == null || a == null || d == null) {
      fail(ex, 400, "required fields: character, p, a, d"); return;
    }
    try {
      AffectInput ai = AppraisalTag.instance().makePADInput(character, p, a, d, intensity, elicitor);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character) + "\",\"pad\":[" + p + "," + a + "," + d + "]}");
    } catch (Exception e) {
      fail(ex, 400, "processSignal failed: " + e.getMessage());
    }
  }

  private void handlePause(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    String name = queryParam(ex, "character");
    boolean r = (name == null) ? am.sInterface.pauseAffectComputation()
                               : am.sInterface.pauseAffectComputation(name);
    ok(ex, "{\"paused\":" + r + "}");
  }

  private void handleResume(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    String name = queryParam(ex, "character");
    boolean r = (name == null) ? am.sInterface.resumeAffectComputation()
                               : am.sInterface.resumeAffectComputation(name);
    ok(ex, "{\"resumed\":" + r + "}");
  }

  private static void ok(HttpExchange ex, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(200, body.length);
    ex.getResponseBody().write(body);
    ex.getResponseBody().close();
  }

  private static void fail(HttpExchange ex, int code, String msg) throws IOException {
    String json = "{\"error\":\"" + escape(msg) + "\"}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(code, body.length);
    ex.getResponseBody().write(body);
    ex.getResponseBody().close();
  }

  private static String readBody(HttpExchange ex) throws IOException {
    try (InputStream is = ex.getRequestBody()) {
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
      return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static Map<String, String> parseJsonFlat(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    if (body == null) return out;
    String s = body.trim();
    if (s.startsWith("{")) s = s.substring(1);
    if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
    int i = 0, n = s.length();
    while (i < n) {
      while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\r' || s.charAt(i) == '\t')) i++;
      if (i >= n) break;
      if (s.charAt(i) != '"') break;
      int keyStart = ++i;
      while (i < n && s.charAt(i) != '"') i++;
      String key = s.substring(keyStart, i);
      i++;
      while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ':')) i++;
      String val;
      if (i < n && s.charAt(i) == '"') {
        int vs = ++i;
        StringBuilder vb = new StringBuilder();
        while (i < n && s.charAt(i) != '"') {
          if (s.charAt(i) == '\\' && i + 1 < n) { vb.append(s.charAt(i + 1)); i += 2; }
          else { vb.append(s.charAt(i)); i++; }
        }
        val = vb.toString();
        i++;
      } else {
        int vs = i;
        while (i < n && s.charAt(i) != ',' && s.charAt(i) != '}' && s.charAt(i) != ' ' && s.charAt(i) != '\n') i++;
        val = s.substring(vs, i);
      }
      out.put(key, val);
    }
    return out;
  }

  private static String queryParam(HttpExchange ex, String key) {
    String q = ex.getRequestURI().getQuery();
    if (q == null) return null;
    for (String kv : q.split("&")) {
      int eq = kv.indexOf('=');
      if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
    }
    return null;
  }

  private static String escape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0, n = s.length(); i < n; i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  public static void main(String[] args) throws Exception {
    Map<String, String> opts = new HashMap<>();
    opts.put("--comp", "conf/AffectComputationExample.aml");
    opts.put("--def",  "conf/AffectDefinitionExample.aml");
    opts.put("--port", "8080");
    for (int i = 0; i < args.length - 1; i += 2) opts.put(args[i], args[i + 1]);

    String comp = opts.get("--comp");
    String def  = opts.get("--def");
    int port    = Integer.parseInt(opts.get("--port"));

    System.out.println("[alma-rest] loading comp=" + comp + " def=" + def);
    AlmaRestServer server = new AlmaRestServer(comp, def, port);
    server.start();
    System.out.println("[alma-rest] endpoints:");
    System.out.println("  GET  /health");
    System.out.println("  GET  /characters");
    System.out.println("  GET  /affect");
    System.out.println("  GET  /affect/{name}");
    System.out.println("  POST /event      {character, tag, intensity?, elicitor?}");
    System.out.println("  POST /pad        {character, p, a, d, intensity?, elicitor?}");
    System.out.println("  POST /pause?character={name}");
    System.out.println("  POST /resume?character={name}");
  }
}
