package de.affect.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.affect.manage.AffectManager;
import de.affect.manage.CharacterManager;
import de.affect.manage.GroupManager;
import de.affect.gui.AlmaGUI;
import de.affect.emotion.Emotion;
import de.affect.mood.Mood;
import de.affect.personality.Personality;
import de.affect.util.AppraisalTag;
import de.affect.util.Convert;
import de.affect.xml.AffectInputDocument.AffectInput;
import de.affect.xml.AffectComputationDocument;
import de.affect.xml.AffectDefinitionDocument;
import de.affect.xml.AffectDefinitionDocument.AffectDefinition;
import de.affect.xml.EmotionName;
import de.affect.xml.MoodWord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

public class AlmaRestServer {

  private final AffectManager am;
  private final HttpServer http;
  private final Set<String> pausedGroups = new HashSet<>();
  private volatile boolean allPaused;

  public AlmaRestServer(String compSpec, String defSpec, int port) throws Exception {
    // The adaptor owns headless policy; the original ALMA core remains untouched.
    AlmaGUI.sIntegratedDesktopMode = true;
    AffectComputationDocument compDoc;
    try (InputStream input = Files.newInputStream(Paths.get(compSpec))) {
      compDoc = AffectComputationDocument.Factory.parse(input);
    }
    if (compDoc.getAffectComputation().getRuntimeInteractionMonitor() != null) {
      compDoc.getAffectComputation().getRuntimeInteractionMonitor().setEnabled(false);
    }
    byte[] headlessComp = compDoc.xmlText().getBytes(StandardCharsets.UTF_8);
    try (InputStream compInput = new ByteArrayInputStream(headlessComp);
         InputStream defInput = Files.newInputStream(Paths.get(defSpec))) {
      this.am = new AffectManager(compInput, defInput, false);
    }
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
    http.createContext("/appraisal",  this::handleEvent);
    http.createContext("/event",      this::handleEvent);
    http.createContext("/eec",        this::handleEec);
    http.createContext("/act",        this::handleAct);
    http.createContext("/emotion-display", this::handleEmotionDisplay);
    http.createContext("/mood-display",    this::handleMoodDisplay);
    http.createContext("/pad",        this::handlePad);
    http.createContext("/groups",     this::handleGroups);
    http.createContext("/pause",      this::handlePause);
    http.createContext("/resume",     this::handleResume);
  }

  private void handleCharacters(HttpExchange ex) throws IOException {
    if ("POST".equals(ex.getRequestMethod())) { handleCreateCharacter(ex); return; }
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "GET or POST only"); return; }
    StringBuilder sb = new StringBuilder("{\"characters\":[");
    boolean first = true;
    for (CharacterManager c : am.sInterface.getCharacters()) {
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
      Object complexAppraisal = root.get("complex_appraisal");

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
      xml.append("</Basic>");
      if (complexAppraisal != null) appendComplexAppraisalXml(xml, complexAppraisal);
      xml.append("</Appraisal></CharacterAffect></AffectDefinition>");

      AffectDefinitionDocument doc = AffectDefinitionDocument.Factory.parse(xml.toString());
      if (!doc.validate()) throw new IllegalArgumentException("generated character does not validate against ALMA Affect.xsd");
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

    if (target != null && target.startsWith("group/")) {
      String groupName = target.substring("group/".length());
      GroupManager[] groups = am.sInterface.getGroups();
      if (groups != null) for (GroupManager group : groups) {
        if (groupName.equals(group.getName())) { ok(ex, groupAffectJson(group)); return; }
      }
      fail(ex, 404, "group not found: " + groupName);
      return;
    }

    if (target == null || target.isEmpty()) {
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (CharacterManager c : am.sInterface.getCharacters()) {
        if (!first) sb.append(",");
        sb.append(characterAffectJson(c));
        first = false;
      }
      sb.append("]");
      ok(ex, sb.toString());
      return;
    }

    for (CharacterManager c : am.sInterface.getCharacters()) {
      if (target.equals(c.getName())) { ok(ex, characterAffectJson(c)); return; }
    }
    fail(ex, 404, "character not found: " + target);
  }

  private String characterAffectJson(CharacterManager c) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"name\":\"").append(escape(c.getName())).append("\",");
    Personality personality = c.getPersonality();
    sb.append("\"personality\":{")
      .append("\"openness\":").append(personality.getOpenness()).append(",")
      .append("\"conscientiousness\":").append(personality.getConscientiousness()).append(",")
      .append("\"extraversion\":").append(personality.getExtraversion()).append(",")
      .append("\"agreeableness\":").append(personality.getAgreeableness()).append(",")
      .append("\"neurotism\":").append(personality.getNeurotism()).append(",")
      .append("\"emotion_influence\":").append(c.getAffectConsts().personalityEmotionInfluence)
      .append("},");
    Emotion dominant = c.getCurrentEmotions().getDominantEmotion();
    sb.append("\"dominant_emotion\":").append(emotionJson(dominant)).append(",");
    Mood moodTendency = trueMoodTendency(c);
    sb.append("\"mood\":").append(moodJson(c.getCurrentMood())).append(",")
      .append("\"mood_tendency\":").append(moodTendency == null ? "null" : moodJson(moodTendency)).append(",")
      .append("\"default_mood\":").append(moodJson(c.defaultMood())).append(",");
    sb.append("\"emotions\":[");
    boolean first = true;
    for (Iterator<?> it = c.getCurrentEmotions().getEmotions().iterator(); it.hasNext();) {
      Emotion emotion = (Emotion) it.next();
      if (!first) sb.append(",");
      sb.append(emotionJson(emotion));
      first = false;
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String emotionJson(Emotion emotion) {
    if (emotion == null) return "null";
    Object elicitor = emotion.getElicitor();
    Mood pad = emotion.getPADValues();
    return new StringBuilder("{")
      .append("\"name\":\"").append(escape(emotion.getType().toString())).append("\",")
      .append("\"intensity\":").append(emotion.getIntensity()).append(",")
      .append("\"baseline\":").append(emotion.getBaseline()).append(",")
      .append("\"active\":").append(emotion.getIntensity() > emotion.getBaseline()).append(",")
      .append("\"elicitor\":").append(elicitor == null ? "null" : "\"" + escape(String.valueOf(elicitor)) + "\"").append(",")
      .append("\"elicited_at\":").append(emotion.getStart()).append(",")
      .append("\"pad\":").append(pad == null ? "null" : padCoordinatesJson(pad))
      .append("}").toString();
  }

  private static String padCoordinatesJson(Mood mood) {
    return new StringBuilder("{")
      .append("\"pleasure\":").append(mood.getPleasure()).append(",")
      .append("\"arousal\":").append(mood.getArousal()).append(",")
      .append("\"dominance\":").append(mood.getDominance()).append("}").toString();
  }

  private static Mood trueMoodTendency(CharacterManager character) {
    try {
      Field field = character.getClass().getSuperclass().getDeclaredField("fCurrentMoodTendency");
      field.setAccessible(true);
      return (Mood) field.get(character);
    } catch (ReflectiveOperationException | SecurityException e) {
      return null;
    }
  }

  private static String moodJson(Mood mood) {
    return new StringBuilder("{")
      .append("\"word\":\"").append(escape(mood.getMoodWord())).append("\",")
      .append("\"intensity\":\"").append(escape(mood.getMoodWordIntensity())).append("\",")
      .append("\"pleasure\":").append(mood.getPleasure()).append(",")
      .append("\"arousal\":").append(mood.getArousal()).append(",")
      .append("\"dominance\":").append(mood.getDominance())
      .append("}").toString();
  }

  private static String groupAffectJson(GroupManager group) {
    StringBuilder sb = new StringBuilder("{");
    double integrity = group.getSocialIntegrity();
    sb.append("\"name\":\"").append(escape(group.getName())).append("\",")
      .append("\"meta_mood\":").append(moodJson(group.getCurrentMood())).append(",")
      .append("\"social_integrity\":{")
      .append("\"numeric\":").append(integrity).append(",")
      .append("\"label\":\"").append(Convert.valueDescription(integrity)).append("\",")
      .append("\"lower_is_stronger\":true")
      .append("},")
      .append("\"mood_similarities\":[");
    boolean first = true;
    String similarMood = group.getCharactersInSimilarMood();
    if (similarMood != null && !"none".equals(similarMood)) {
      for (String pair : similarMood.split(",")) {
        String[] names = pair.trim().split(" - ", 2);
        if (names.length != 2) continue;
        if (!first) sb.append(',');
        sb.append("{\"first\":\"").append(escape(names[0].trim())).append("\",\"second\":\"")
          .append(escape(names[1].trim())).append("\"}");
        first = false;
      }
    }
    sb.append("],\"mood_extremes\":[");
    first = true;
    List<CharacterManager> extremes = group.getCharactersInExtremeMood();
    if (extremes != null) {
      synchronized (extremes) {
        for (CharacterManager character : extremes) {
          if (!first) sb.append(',');
          sb.append("{\"name\":\"").append(escape(character.getName())).append("\",\"distance_from_default_mood\":")
            .append(character.getDistancetoDefaultMood()).append('}');
          first = false;
        }
      }
    }
    return sb.append("]}").toString();
  }

  private void handleGroups(HttpExchange ex) throws IOException {
    if ("GET".equals(ex.getRequestMethod())) {
      StringBuilder sb = new StringBuilder("{\"groups\":[");
      boolean first = true;
      GroupManager[] groups = am.sInterface.getGroups();
      if (groups != null) {
        for (GroupManager group : groups) {
          if (!first) sb.append(',');
          sb.append('"').append(escape(group.getName())).append('"');
          first = false;
        }
      }
      ok(ex, sb.append("]}").toString());
      return;
    }
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "GET or POST only"); return; }
    try {
      Map<String, Object> body = flexibleBody(ex, new String[] { "name", "characters" },
        "mood", "emotion", "appraisal", "complex_appraisal");
      String name = requiredString(body, "name");
      if (!name.matches("[A-Za-z0-9_. -]{1,80}")) throw new ApiException(400, "invalid group name");
      if (!(body.get("characters") instanceof List)) throw new ApiException(400, "characters must be a JSON array");
      List<?> values = (List<?>) body.get("characters");
      if (values.size() < 2) throw new ApiException(400, "a group requires at least two characters");
      List<String> characters = new ArrayList<>();
      for (Object value : values) {
        if (!(value instanceof String)) throw new ApiException(400, "every group character must be a string");
        String character = ((String) value).trim();
        requireCharacterExists(character);
        if (characters.contains(character)) throw new ApiException(400, "duplicate group character: " + character);
        characters.add(character);
      }
      try {
        am.sInterface.getGroupByName(name);
        throw new ApiException(409, "group already exists: " + name);
      } catch (IllegalArgumentException ignored) {
        // Missing group is expected.
      }
      addGroupFromAdaptor(name, characters, body);
      created(ex, "{\"created\":true,\"name\":\"" + escape(name) + "\",\"characters\":["
        + quotedStrings(characters) + "],\"persistent\":false}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "cannot create group: " + e.getMessage());
    }
  }

  private void addGroupFromAdaptor(String name, List<String> characters, Map<String, Object> body) throws Exception {
    long moodDecayTime = 60000L;
    long moodDecayPeriod = 500L;
    long emotionDecayTime = 20000L;
    long emotionDecayPeriod = 500L;
    String decayFunction = "linear";
    if (body.containsKey("mood")) {
      Map<String, Object> mood = asObject(body.get("mood"), "mood");
      requireExactKeys(mood, "mood", "decay_time", "decay_period");
      moodDecayTime = positiveLong(mood, "decay_time");
      moodDecayPeriod = positiveLong(mood, "decay_period");
      if (moodDecayPeriod > moodDecayTime) throw new IllegalArgumentException("mood.decay_period cannot exceed decay_time");
    }
    if (body.containsKey("emotion")) {
      Map<String, Object> emotion = asObject(body.get("emotion"), "emotion");
      requireExactKeys(emotion, "emotion", "decay_time", "decay_period", "decay_function");
      emotionDecayTime = positiveLong(emotion, "decay_time");
      emotionDecayPeriod = positiveLong(emotion, "decay_period");
      if (emotionDecayPeriod > emotionDecayTime) throw new IllegalArgumentException("emotion.decay_period cannot exceed decay_time");
      decayFunction = requiredString(emotion, "decay_function");
      if (!("linear".equals(decayFunction) || "exponential".equals(decayFunction) || "hyperbolic".equals(decayFunction))) {
        throw new IllegalArgumentException("emotion.decay_function must be linear, exponential or hyperbolic");
      }
    }
    StringBuilder xml = new StringBuilder();
    xml.append("<AffectDefinition xmlns=\"xml.affect.de\"><GroupAffect name=\"")
      .append(xmlEscape(name)).append("\" characters=\"")
      .append(xmlEscape(String.join(",", characters))).append("\" monitored=\"false\" docu=\"\">")
      .append("<MoodSpecification decaytime=\"").append(moodDecayTime).append("\" decayperiod=\"").append(moodDecayPeriod).append("\"/>")
      .append("<EmotionSpecification decaytime=\"").append(emotionDecayTime).append("\" decayperiod=\"").append(emotionDecayPeriod)
      .append("\" decayfunction=\"").append(decayFunction).append("\"/>")
      .append("<Appraisal><Basic>");
    if (body.containsKey("appraisal")) {
      Map<String, Object> appraisal = asObject(body.get("appraisal"), "appraisal");
      validateCompleteAppraisal(appraisal);
      appendAppraisalXml(xml, appraisal);
    } else {
      xml.append("<GoodEvent desirability=\"0.5\"/>")
      .append("<GoodEventForGoodOther desirability=\"0.5\" liking=\"0.5\" agency=\"other\"/>")
      .append("<GoodEventForBadOther desirability=\"0.5\" liking=\"-0.5\" agency=\"other\"/>")
      .append("<BadEvent desirability=\"-0.5\"/>")
      .append("<BadEventForGoodOther desirability=\"-0.5\" liking=\"0.5\" agency=\"other\"/>")
      .append("<BadEventForBadOther desirability=\"-0.5\" liking=\"-0.5\" agency=\"other\"/>")
      .append("<GoodLikelyFutureEvent desirability=\"0.5\" likelihood=\"0.5\"/>")
      .append("<GoodUnlikelyFutureEvent desirability=\"0.5\" likelihood=\"-0.5\"/>")
      .append("<BadLikelyFutureEvent desirability=\"-0.5\" likelihood=\"0.5\"/>")
      .append("<BadUnlikelyFutureEvent desirability=\"-0.5\" likelihood=\"-0.5\"/>")
      .append("<EventConfirmed realization=\"true\"/><EventDisconfirmed realization=\"false\"/>")
      .append("<GoodActSelf praiseworthiness=\"0.5\" agency=\"self\"/>")
      .append("<GoodActOther praiseworthiness=\"0.5\" agency=\"other\"/>")
      .append("<BadActSelf praiseworthiness=\"-0.5\" agency=\"self\"/>")
      .append("<BadActOther praiseworthiness=\"-0.5\" agency=\"other\"/>")
      .append("<NiceThing appealingness=\"0.5\"/><NastyThing appealingness=\"-0.5\"/>");
    }
    xml.append("</Basic>");
    if (body.containsKey("complex_appraisal")) appendGroupComplexAppraisalXml(xml, body.get("complex_appraisal"));
    xml.append("</Appraisal></GroupAffect></AffectDefinition>");

    AffectDefinitionDocument doc = AffectDefinitionDocument.Factory.parse(xml.toString());
    AffectDefinition.GroupAffect profile = doc.getAffectDefinition().getGroupAffectArray(0);
    // AffectDefinition requires at least one CharacterAffect at the document level.
    // Validate the generated GroupAffect itself because this adaptor adds it to the
    // already loaded (and complete) definition document below.
    if (!profile.validate()) throw new IllegalArgumentException("generated group does not validate against ALMA Affect.xsd");
    synchronized (am) {
      AffectDefinition current = am.sInterface.getDocumentManager().getAffectDefinition();
      int index = current.sizeOfGroupAffectArray();
      AffectDefinition.GroupAffect stored = current.addNewGroupAffect();
      stored.set(profile);
      try {
        am.initGroup(stored);
        if (allPaused) {
          GroupManager createdGroup = am.sInterface.getGroupByName(name);
          createdGroup.pauseAffectComputation();
          synchronized (this) { pausedGroups.add(name); }
        }
      } catch (RuntimeException e) {
        current.removeGroupAffect(index);
        throw e;
      }
    }
  }

  private static void appendGroupComplexAppraisalXml(StringBuilder xml, Object value) {
    if (!(value instanceof List)) throw new IllegalArgumentException("complex_appraisal must be a JSON array");
    List<?> entries = (List<?>) value;
    String[] kindOrder = { "indirect_act", "indirect_emotion", "indirect_mood" };
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      boolean supported = false;
      for (String expected : kindOrder) if (expected.equals(kind)) supported = true;
      if (!supported) throw new IllegalArgumentException("groups support only indirect_act, indirect_emotion and indirect_mood");
    }
    for (String expectedKind : kindOrder) for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      if (!expectedKind.equals(kind)) continue;
      String signal = requiredString(entry, "signal");
      String performer = requiredString(entry, "performer");
      Map<String, Object> rules = requiredObject(entry, "appraisal");
      if (rules.isEmpty()) throw new IllegalArgumentException("complex_appraisal[" + i + "].appraisal cannot be empty");
      validateAppraisalSubset(rules);
      String element = "indirect_act".equals(kind) ? "IndirectAct"
        : ("indirect_emotion".equals(kind) ? "IndirectEmotion" : "IndirectMood");
      String signalAttribute = "indirect_act".equals(kind) ? "type"
        : ("indirect_emotion".equals(kind) ? "emotion" : "mood");
      xml.append('<').append(element).append(' ').append(signalAttribute).append("=\"")
        .append(xmlEscape(signal)).append("\" performer=\"").append(xmlEscape(performer)).append("\">");
      appendAppraisalXmlOrdered(xml, rules);
      xml.append("</").append(element).append('>');
    }
  }

  private void handleEvent(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, Object> body = asObject(new JsonParser(readBody(ex)).parse(), "request body");
      if (body.size() != 4) {
        throw new IllegalArgumentException("appraisal must contain exactly: character, tag, intensity, elicitor");
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
        + ",\"elicitor\":\"" + escape(elicitor) + "\",\"signal_kind\":\""
        + appraisalSignalKind(tag) + "\"}");
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

  private static String appraisalSignalKind(String tag) {
    if (tag.endsWith("ActSelf") || tag.endsWith("ActOther")) return "action";
    if ("NiceThing".equals(tag) || "NastyThing".equals(tag)) return "object";
    return "event";
  }

  private void handlePad(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, Object> body = flexibleBody(ex,
        new String[] { "character", "pleasure", "arousal", "dominance", "intensity" },
        "description", "elicitor");
      String character = requiredExistingCharacter(body, "character");
      double p = signedNumber(body, "pleasure");
      double a = signedNumber(body, "arousal");
      double d = signedNumber(body, "dominance");
      double intensity = unsignedNumber(body, "intensity");
      String description = requiredPadDescription(body);
      AffectInput ai = AppraisalTag.instance().makePADInput(character, Double.toString(p), Double.toString(a),
        Double.toString(d), Double.toString(intensity), description);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character) + "\",\"pad\":{"
        + "\"pleasure\":" + p + ",\"arousal\":" + a + ",\"dominance\":" + d
        + "},\"intensity\":" + intensity + ",\"description\":\"" + escape(description)
        + "\",\"result_type\":\"Physical\"}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private void handleEec(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, Object> body = exactBody(ex, 9, "character", "desirability", "praiseworthiness",
        "appealingness", "likelihood", "realization", "liking", "agency", "elicitor");
      String character = requiredExistingCharacter(body, "character");
      String agency = requiredString(body, "agency");
      if (!("self".equals(agency) || "other".equals(agency))) throw new ApiException(400, "agency must be self or other");
      String elicitor = requiredElicitor(body);

      double desirability = signedNumber(body, "desirability");
      double praiseworthiness = signedNumber(body, "praiseworthiness");
      double appealingness = signedNumber(body, "appealingness");
      double likelihood = signedNumber(body, "likelihood");
      double realization = signedNumber(body, "realization");
      double liking = signedNumber(body, "liking");
      String eecKind = validateEecCombination(desirability, praiseworthiness, appealingness,
        likelihood, realization, liking);

      AffectInput ai = AffectInput.Factory.newInstance();
      AffectInput.Character characterNode = AffectInput.Character.Factory.newInstance();
      characterNode.setName(character);
      ai.setCharacter(characterNode);
      AffectInput.BasicEEC eec = AffectInput.BasicEEC.Factory.newInstance();
      eec.setDesirability(desirability);
      eec.setPraiseworthiness(praiseworthiness);
      eec.setAppealingness(appealingness);
      eec.setLikelihood(likelihood);
      eec.setRealization(realization);
      eec.setLiking(liking);
      eec.setAgency(AffectInput.BasicEEC.Agency.Enum.forString(agency));
      eec.setElicitor(elicitor);
      ai.setBasicEEC(eec);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character)
        + "\",\"type\":\"BasicEEC\",\"combination\":\"" + eecKind
        + "\",\"elicitor\":\"" + escape(elicitor) + "\"}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private static String validateEecCombination(double desirability, double praiseworthiness,
      double appealingness, double likelihood, double realization, double liking)
      throws ApiException {
    boolean de = desirability != 0.0d;
    boolean pr = praiseworthiness != 0.0d;
    boolean ap = appealingness != 0.0d;
    boolean li = likelihood != 0.0d;
    boolean re = realization != 0.0d;
    boolean lk = liking != 0.0d;
    if (re && !de && !pr && !ap && !li && !lk) return "realization";
    if (de && pr && !ap && !li && !re && !lk) return "event_action_compound";
    if (!de && pr && ap && !li && !re && !lk) return "action_object_compound";
    if (de && !pr && !ap && !li && !re && !lk) return "desirability";
    if (de && !pr && !ap && li && !re && !lk) return "desirability_likelihood";
    if (de && !pr && !ap && !li && !re && lk) return "desirability_liking";
    if (!de && pr && !ap && !li && !re && !lk) return "praiseworthiness";
    if (!de && !pr && ap && !li && !re && !lk) return "appealingness";
    throw new ApiException(400, "invalid BasicEEC combination; ALMA supports only: realization; "
      + "desirability; desirability+likelihood; desirability+liking; praiseworthiness; "
      + "appealingness; desirability+praiseworthiness; or praiseworthiness+appealingness "
      + "(all unused numeric fields must be 0)");
  }

  private void handleAct(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, Object> body = flexibleBody(ex, new String[] { "performer", "type", "intensity", "elicitor" },
        "addressee", "addressees", "listener", "listeners");
      String performer = requiredExistingCharacter(body, "performer");
      List<String> addressees = participants(body, "addressee", "addressees");
      List<String> listeners = participants(body, "listener", "listeners");
      String type = requiredString(body, "type");
      if (type.length() > 100) throw new ApiException(400, "type must not exceed 100 characters");
      double intensity = unsignedNumber(body, "intensity");
      String elicitor = requiredElicitor(body);

      AffectInput ai = AffectInput.Factory.newInstance();
      AffectInput.Character characterNode = AffectInput.Character.Factory.newInstance();
      characterNode.setName(performer);
      ai.setCharacter(characterNode);
      AffectInput.Act act = AffectInput.Act.Factory.newInstance();
      act.setType(type);
      if (!addressees.isEmpty()) act.setAddressee(String.join(",", addressees));
      if (!listeners.isEmpty()) act.setListener(String.join(",", listeners));
      act.setIntensity(Double.toString(intensity));
      act.setElicitor(elicitor);
      ai.setAct(act);
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"performer\":\"" + escape(performer)
        + "\",\"addressees\":[" + quotedStrings(addressees) + "],\"listeners\":[" + quotedStrings(listeners) + "]"
        + ",\"type\":\"" + escape(type) + "\",\"intensity\":" + intensity
        + ",\"elicitor\":\"" + escape(elicitor) + "\"}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private void handleEmotionDisplay(HttpExchange ex) throws IOException {
    handleDisplay(ex, true);
  }

  private void handleMoodDisplay(HttpExchange ex) throws IOException {
    handleDisplay(ex, false);
  }

  private void handleDisplay(HttpExchange ex, boolean emotion) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, Object> body = flexibleBody(ex, new String[] { "performer", "type", "intensity", "elicitor" },
        "addressee", "addressees", "listener", "listeners");
      String performer = requiredExistingCharacter(body, "performer");
      List<String> addressees = participants(body, "addressee", "addressees");
      List<String> listeners = participants(body, "listener", "listeners");
      String type = requiredString(body, "type");
      double intensity = unsignedNumber(body, "intensity");
      String elicitor = requiredElicitor(body);

      AffectInput ai = AffectInput.Factory.newInstance();
      AffectInput.Character characterNode = AffectInput.Character.Factory.newInstance();
      characterNode.setName(performer);
      ai.setCharacter(characterNode);
      if (emotion) {
        EmotionName.Enum displayType = EmotionName.Enum.forString(type);
        if (displayType == null) throw new ApiException(400, "unknown ALMA emotion display type: " + type);
        AffectInput.EmotionDisplay display = AffectInput.EmotionDisplay.Factory.newInstance();
        display.setType(displayType);
        if (!addressees.isEmpty()) display.setAddressee(String.join(",", addressees));
        if (!listeners.isEmpty()) display.setListener(String.join(",", listeners));
        display.setIntensity(Double.toString(intensity));
        display.setElicitor(elicitor);
        ai.setEmotionDisplay(display);
      } else {
        MoodWord.Enum displayType = MoodWord.Enum.forString(type);
        if (displayType == null) throw new ApiException(400, "unknown ALMA mood display type: " + type);
        AffectInput.MoodDisplay display = AffectInput.MoodDisplay.Factory.newInstance();
        display.setType(displayType);
        if (!addressees.isEmpty()) display.setAddressee(String.join(",", addressees));
        if (!listeners.isEmpty()) display.setListener(String.join(",", listeners));
        display.setIntensity(Double.toString(intensity));
        display.setElicitor(elicitor);
        ai.setMoodDisplay(display);
      }
      am.sInterface.processSignal(ai);
      ok(ex, "{\"accepted\":true,\"signal_kind\":\"" + (emotion ? "emotion_display" : "mood_display")
        + "\",\"performer\":\"" + escape(performer) + "\",\"addressees\":[" + quotedStrings(addressees)
        + "],\"listeners\":[" + quotedStrings(listeners) + "],\"type\":\"" + escape(type)
        + "\",\"intensity\":" + intensity + ",\"elicitor\":\"" + escape(elicitor) + "\"}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private void handlePause(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      String name = queryParam(ex, "character");
      boolean r = (name == null) ? setPausedForAll(true)
                                 : am.sInterface.getCharacterByName(name).pauseAffectComputation();
      ok(ex, "{\"paused\":" + r + "}");
    } catch (IllegalArgumentException e) {
      fail(ex, 404, "character not found: " + queryParam(ex, "character"));
    }
  }

  private void handleResume(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      String name = queryParam(ex, "character");
      boolean r = (name == null) ? setPausedForAll(false)
                                 : am.sInterface.getCharacterByName(name).resumeAffectComputation();
      ok(ex, "{\"resumed\":" + r + "}");
    } catch (IllegalArgumentException e) {
      fail(ex, 404, "character not found: " + queryParam(ex, "character"));
    }
  }

  private synchronized boolean setPausedForAll(boolean paused) {
    allPaused = paused;
    boolean changedAny = false;
    for (CharacterManager character : am.sInterface.getCharacters()) {
      if (paused && !character.isAffectComputationPaused()) {
        changedAny = character.pauseAffectComputation() || changedAny;
      } else if (!paused && character.isAffectComputationPaused()) {
        changedAny = character.resumeAffectComputation() || changedAny;
      }
    }
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups != null) for (GroupManager group : groups) {
      if (paused && !pausedGroups.contains(group.getName())) {
        changedAny = group.pauseAffectComputation() || changedAny;
        pausedGroups.add(group.getName());
      } else if (!paused && pausedGroups.remove(group.getName())) {
        // Original GroupManager does not clear its paused flag on resume. The
        // adaptor tracks the actual transition to prevent duplicate timers.
        changedAny = group.resumeAffectComputation() || changedAny;
      }
    }
    return changedAny;
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

  private static Map<String, Object> exactBody(HttpExchange ex, int size, String... fields)
      throws IOException, ApiException {
    final Map<String, Object> body;
    try {
      body = asObject(new JsonParser(readBody(ex)).parse(), "request body");
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, e.getMessage());
    }
    if (body.size() != size) throw new ApiException(400, "request must contain exactly: " + String.join(", ", fields));
    for (String field : fields) {
      if (!body.containsKey(field)) throw new ApiException(400, "missing required field: " + field);
    }
    return body;
  }

  private static Map<String, Object> flexibleBody(HttpExchange ex, String[] required, String... optional)
      throws IOException, ApiException {
    final Map<String, Object> body;
    try {
      body = asObject(new JsonParser(readBody(ex)).parse(), "request body");
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, e.getMessage());
    }
    Set<String> allowed = new HashSet<>();
    for (String field : required) allowed.add(field);
    for (String field : optional) allowed.add(field);
    for (String field : body.keySet()) {
      if (!allowed.contains(field)) throw new ApiException(400, "unknown field: " + field);
    }
    for (String field : required) {
      if (!body.containsKey(field)) throw new ApiException(400, "missing required field: " + field);
    }
    return body;
  }

  private List<String> participants(Map<String, Object> body, String singular, String plural)
      throws ApiException {
    if (body.containsKey(singular) && body.containsKey(plural)) {
      throw new ApiException(400, "use either " + singular + " or " + plural + ", not both");
    }
    List<String> result = new ArrayList<>();
    if (body.containsKey(singular)) {
      final String name;
      try { name = requiredString(body, singular); }
      catch (IllegalArgumentException e) { throw new ApiException(400, e.getMessage()); }
      requireCharacterExists(name);
      result.add(name);
    } else if (body.containsKey(plural)) {
      Object value = body.get(plural);
      if (!(value instanceof List)) throw new ApiException(400, plural + " must be a JSON array");
      for (Object item : (List<?>) value) {
        if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
          throw new ApiException(400, "every " + plural + " entry must be a non-empty string");
        }
        String name = ((String) item).trim();
        requireCharacterExists(name);
        if (result.contains(name)) throw new ApiException(400, "duplicate " + plural + " entry: " + name);
        result.add(name);
      }
    }
    return result;
  }

  private String requiredExistingCharacter(Map<String, Object> body, String field) throws ApiException {
    final String name;
    try { name = requiredString(body, field); }
    catch (IllegalArgumentException e) { throw new ApiException(400, e.getMessage()); }
    requireCharacterExists(name);
    return name;
  }

  private void requireCharacterExists(String name) throws ApiException {
    try { am.sInterface.getCharacterByName(name); }
    catch (IllegalArgumentException e) { throw new ApiException(404, "character not found: " + name); }
  }

  private static String requiredElicitor(Map<String, Object> body) throws ApiException {
    final String elicitor;
    try { elicitor = requiredString(body, "elicitor"); }
    catch (IllegalArgumentException e) { throw new ApiException(400, e.getMessage()); }
    if (elicitor.length() > 200) throw new ApiException(400, "elicitor must not exceed 200 characters");
    return elicitor;
  }

  private static String requiredPadDescription(Map<String, Object> body) throws ApiException {
    if (body.containsKey("description") && body.containsKey("elicitor")) {
      throw new ApiException(400, "use description; elicitor is only a backward-compatible alias and cannot be supplied together");
    }
    String key = body.containsKey("description") ? "description" : "elicitor";
    if (!body.containsKey(key)) throw new ApiException(400, "missing required field: description");
    final String description;
    try { description = requiredString(body, key); }
    catch (IllegalArgumentException e) { throw new ApiException(400, e.getMessage()); }
    if (description.length() > 200) throw new ApiException(400, "description must not exceed 200 characters");
    return description;
  }

  private static double signedNumber(Map<String, Object> body, String field) throws ApiException {
    Object value = body.get(field);
    if (!(value instanceof Number)) throw new ApiException(400, field + " must be a JSON number");
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number < -1.0 || number > 1.0) {
      throw new ApiException(400, field + " must be between -1.0 and 1.0");
    }
    return number;
  }

  private static double unsignedNumber(Map<String, Object> body, String field) throws ApiException {
    Object value = body.get(field);
    if (!(value instanceof Number)) throw new ApiException(400, field + " must be a JSON number");
    double number = ((Number) value).doubleValue();
    if (!Double.isFinite(number) || number < 0.0 || number > 1.0) {
      throw new ApiException(400, field + " must be between 0.0 and 1.0");
    }
    return number;
  }

  private static String quotedStrings(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(escape(values.get(i))).append('"');
    }
    return sb.toString();
  }

  private static final class ApiException extends Exception {
    final int status;
    ApiException(int status, String message) { super(message); this.status = status; }
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

  private static void appendComplexAppraisalXml(StringBuilder xml, Object value) {
    if (!(value instanceof List)) throw new IllegalArgumentException("complex_appraisal must be a JSON array");
    List<?> entries = (List<?>) value;
    String[] kindOrder = { "self_act", "direct_act", "indirect_act", "self_emotion",
      "indirect_emotion", "self_mood", "indirect_mood" };
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      boolean supported = false;
      for (String expected : kindOrder) if (expected.equals(kind)) supported = true;
      if (!supported) throw new IllegalArgumentException("unsupported complex appraisal kind: " + kind);
    }
    for (String expectedKind : kindOrder) for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      if (!expectedKind.equals(kind)) continue;
      String signal = requiredString(entry, "signal");
      Map<String, Object> rules = requiredObject(entry, "appraisal");
      if (rules.isEmpty()) throw new IllegalArgumentException("complex_appraisal[" + i + "].appraisal cannot be empty");
      validateAppraisalSubset(rules);

      String element;
      String signalAttribute;
      boolean needsPerformer;
      if ("self_act".equals(kind)) { element = "SelfAct"; signalAttribute = "type"; needsPerformer = false; }
      else if ("direct_act".equals(kind)) { element = "DirectAct"; signalAttribute = "type"; needsPerformer = true; }
      else if ("indirect_act".equals(kind)) { element = "IndirectAct"; signalAttribute = "type"; needsPerformer = true; }
      else if ("self_emotion".equals(kind)) { element = "SelfEmotion"; signalAttribute = "emotion"; needsPerformer = false; }
      else if ("indirect_emotion".equals(kind)) { element = "IndirectEmotion"; signalAttribute = "emotion"; needsPerformer = true; }
      else if ("self_mood".equals(kind)) { element = "SelfMood"; signalAttribute = "mood"; needsPerformer = false; }
      else if ("indirect_mood".equals(kind)) { element = "IndirectMood"; signalAttribute = "mood"; needsPerformer = true; }
      else throw new IllegalArgumentException("unsupported complex appraisal kind: " + kind);

      xml.append('<').append(element).append(' ').append(signalAttribute).append("=\"")
        .append(xmlEscape(signal)).append('"');
      if (needsPerformer) {
        String performer = requiredString(entry, "performer");
        xml.append(" performer=\"").append(xmlEscape(performer)).append('"');
      }
      xml.append('>');
      appendAppraisalXmlOrdered(xml, rules);
      xml.append("</").append(element).append('>');
    }
  }

  private static void appendAppraisalXmlOrdered(StringBuilder xml, Map<String, Object> rules) {
    String[] order = complexTagNamesInXsdOrder();
    for (String tag : order) {
      if (!rules.containsKey(tag)) continue;
      Map<String, Object> single = new LinkedHashMap<>();
      single.put(tag, rules.get(tag));
      appendAppraisalXml(xml, single);
    }
  }

  private static String[] complexTagNamesInXsdOrder() {
    return new String[] {
      "GoodEvent", "GoodEventForGoodOther", "GoodEventForBadOther",
      "GoodLikelyFutureEvent", "GoodUnlikelyFutureEvent",
      "BadEvent", "BadEventForGoodOther", "BadEventForBadOther",
      "BadLikelyFutureEvent", "BadUnlikelyFutureEvent",
      "EventConfirmed", "EventDisconfirmed",
      "GoodActSelf", "BadActSelf", "GoodActOther", "BadActOther",
      "NiceThing", "NastyThing"
    };
  }

  private static void validateAppraisalSubset(Map<String, Object> appraisal) {
    for (String tag : appraisal.keySet()) {
      if (!isExactAppraisalTag(tag)) throw new IllegalArgumentException("unsupported complex appraisal tag: " + tag);
      if ("GoodEvent".equals(tag)) positiveRule(appraisal, tag, "desirability");
      else if ("BadEvent".equals(tag)) negativeRule(appraisal, tag, "desirability");
      else if ("GoodEventForGoodOther".equals(tag)) otherEventRule(appraisal, tag, true, true);
      else if ("GoodEventForBadOther".equals(tag)) otherEventRule(appraisal, tag, true, false);
      else if ("BadEventForGoodOther".equals(tag)) otherEventRule(appraisal, tag, false, true);
      else if ("BadEventForBadOther".equals(tag)) otherEventRule(appraisal, tag, false, false);
      else if ("GoodLikelyFutureEvent".equals(tag)) futureRule(appraisal, tag, true, true);
      else if ("GoodUnlikelyFutureEvent".equals(tag)) futureRule(appraisal, tag, true, false);
      else if ("BadLikelyFutureEvent".equals(tag)) futureRule(appraisal, tag, false, true);
      else if ("BadUnlikelyFutureEvent".equals(tag)) futureRule(appraisal, tag, false, false);
      else if ("EventConfirmed".equals(tag)) realizationRule(appraisal, tag, true);
      else if ("EventDisconfirmed".equals(tag)) realizationRule(appraisal, tag, false);
      else if ("GoodActSelf".equals(tag)) actionRule(appraisal, tag, "self", true);
      else if ("GoodActOther".equals(tag)) actionRule(appraisal, tag, "other", true);
      else if ("BadActSelf".equals(tag)) actionRule(appraisal, tag, "self", false);
      else if ("BadActOther".equals(tag)) actionRule(appraisal, tag, "other", false);
      else if ("NiceThing".equals(tag)) positiveRule(appraisal, tag, "appealingness");
      else if ("NastyThing".equals(tag)) negativeRule(appraisal, tag, "appealingness");
    }
  }

  private static String[] basicTagNames() {
    return new String[] {
      "GoodEvent", "GoodEventForGoodOther", "GoodEventForBadOther",
      "BadEvent", "BadEventForGoodOther", "BadEventForBadOther",
      "GoodLikelyFutureEvent", "GoodUnlikelyFutureEvent",
      "BadLikelyFutureEvent", "BadUnlikelyFutureEvent",
      "EventConfirmed", "EventDisconfirmed",
      "GoodActSelf", "GoodActOther", "BadActSelf", "BadActOther",
      "NiceThing", "NastyThing"
    };
  }

  private static void validateCompleteAppraisal(Map<String, Object> appraisal) {
    String[] requiredTags = basicTagNames();
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

  private static void requireExactKeys(Map<String, Object> object, String field, String... keys) {
    Set<String> expected = new HashSet<>();
    for (String key : keys) expected.add(key);
    if (!object.keySet().equals(expected)) {
      throw new IllegalArgumentException(field + " must contain exactly: " + String.join(", ", keys));
    }
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
      if (c == '[') return readArray();
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

    private List<Object> readArray() {
      List<Object> array = new ArrayList<>();
      pos++;
      skipWhitespace();
      if (consume(']')) return array;
      while (true) {
        array.add(readValue());
        skipWhitespace();
        if (consume(']')) return array;
        if (!consume(',')) error("expected ',' or ']'");
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
      if (eq > 0 && decodeQuery(kv.substring(0, eq)).equals(key)) {
        return decodeQuery(kv.substring(eq + 1));
      }
    }
    return null;
  }

  private static String decodeQuery(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
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
    System.out.println("  POST /characters {name, personality, mood, emotion, appraisal, complex_appraisal?}");
    System.out.println("  GET  /affect");
    System.out.println("  GET  /affect/{name}");
    System.out.println("  GET  /affect/group/{name}");
    System.out.println("  POST /appraisal  {character, tag, intensity, elicitor}");
    System.out.println("  POST /event      backward-compatible alias of /appraisal");
    System.out.println("  POST /eec        {character, desirability, praiseworthiness, appealingness, likelihood, realization, liking, agency, elicitor}");
    System.out.println("  POST /pad        {character, pleasure, arousal, dominance, intensity, description}");
    System.out.println("  POST /act        {performer, type, intensity, elicitor, addressee(s)?, listener(s)?}");
    System.out.println("  POST /emotion-display  {performer, type, intensity, elicitor, addressee(s)?, listener(s)?}");
    System.out.println("  POST /mood-display     {performer, type, intensity, elicitor, addressee(s)?, listener(s)?}");
    System.out.println("  GET|POST /groups {name, characters, mood?, emotion?, appraisal?, complex_appraisal?}");
    System.out.println("  POST /pause?character={name}");
    System.out.println("  POST /resume?character={name}");
  }
}
