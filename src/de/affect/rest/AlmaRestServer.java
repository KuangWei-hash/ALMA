package de.affect.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.affect.manage.AffectManager;
import de.affect.util.AppraisalTag;
import de.affect.xml.AffectInputDocument.AffectInput;
import de.affect.xml.AffectDefinitionDocument;
import de.affect.xml.AffectDefinitionDocument.AffectDefinition;
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
    if ("POST".equals(ex.getRequestMethod())) { handleCreateCharacter(ex); return; }
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "GET or POST only"); return; }
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

  private void handleCreateCharacter(HttpExchange ex) throws IOException {
    try {
      Object parsed = new JsonParser(readBody(ex)).parse();
      Map<String, Object> root = asObject(parsed, "request body");
      String name = requiredString(root, "name");
      if (!name.matches("[A-Za-z0-9_. -]{1,80}")) {
        throw new IllegalArgumentException("name must be 1-80 letters, numbers, spaces, '.', '_' or '-'");
      }

      Map<String, Object> personality = requiredObject(root, "personality");
      Map<String, Object> mood = requiredObject(root, "mood");
      Map<String, Object> emotion = requiredObject(root, "emotion");
      Map<String, Object> appraisal = requiredObject(root, "appraisal");

      double openness = unit(personality, "openness");
      double conscientiousness = unit(personality, "conscientiousness");
      double extraversion = unit(personality, "extraversion");
      double agreeableness = unit(personality, "agreeableness");
      double neurotism = unit(personality, "neurotism");
      double emotionInfluence = nonNegativeUnit(personality, "emotion_influence");

      long moodDecayTime = positiveLong(mood, "decay_time");
      long moodDecayPeriod = positiveLong(mood, "decay_period");
      boolean neurotismStability = requiredBoolean(mood, "neurotism_stability");
      if (moodDecayPeriod > moodDecayTime) throw new IllegalArgumentException("mood.decay_period cannot exceed decay_time");

      long emotionDecayTime = positiveLong(emotion, "decay_time");
      long emotionDecayPeriod = positiveLong(emotion, "decay_period");
      if (emotionDecayPeriod > emotionDecayTime) throw new IllegalArgumentException("emotion.decay_period cannot exceed decay_time");
      double baseline = nonNegativeUnit(emotion, "baseline");
      String decayFunction = requiredString(emotion, "decay_function");
      if (!("linear".equals(decayFunction) || "exponential".equals(decayFunction) || "hyperbolic".equals(decayFunction))) {
        throw new IllegalArgumentException("emotion.decay_function must be linear, exponential or hyperbolic");
      }
      validateCompleteAppraisal(appraisal);

      StringBuilder xml = new StringBuilder();
      xml.append("<AffectDefinition xmlns=\"xml.affect.de\"><CharacterAffect name=\"")
        .append(xmlEscape(name)).append("\" monitored=\"false\">")
        .append("<PersonalitySpecification derived=\"false\" emotioninfluence=\"").append(emotionInfluence)
        .append("\" openness=\"").append(openness).append("\" conscientiousness=\"").append(conscientiousness)
        .append("\" extraversion=\"").append(extraversion).append("\" agreeableness=\"").append(agreeableness)
        .append("\" neurotism=\"").append(neurotism).append("\"/>")
        .append("<MoodSpecification decaytime=\"").append(moodDecayTime).append("\" decayperiod=\"").append(moodDecayPeriod)
        .append("\" neurotismstability=\"").append(neurotismStability).append("\"/>")
        .append("<EmotionSpecification decaytime=\"").append(emotionDecayTime).append("\" decayperiod=\"").append(emotionDecayPeriod)
        .append("\" decayfunction=\"").append(decayFunction).append("\" baseline=\"").append(baseline).append("\"/>")
        .append("<Appraisal><Basic>");
      appendAppraisalXml(xml, appraisal);
      xml.append("</Basic></Appraisal></CharacterAffect></AffectDefinition>");

      AffectDefinitionDocument doc = AffectDefinitionDocument.Factory.parse(xml.toString());
      AffectDefinition.CharacterAffect profile = doc.getAffectDefinition().getCharacterAffectArray(0);
      synchronized (am) {
        boolean exists = false;
        try {
          am.sInterface.getCharacterByName(name);
          exists = true;
        } catch (IllegalArgumentException ignored) {
          // The legacy API signals a missing character by throwing.
        }
        if (exists) {
          fail(ex, 409, "character already exists: " + name);
          return;
        }
        AffectDefinition current = am.sInterface.getDocumentManager().getAffectDefinition();
        if (current == null) throw new IllegalStateException("no active affect definition");
        int index = current.sizeOfCharacterAffectArray();
        AffectDefinition.CharacterAffect stored = current.addNewCharacterAffect();
        stored.set(profile);
        try {
          am.initCharacter(stored);
        } catch (RuntimeException e) {
          current.removeCharacterAffect(index);
          throw e;
        }
      }
      created(ex, "{\"created\":true,\"name\":\"" + escape(name) + "\",\"persistent\":false}");
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 400, "cannot create character: " + e.getMessage());
    }
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
    try {
      Map<String, Object> body = asObject(new JsonParser(readBody(ex)).parse(), "request body");
      if (body.size() != 4) {
        throw new IllegalArgumentException("event must contain exactly: character, tag, intensity, elicitor");
      }
      String character = requiredString(body, "character");
      String tag = requiredString(body, "tag");
      String elicitor = requiredString(body, "elicitor");
      if (elicitor.length() > 200) throw new IllegalArgumentException("elicitor must not exceed 200 characters");
      if (!isExactAppraisalTag(tag)) {
        throw new IllegalArgumentException("tag must be one of the 18 ALMA Basic appraisal tags");
      }
      Object intensityValue = body.get("intensity");
      if (!(intensityValue instanceof Number)) {
        throw new IllegalArgumentException("intensity must be a JSON number between 0.0 and 1.0");
      }
      double intensity = ((Number) intensityValue).doubleValue();
      if (!Double.isFinite(intensity) || intensity < 0.0 || intensity > 1.0) {
        throw new IllegalArgumentException("intensity must be between 0.0 and 1.0");
      }
      try {
        am.sInterface.getCharacterByName(character);
      } catch (IllegalArgumentException e) {
        fail(ex, 404, "character not found: " + character);
        return;
      }
      AffectInput ai = AppraisalTag.instance().makeAffectInput(character, tag, Double.toString(intensity), elicitor);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character)
        + "\",\"tag\":\"" + escape(tag) + "\",\"intensity\":" + intensity
        + ",\"elicitor\":\"" + escape(elicitor) + "\"}");
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private static boolean isExactAppraisalTag(String tag) {
    for (AppraisalTag.Tags candidate : AppraisalTag.Tags.values()) {
      if (candidate.name().equals(tag)) return true;
    }
    return false;
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
    respond(ex, 200, json);
  }

  private static void created(HttpExchange ex, String json) throws IOException {
    respond(ex, 201, json);
  }

  private static void respond(HttpExchange ex, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, body.length);
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

  private static void appendAppraisalXml(StringBuilder xml, Map<String, Object> appraisal) {
    for (Map.Entry<String, Object> rule : appraisal.entrySet()) {
      String tag = rule.getKey();
      if (!tag.matches("[A-Za-z][A-Za-z0-9]*")) throw new IllegalArgumentException("invalid appraisal tag: " + tag);
      Map<String, Object> attributes = asObject(rule.getValue(), "appraisal." + tag);
      if (attributes.isEmpty()) throw new IllegalArgumentException("appraisal." + tag + " must contain attributes");
      xml.append('<').append(tag);
      for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
        String key = attribute.getKey();
        if (!key.matches("[A-Za-z][A-Za-z0-9]*")) throw new IllegalArgumentException("invalid appraisal attribute: " + key);
        Object value = attribute.getValue();
        if ("agency".equals(key)) {
          String agency = String.valueOf(value);
          if (!("self".equals(agency) || "other".equals(agency))) {
            throw new IllegalArgumentException("appraisal." + tag + ".agency must be self or other");
          }
        } else if ("realization".equals(key)) {
          if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("appraisal." + tag + ".realization must be true or false");
          }
        } else {
          if (!(value instanceof Number)) throw new IllegalArgumentException("appraisal." + tag + "." + key + " must be a number");
          double number = ((Number) value).doubleValue();
          if (!Double.isFinite(number) || number < -1.0 || number > 1.0) {
            throw new IllegalArgumentException("appraisal." + tag + "." + key + " must be between -1.0 and 1.0");
          }
        }
        xml.append(' ').append(key).append("=\"").append(xmlEscape(String.valueOf(value))).append('"');
      }
      xml.append("/>");
    }
  }

  private static void validateCompleteAppraisal(Map<String, Object> appraisal) {
    String[] requiredTags = {
      "GoodEvent", "GoodEventForGoodOther", "GoodEventForBadOther",
      "BadEvent", "BadEventForGoodOther", "BadEventForBadOther",
      "GoodLikelyFutureEvent", "GoodUnlikelyFutureEvent",
      "BadLikelyFutureEvent", "BadUnlikelyFutureEvent",
      "EventConfirmed", "EventDisconfirmed",
      "GoodActSelf", "GoodActOther", "BadActSelf", "BadActOther",
      "NiceThing", "NastyThing"
    };
    if (appraisal.size() != requiredTags.length) {
      throw new IllegalArgumentException("appraisal must contain exactly all 18 ALMA Basic tags");
    }
    for (String tag : requiredTags) {
      if (!appraisal.containsKey(tag)) throw new IllegalArgumentException("missing required appraisal tag: " + tag);
    }

    positiveRule(appraisal, "GoodEvent", "desirability");
    negativeRule(appraisal, "BadEvent", "desirability");
    otherEventRule(appraisal, "GoodEventForGoodOther", true, true);
    otherEventRule(appraisal, "GoodEventForBadOther", true, false);
    otherEventRule(appraisal, "BadEventForGoodOther", false, true);
    otherEventRule(appraisal, "BadEventForBadOther", false, false);
    futureRule(appraisal, "GoodLikelyFutureEvent", true, true);
    futureRule(appraisal, "GoodUnlikelyFutureEvent", true, false);
    futureRule(appraisal, "BadLikelyFutureEvent", false, true);
    futureRule(appraisal, "BadUnlikelyFutureEvent", false, false);
    realizationRule(appraisal, "EventConfirmed", true);
    realizationRule(appraisal, "EventDisconfirmed", false);
    actionRule(appraisal, "GoodActSelf", "self", true);
    actionRule(appraisal, "GoodActOther", "other", true);
    actionRule(appraisal, "BadActSelf", "self", false);
    actionRule(appraisal, "BadActOther", "other", false);
    positiveRule(appraisal, "NiceThing", "appealingness");
    negativeRule(appraisal, "NastyThing", "appealingness");
  }

  private static void otherEventRule(Map<String, Object> appraisal, String tag,
                                     boolean positiveDesirability, boolean positiveLiking) {
    Map<String, Object> rule = exactRule(appraisal, tag, "agency", "desirability", "liking");
    fixedString(rule, tag, "agency", "other");
    signedRuleValue(rule, tag, "desirability", positiveDesirability);
    signedRuleValue(rule, tag, "liking", positiveLiking);
  }

  private static void futureRule(Map<String, Object> appraisal, String tag,
                                 boolean positiveDesirability, boolean positiveLikelihood) {
    Map<String, Object> rule = exactRule(appraisal, tag, "desirability", "likelihood");
    signedRuleValue(rule, tag, "desirability", positiveDesirability);
    signedRuleValue(rule, tag, "likelihood", positiveLikelihood);
  }

  private static void realizationRule(Map<String, Object> appraisal, String tag, boolean expected) {
    Map<String, Object> rule = exactRule(appraisal, tag, "realization");
    Object value = rule.get("realization");
    if (!(value instanceof Boolean) || ((Boolean) value) != expected) {
      throw new IllegalArgumentException("appraisal." + tag + ".realization must be " + expected);
    }
  }

  private static void actionRule(Map<String, Object> appraisal, String tag,
                                 String agency, boolean positive) {
    Map<String, Object> rule = exactRule(appraisal, tag, "agency", "praiseworthiness");
    fixedString(rule, tag, "agency", agency);
    signedRuleValue(rule, tag, "praiseworthiness", positive);
  }

  private static void positiveRule(Map<String, Object> appraisal, String tag, String attribute) {
    Map<String, Object> rule = exactRule(appraisal, tag, attribute);
    signedRuleValue(rule, tag, attribute, true);
  }

  private static void negativeRule(Map<String, Object> appraisal, String tag, String attribute) {
    Map<String, Object> rule = exactRule(appraisal, tag, attribute);
    signedRuleValue(rule, tag, attribute, false);
  }

  private static Map<String, Object> exactRule(Map<String, Object> appraisal, String tag, String... attributes) {
    Map<String, Object> rule = asObject(appraisal.get(tag), "appraisal." + tag);
    if (rule.size() != attributes.length) {
      throw new IllegalArgumentException("appraisal." + tag + " must contain exactly: " + String.join(", ", attributes));
    }
    for (String attribute : attributes) {
      if (!rule.containsKey(attribute)) {
        throw new IllegalArgumentException("missing required field: appraisal." + tag + "." + attribute);
      }
    }
    return rule;
  }

  private static void fixedString(Map<String, Object> rule, String tag, String attribute, String expected) {
    Object value = rule.get(attribute);
    if (!(value instanceof String) || !expected.equals(value)) {
      throw new IllegalArgumentException("appraisal." + tag + "." + attribute + " must be \"" + expected + "\"");
    }
  }

  private static void signedRuleValue(Map<String, Object> rule, String tag,
                                      String attribute, boolean positive) {
    Object value = rule.get(attribute);
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("appraisal." + tag + "." + attribute + " must be a number");
    }
    double number = ((Number) value).doubleValue();
    double min = positive ? 0.0 : -1.0;
    double max = positive ? 1.0 : 0.0;
    if (!Double.isFinite(number) || number < min || number > max) {
      throw new IllegalArgumentException("appraisal." + tag + "." + attribute
        + " must be between " + min + " and " + max);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObject(Object value, String field) {
    if (!(value instanceof Map)) throw new IllegalArgumentException(field + " must be a JSON object");
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> requiredObject(Map<String, Object> object, String key) {
    if (!object.containsKey(key)) throw new IllegalArgumentException("missing required field: " + key);
    return asObject(object.get(key), key);
  }

  private static String requiredString(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
      throw new IllegalArgumentException(key + " must be a non-empty string");
    }
    return ((String) value).trim();
  }

  private static boolean requiredBoolean(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be true or false");
    return (Boolean) value;
  }

  private static double unit(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number < -1.0 || number > 1.0) {
      throw new IllegalArgumentException(key + " must be between -1.0 and 1.0");
    }
    return number;
  }

  private static double nonNegativeUnit(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number < 0.0 || number > 1.0) {
      throw new IllegalArgumentException(key + " must be between 0.0 and 1.0");
    }
    return number;
  }

  private static long positiveLong(Map<String, Object> object, String key) {
    Object value = object.get(key);
    if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be a positive integer");
    double number = ((Number) value).doubleValue();
    long result = ((Number) value).longValue();
    if (!Double.isFinite(number) || number != result || result <= 0) {
      throw new IllegalArgumentException(key + " must be a positive integer");
    }
    return result;
  }

  private static String xmlEscape(String value) {
    return value.replace("&", "&amp;").replace("\"", "&quot;")
      .replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;");
  }

  private static final class JsonParser {
    private final String input;
    private int pos;

    JsonParser(String input) { this.input = input == null ? "" : input; }

    Object parse() {
      skipWhitespace();
      Object value = readValue();
      skipWhitespace();
      if (pos != input.length()) error("unexpected trailing content");
      return value;
    }

    private Object readValue() {
      skipWhitespace();
      if (pos >= input.length()) return error("expected JSON value");
      char c = input.charAt(pos);
      if (c == '{') return readObject();
      if (c == '"') return readString();
      if (c == 't') return readLiteral("true", Boolean.TRUE);
      if (c == 'f') return readLiteral("false", Boolean.FALSE);
      if (c == 'n') return readLiteral("null", null);
      if (c == '-' || (c >= '0' && c <= '9')) return readNumber();
      return error("unexpected character '" + c + "'");
    }

    private Map<String, Object> readObject() {
      Map<String, Object> object = new LinkedHashMap<>();
      pos++;
      skipWhitespace();
      if (consume('}')) return object;
      while (true) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != '"') error("expected object key");
        String key = readString();
        skipWhitespace();
        if (!consume(':')) error("expected ':' after object key");
        object.put(key, readValue());
        skipWhitespace();
        if (consume('}')) return object;
        if (!consume(',')) error("expected ',' or '}'");
      }
    }

    private String readString() {
      pos++;
      StringBuilder value = new StringBuilder();
      while (pos < input.length()) {
        char c = input.charAt(pos++);
        if (c == '"') return value.toString();
        if (c == '\\') {
          if (pos >= input.length()) error("unfinished escape sequence");
          char escaped = input.charAt(pos++);
          switch (escaped) {
            case '"': case '\\': case '/': value.append(escaped); break;
            case 'b': value.append('\b'); break;
            case 'f': value.append('\f'); break;
            case 'n': value.append('\n'); break;
            case 'r': value.append('\r'); break;
            case 't': value.append('\t'); break;
            case 'u':
              if (pos + 4 > input.length()) error("invalid unicode escape");
              try { value.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16)); }
              catch (NumberFormatException e) { error("invalid unicode escape"); }
              pos += 4;
              break;
            default: error("invalid escape sequence");
          }
        } else {
          if (c < 0x20) error("control character in string");
          value.append(c);
        }
      }
      return error("unterminated string");
    }

    private Number readNumber() {
      int start = pos;
      if (consume('-') && pos >= input.length()) error("invalid number");
      if (consume('0')) {
        // A leading zero is complete unless followed by a fraction/exponent.
      } else {
        int digits = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        if (digits == pos) error("invalid number");
      }
      boolean decimal = false;
      if (consume('.')) {
        decimal = true;
        int digits = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        if (digits == pos) error("invalid number");
      }
      if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
        decimal = true;
        pos++;
        if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
        int digits = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        if (digits == pos) error("invalid exponent");
      }
      String token = input.substring(start, pos);
      try { return decimal ? Double.valueOf(token) : Long.valueOf(token); }
      catch (NumberFormatException e) { return error("invalid number"); }
    }

    private Object readLiteral(String literal, Object value) {
      if (!input.startsWith(literal, pos)) return error("invalid literal");
      pos += literal.length();
      return value;
    }

    private boolean consume(char expected) {
      if (pos < input.length() && input.charAt(pos) == expected) { pos++; return true; }
      return false;
    }

    private void skipWhitespace() {
      while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private <T> T error(String message) {
      throw new IllegalArgumentException("invalid JSON at character " + pos + ": " + message);
    }
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
    System.out.println("  POST /characters {name, personality, mood, emotion, appraisal}");
    System.out.println("  GET  /affect");
    System.out.println("  GET  /affect/{name}");
    System.out.println("  POST /event      {character, tag, intensity, elicitor}");
    System.out.println("  POST /pad        {character, p, a, d, intensity?, elicitor?}");
    System.out.println("  POST /pause?character={name}");
    System.out.println("  POST /resume?character={name}");
  }
}
