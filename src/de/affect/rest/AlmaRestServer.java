package de.affect.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.affect.manage.AffectManager;
import de.affect.manage.AppraisalRules;
import de.affect.manage.CharacterManager;
import de.affect.manage.GroupManager;
import de.affect.compute.EmotionEngine;
import de.affect.compute.MoodEngine;
import de.affect.appraisal.AppraisalVariables;
import de.affect.gui.AlmaGUI;
import de.affect.emotion.Emotion;
import de.affect.emotion.EmotionAppraisalVars;
import de.affect.emotion.EmotionHistory;
import de.affect.emotion.EmotionVector;
import de.affect.emotion.EmotionsPADRelation;
import de.affect.emotion.EmotionType;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;

public class AlmaRestServer {

  private static final String INTERNAL_EMOTION_ELICITOR = "alma internal emotion appraisal";
  private static final String INTERNAL_MOOD_ELICITOR = "alma internal mood appraisal";
  private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
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
    AffectDefinitionDocument defDoc;
    try (InputStream input = Files.newInputStream(Paths.get(defSpec))) {
      defDoc = AffectDefinitionDocument.Factory.parse(input);
    }
    validateDefinitionInternalAppraisalSafety(defDoc);
    List<String> deferredInternalAppraisal = internalAppraisalCharacterNames(defDoc);
    setDefinitionInternalAppraisal(defDoc, deferredInternalAppraisal, false);
    byte[] headlessComp = compDoc.xmlText().getBytes(StandardCharsets.UTF_8);
    byte[] validatedDef = defDoc.xmlText().getBytes(StandardCharsets.UTF_8);
    try (InputStream compInput = new ByteArrayInputStream(headlessComp);
         InputStream defInput = new ByteArrayInputStream(validatedDef)) {
      this.am = new AffectManager(compInput, defInput, false);
    }
    normalizeMissingGroupAppraisalRules();
    restoreLoadedInternalAppraisalFlags(deferredInternalAppraisal);
    validateLoadedInternalAppraisalSafety();
    startDeferredInternalAppraisal(deferredInternalAppraisal);
    this.http = HttpServer.create(new InetSocketAddress(port), 0);
    this.http.setExecutor(Executors.newFixedThreadPool(4));
    registerHandlers();
  }

  public void start() {
    http.start();
    System.out.println("[alma-rest] listening on http://localhost:" + http.getAddress().getPort());
  }

  private void registerHandlers() {
    http.createContext("/health",     this::handleHealth);
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
    http.createContext("/step",       this::handleStep);
  }

  private void handleHealth(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/health")) return;
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "GET only"); return; }
    ok(ex, "{\"status\":\"ok\",\"alma_version\":\"3.0\"}");
  }

  /**
   * Group Appraisal is optional in Affect.xsd. The legacy signal path assumes
   * a non-null rule container, however, so represent an omitted Appraisal as
   * an empty rule set. This preserves the schema meaning (the group appraises
   * nothing) without changing the ALMA core.
   */
  private void normalizeMissingGroupAppraisalRules() {
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    for (GroupManager group : groups) {
      if (group.getAppraisalRules() == null) group.setAppraisalRules(new AppraisalRules());
    }
  }

  private static List<String> internalAppraisalCharacterNames(AffectDefinitionDocument document) {
    List<String> names = new ArrayList<>();
    AffectDefinition definition = document.getAffectDefinition();
    if (definition == null) return names;
    for (AffectDefinition.CharacterAffect profile : definition.getCharacterAffectList()) {
      AffectDefinition.CharacterAffect.Appraisal appraisal = profile.getAppraisal();
      if (appraisal != null && appraisal.isSetInternalAffectAppraisal()
          && appraisal.getInternalAffectAppraisal() && !names.contains(profile.getName())) {
        names.add(profile.getName());
      }
    }
    return names;
  }

  /** Prevent original 500 ms timers from firing before AffectManager has initialized its groups. */
  private static void setDefinitionInternalAppraisal(AffectDefinitionDocument document,
      List<String> names, boolean enabled) {
    AffectDefinition definition = document.getAffectDefinition();
    if (definition == null || names.isEmpty()) return;
    for (AffectDefinition.CharacterAffect profile : definition.getCharacterAffectList()) {
      if (names.contains(profile.getName()) && profile.getAppraisal() != null) {
        profile.getAppraisal().setInternalAffectAppraisal(enabled);
      }
    }
  }

  private void restoreLoadedInternalAppraisalFlags(List<String> names) {
    AffectDefinition loaded = am.sInterface.getDocumentManager().getAffectDefinition();
    if (loaded == null || names.isEmpty()) return;
    for (AffectDefinition.CharacterAffect profile : loaded.getCharacterAffectList()) {
      if (names.contains(profile.getName()) && profile.getAppraisal() != null) {
        profile.getAppraisal().setInternalAffectAppraisal(true);
      }
    }
  }

  /** Start the unchanged package-private core simulation only after every group is ready. */
  @SuppressWarnings("unchecked")
  private void startDeferredInternalAppraisal(List<String> names) {
    if (names.isEmpty()) return;
    try {
      Class<?> simulationClass = Class.forName("de.affect.manage.AffectAppraisalSimulation");
      java.lang.reflect.Constructor<?> constructor = simulationClass.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      Field interfaceField = simulationClass.getDeclaredField("affectManager");
      interfaceField.setAccessible(true);
      interfaceField.set(null, am.sInterface);
      Field simulationsField = AffectManager.class.getDeclaredField("fNameToAppraisalSimulation");
      simulationsField.setAccessible(true);
      Map<String, Object> simulations = (Map<String, Object>) simulationsField.get(am);
      if (simulations == null) throw new IllegalStateException("core internal appraisal registry is unavailable");
      for (String name : names) {
        CharacterManager character = am.sInterface.getCharacterByName(name);
        simulations.put(name, constructor.newInstance(character));
      }
    } catch (ReflectiveOperationException | SecurityException e) {
      throw new IllegalStateException("cannot safely start original ALMA internal appraisal simulation", e);
    }
  }

  /** Validate autonomous routes before AffectManager can start its 500 ms timers. */
  private static void validateDefinitionInternalAppraisalSafety(AffectDefinitionDocument document) {
    AffectDefinition definition = document.getAffectDefinition();
    if (definition == null) return;
    Set<String> internalCharacters = new HashSet<>();
    for (AffectDefinition.CharacterAffect profile : definition.getCharacterAffectList()) {
      AffectDefinition.CharacterAffect.Appraisal appraisal = profile.getAppraisal();
      if (appraisal == null || !appraisal.isSetInternalAffectAppraisal()
          || !appraisal.getInternalAffectAppraisal()) continue;
      internalCharacters.add(profile.getName());
      CompoundCandidates emotionRules = new CompoundCandidates();
      CompoundCandidates moodRules = new CompoundCandidates();
      for (RawXmlRule rule : effectiveCharacterXmlRules(profile).values()) {
        if (!profile.getName().equals(rule.entity)) continue;
        if ("SelfEmotion".equals(rule.type)) {
          emotionRules.merge(compoundCandidatesFromXmlTag(rule.tag, "internal emotion appraisal"));
        } else if ("SelfMood".equals(rule.type)) {
          moodRules.merge(compoundCandidatesFromXmlTag(rule.tag, "internal mood appraisal"));
        }
      }
      rejectAutonomousCompound(emotionRules, "internal emotion appraisal for " + profile.getName());
      rejectAutonomousCompound(moodRules, "internal mood appraisal for " + profile.getName());
    }
    for (AffectDefinition.GroupAffect profile : definition.getGroupAffectList()) {
      AffectDefinition.GroupAffect.Appraisal appraisal = profile.getAppraisal();
      Set<String> members = new HashSet<>();
      String characterList = profile.getCharacters();
      if (characterList != null) for (String member : characterList.split(",")) members.add(member.trim());
      if (appraisal == null) continue;
      CompoundCandidates emotionRules = new CompoundCandidates();
      CompoundCandidates moodRules = new CompoundCandidates();
      for (RawXmlRule rule : effectiveGroupXmlRules(appraisal).values()) {
        if (!members.contains(rule.entity) || !internalCharacters.contains(rule.entity)) continue;
        if ("ExternalEmotion".equals(rule.type)) {
          emotionRules.merge(compoundCandidatesFromXmlTag(rule.tag,
            "group " + profile.getName() + " internal emotion listener"));
        } else if ("ExternalMood".equals(rule.type)) {
          moodRules.merge(compoundCandidatesFromXmlTag(rule.tag,
            "group " + profile.getName() + " internal mood listener"));
        }
      }
      rejectAutonomousCompound(emotionRules, "group " + profile.getName() + " internal emotion listener");
      rejectAutonomousCompound(moodRules, "group " + profile.getName() + " internal mood listener");
    }
  }

  /** Reproduce AppraisalRuleReader order and AppraisalRules' last-write-wins storage. */
  private static Map<String, RawXmlRule> effectiveCharacterXmlRules(
      AffectDefinition.CharacterAffect profile) {
    Map<String, RawXmlRule> rules = new LinkedHashMap<>();
    AffectDefinition.CharacterAffect.Appraisal appraisal = profile.getAppraisal();
    if (appraisal == null) return rules;
    String owner = profile.getName();
    for (de.affect.xml.SelfActType rule : appraisal.getSelfActList()) {
      putRawXmlRule(rules, owner, rule.getType(), "SelfAct", rule);
    }
    for (de.affect.xml.DirectActType rule : appraisal.getDirectActList()) {
      putRawXmlRule(rules, rule.getPerformer(), rule.getType(), "DirectAct", rule);
    }
    for (de.affect.xml.IndirectActType rule : appraisal.getIndirectActList()) {
      putRawXmlRule(rules, rule.getPerformer(), rule.getType(), "IndirectAct", rule);
    }
    for (de.affect.xml.SelfEmotionType rule : appraisal.getSelfEmotionList()) {
      putRawXmlRule(rules, owner, String.valueOf(rule.getEmotion()), "SelfEmotion", rule);
    }
    for (de.affect.xml.IndirectEmotionType rule : appraisal.getIndirectEmotionList()) {
      putRawXmlRule(rules, rule.getPerformer(), String.valueOf(rule.getEmotion()), "ExternalEmotion", rule);
    }
    for (de.affect.xml.SelfMoodType rule : appraisal.getSelfMoodList()) {
      putRawXmlRule(rules, owner, String.valueOf(rule.getMood()), "SelfMood", rule);
    }
    for (de.affect.xml.IndirectMoodType rule : appraisal.getIndirectMoodList()) {
      putRawXmlRule(rules, rule.getPerformer(), String.valueOf(rule.getMood()), "ExternalMood", rule);
    }
    return rules;
  }

  private static Map<String, RawXmlRule> effectiveGroupXmlRules(
      AffectDefinition.GroupAffect.Appraisal appraisal) {
    Map<String, RawXmlRule> rules = new LinkedHashMap<>();
    for (de.affect.xml.IndirectActType rule : appraisal.getIndirectActList()) {
      putRawXmlRule(rules, rule.getPerformer(), rule.getType(), "IndirectAct", rule);
    }
    for (de.affect.xml.IndirectEmotionType rule : appraisal.getIndirectEmotionList()) {
      putRawXmlRule(rules, rule.getPerformer(), String.valueOf(rule.getEmotion()), "ExternalEmotion", rule);
    }
    for (de.affect.xml.IndirectMoodType rule : appraisal.getIndirectMoodList()) {
      putRawXmlRule(rules, rule.getPerformer(), String.valueOf(rule.getMood()), "ExternalMood", rule);
    }
    return rules;
  }

  private static void putRawXmlRule(Map<String, RawXmlRule> rules, String entity, String signal,
      String type, de.affect.xml.AppraisalTag tag) {
    rules.put(storageKey(entity, signal), new RawXmlRule(entity, type, tag));
  }

  private static final class RawXmlRule {
    final String entity;
    final String type;
    final de.affect.xml.AppraisalTag tag;

    RawXmlRule(String entity, String type, de.affect.xml.AppraisalTag tag) {
      this.entity = entity;
      this.type = type;
      this.tag = tag;
    }
  }

  private static CompoundCandidates compoundCandidatesFromXmlTag(de.affect.xml.AppraisalTag tag,
      String description) {
    int events = 0;
    events += tag.isSetGoodEvent() ? 1 : 0;
    events += tag.isSetGoodEventForGoodOther() ? 1 : 0;
    events += tag.isSetGoodEventForBadOther() ? 1 : 0;
    events += tag.isSetGoodLikelyFutureEvent() ? 1 : 0;
    events += tag.isSetGoodUnlikelyFutureEvent() ? 1 : 0;
    events += tag.isSetBadEvent() ? 1 : 0;
    events += tag.isSetBadEventForGoodOther() ? 1 : 0;
    events += tag.isSetBadEventForBadOther() ? 1 : 0;
    events += tag.isSetBadLikelyFutureEvent() ? 1 : 0;
    events += tag.isSetBadUnlikelyFutureEvent() ? 1 : 0;
    events += tag.isSetEventConfirmed() ? 1 : 0;
    events += tag.isSetEventDisconfirmed() ? 1 : 0;
    int actions = (tag.isSetGoodActSelf() ? 1 : 0) + (tag.isSetBadActSelf() ? 1 : 0)
      + (tag.isSetGoodActOther() ? 1 : 0) + (tag.isSetBadActOther() ? 1 : 0);
    int objects = (tag.isSetNiceThing() ? 1 : 0) + (tag.isSetNastyThing() ? 1 : 0);
    if (events > 1 || actions > 1 || objects > 1) {
      throw new IllegalArgumentException(description + " contains more than one Event, Action or Object EEC; "
        + "the original core has only one pending slot for each category");
    }
    CompoundCandidates result = new CompoundCandidates();
    if (tag.isSetGoodActOther()) {
      addAttributionCandidate(result,
        Convert.doubleValue(tag.getGoodActOther().getPraiseworthiness()));
    }
    if (tag.isSetBadActOther()) {
      addAttributionCandidate(result,
        Convert.doubleValue(tag.getBadActOther().getPraiseworthiness()));
    }
    if (tag.isSetNiceThing()) {
      addAttractionCandidate(result,
        Convert.doubleValue(tag.getNiceThing().getAppealingness()));
    }
    if (tag.isSetNastyThing()) {
      addAttractionCandidate(result,
        Convert.doubleValue(tag.getNastyThing().getAppealingness()));
    }
    return result;
  }

  /**
   * Internal appraisal uses one fixed elicitor for every emotion display and
   * another for every mood display. Across different rules that can therefore
   * enter the legacy Love/Hate compound bug without going through REST
   * preflight. Refuse only those unsafe autonomous rule families.
   */
  private void validateLoadedInternalAppraisalSafety() {
    AffectDefinition definition = am.sInterface.getDocumentManager().getAffectDefinition();
    if (definition == null) return;
    for (AffectDefinition.CharacterAffect profile : definition.getCharacterAffectList()) {
      if (profile.getAppraisal() == null
          || !profile.getAppraisal().isSetInternalAffectAppraisal()
          || !profile.getAppraisal().getInternalAffectAppraisal()) continue;
      CharacterManager character = am.sInterface.getCharacterByName(profile.getName());
      validateAutonomousRuleFamily(character.getAppraisalRules(), profile.getName(), "SelfEmotion",
        "internal emotion appraisal");
      validateAutonomousRuleFamily(character.getAppraisalRules(), profile.getName(), "SelfMood",
        "internal mood appraisal");
    }
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    for (GroupManager group : groups) {
      CompoundCandidates emotionRules = new CompoundCandidates();
      CompoundCandidates moodRules = new CompoundCandidates();
      CharacterManager[] members = group.getCharacters();
      if (members == null) continue;
      for (CharacterManager member : members) {
        if (!internalAffectAppraisalEnabled(member.getName())) continue;
        emotionRules.merge(autonomousRuleFamilyCandidates(group.getAppraisalRules(), member.getName(),
          "ExternalEmotion", "group " + group.getName() + " internal emotion listener"));
        moodRules.merge(autonomousRuleFamilyCandidates(group.getAppraisalRules(), member.getName(),
          "ExternalMood", "group " + group.getName() + " internal mood listener"));
      }
      rejectAutonomousCompound(emotionRules, "group " + group.getName() + " internal emotion listener");
      rejectAutonomousCompound(moodRules, "group " + group.getName() + " internal mood listener");
    }
  }

  private static void validateAutonomousRuleFamily(AppraisalRules rules, String entity, String ruleType,
      String description) {
    rejectAutonomousCompound(autonomousRuleFamilyCandidates(rules, entity, ruleType, description), description);
  }

  private static CompoundCandidates autonomousRuleFamilyCandidates(AppraisalRules rules, String entity,
      String ruleType, String description) {
    CompoundCandidates combined = new CompoundCandidates();
    if (rules == null) return combined;
    AppraisalRules subset = rules.getAppraisalRulesByType(entity, ruleType);
    for (String key : subset.getKeys(entity)) {
      try {
        combined.merge(compoundCandidates(subset.getAppraisalVariables(entity, key), 1.0d));
      } catch (ApiException e) {
        throw new IllegalArgumentException(description + " is unsafe: " + e.getMessage());
      }
    }
    return combined;
  }

  private static void rejectAutonomousCompound(CompoundCandidates candidates, String description) {
    if ((candidates.admiration && candidates.liking)
        || (candidates.reproach && candidates.disliking)) {
      throw new IllegalArgumentException(description + " can combine same-elicitor Love/Hate across rules; "
        + "the unmodified ALMA 3.0 compound path crashes for this configuration");
    }
  }

  private void handleCharacters(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/characters")) return;
    if ("POST".equals(ex.getRequestMethod())) { handleCreateCharacter(ex); return; }
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "GET or POST only"); return; }
    String json;
    synchronized (am) {
      StringBuilder sb = new StringBuilder("{\"characters\":[");
      boolean first = true;
      for (CharacterManager c : am.sInterface.getCharacters()) {
        if (!first) sb.append(",");
        sb.append("\"").append(escape(c.getName())).append("\"");
        first = false;
      }
      json = sb.append("]}").toString();
    }
    ok(ex, json);
  }

  private void handleCreateCharacter(HttpExchange ex) throws IOException {
    try {
      Object parsed = new JsonParser(readBody(ex)).parse();
      Map<String, Object> root = asObject(parsed, "request body");
      requireAllowedKeys(root, "request body",
        new String[] { "name", "personality", "mood", "emotion", "appraisal" },
        "complex_appraisal", "internal_affect_appraisal");
      String name = requiredString(root, "name");
      if (!name.matches("[A-Za-z0-9_. -]{1,80}")) {
        throw new IllegalArgumentException("name must be 1-80 letters, numbers, spaces, '.', '_' or '-'");
      }
      if (name.contains(" - ")) {
        throw new IllegalArgumentException("name cannot contain the reserved group-summary delimiter ' - '");
      }

      Map<String, Object> personality = requiredObject(root, "personality");
      Map<String, Object> mood = requiredObject(root, "mood");
      Map<String, Object> emotion = requiredObject(root, "emotion");
      Map<String, Object> appraisal = requiredObject(root, "appraisal");
      Object complexAppraisal = root.get("complex_appraisal");
      boolean internalAffectAppraisal = optionalBoolean(root, "internal_affect_appraisal", false);

      requireAllowedKeys(personality, "personality",
        new String[] { "openness", "conscientiousness", "extraversion", "agreeableness", "neurotism", "emotion_influence" },
        "derived");
      double openness = unit(personality, "openness");
      double conscientiousness = unit(personality, "conscientiousness");
      double extraversion = unit(personality, "extraversion");
      double agreeableness = unit(personality, "agreeableness");
      double neurotism = unit(personality, "neurotism");
      double emotionInfluence = nonNegativeUnit(personality, "emotion_influence");
      boolean derived = optionalBoolean(personality, "derived", false);

      requireExactKeys(mood, "mood", "decay_time", "decay_period", "neurotism_stability");
      long moodDecayTime = positiveLong(mood, "decay_time");
      long moodDecayPeriod = positiveLong(mood, "decay_period");
      boolean neurotismStability = requiredBoolean(mood, "neurotism_stability");
      if (moodDecayPeriod > moodDecayTime) throw new IllegalArgumentException("mood.decay_period cannot exceed decay_time");

      requireExactKeys(emotion, "emotion", "decay_time", "decay_period", "decay_function", "baseline");
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
        .append("<PersonalitySpecification derived=\"").append(derived).append("\" emotioninfluence=\"").append(emotionInfluence)
        .append("\" openness=\"").append(openness).append("\" conscientiousness=\"").append(conscientiousness)
        .append("\" extraversion=\"").append(extraversion).append("\" agreeableness=\"").append(agreeableness)
        .append("\" neurotism=\"").append(neurotism).append("\"/>")
        .append("<MoodSpecification decaytime=\"").append(moodDecayTime).append("\" decayperiod=\"").append(moodDecayPeriod)
        .append("\" neurotismstability=\"").append(neurotismStability).append("\"/>")
        .append("<EmotionSpecification decaytime=\"").append(emotionDecayTime).append("\" decayperiod=\"").append(emotionDecayPeriod)
        .append("\" decayfunction=\"").append(decayFunction).append("\" baseline=\"").append(baseline).append("\"/>")
        .append("<Appraisal internalAffectAppraisal=\"").append(internalAffectAppraisal).append("\"><Basic>");
      appendAppraisalXml(xml, appraisal);
      xml.append("</Basic>");
      if (complexAppraisal != null) appendComplexAppraisalXml(xml, complexAppraisal, name);
      if (internalAffectAppraisal && complexAppraisal != null) {
        validateInternalCharacterCompoundSafety(complexAppraisal);
      }
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
          inheritGlobalPause(am.sInterface.getCharacterByName(name));
        } catch (RuntimeException e) {
          current.removeCharacterAffect(index);
          throw e;
        }
      }
      created(ex, "{\"created\":true,\"name\":\"" + escape(name)
        + "\",\"derived\":" + derived + ",\"internal_affect_appraisal\":" + internalAffectAppraisal
        + ",\"persistent\":false}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 400, "cannot create character: " + e.getMessage());
    }
  }

  private void handleAffect(HttpExchange ex) throws IOException {
    String requestPath = ex.getRequestURI().getPath();
    if (!("/affect".equals(requestPath) || requestPath.startsWith("/affect/"))) {
      fail(ex, 404, "endpoint not found: " + requestPath);
      return;
    }
    if (!"GET".equals(ex.getRequestMethod())) { fail(ex, 405, "method not allowed"); return; }
    int status = 200;
    String json;
    synchronized (am) {
      String path = ex.getRequestURI().getPath();
      String target = path.length() > "/affect/".length() ? path.substring("/affect/".length()) : null;

      if (target != null && target.startsWith("group/")) {
        String groupName = target.substring("group/".length());
        json = null;
        GroupManager[] groups = am.sInterface.getGroups();
        if (groups != null) for (GroupManager group : groups) {
          if (groupName.equals(group.getName())) {
            json = groupAffectJson(group);
            break;
          }
        }
        if (json == null) {
          status = 404;
          json = errorJson("group not found: " + groupName);
        }
      } else if (target == null || target.isEmpty()) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (CharacterManager c : am.sInterface.getCharacters()) {
          if (!first) sb.append(",");
          sb.append(characterAffectJson(c));
          first = false;
        }
        json = sb.append("]").toString();
      } else {
        json = null;
        for (CharacterManager c : am.sInterface.getCharacters()) {
          if (target.equals(c.getName())) {
            json = characterAffectJson(c);
            break;
          }
        }
        if (json == null) {
          status = 404;
          json = errorJson("character not found: " + target);
        }
      }
    }
    respond(ex, status, json);
  }

  private String characterAffectJson(CharacterManager c) {
    // Character inference locks CharacterManager and then EmotionEngine; use
    // the same order so decay/inference cannot change intensities between the
    // dominant field and the full emotion list in one response.
    synchronized (c) {
      EmotionEngine engine = c.getEmotionEngine();
      synchronized (engine) {
        return characterAffectJsonLocked(c);
      }
    }
  }

  private String characterAffectJsonLocked(CharacterManager c) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"name\":\"").append(escape(c.getName())).append("\",");
    sb.append("\"affect_computation_paused\":").append(c.isAffectComputationPaused()).append(",");
    Personality personality = c.getPersonality();
    sb.append("\"personality\":{")
      .append("\"openness\":").append(personality.getOpenness()).append(",")
      .append("\"conscientiousness\":").append(personality.getConscientiousness()).append(",")
      .append("\"extraversion\":").append(personality.getExtraversion()).append(",")
      .append("\"agreeableness\":").append(personality.getAgreeableness()).append(",")
      .append("\"neurotism\":").append(personality.getNeurotism()).append(",")
      .append("\"derived\":").append(c.isDerivedPersonality()).append(",")
      .append("\"emotion_influence\":").append(c.getAffectConsts().personalityEmotionInfluence)
      .append("},");
    EmotionVector currentEmotions = c.getCurrentEmotions();
    List<Emotion> emotions = currentEmotions.getEmotions();
    Emotion dominant = emotions.isEmpty() ? null : emotions.get(emotions.size() - 1);
    if (dominant != null && dominant.getIntensity() == dominant.getBaseline()) {
      dominant = currentEmotions.get(EmotionType.Undefined);
    }
    sb.append("\"dominant_emotion\":").append(emotionJson(dominant)).append(",");
    Mood moodTendency = trueMoodTendency(c);
    sb.append("\"mood\":").append(moodJson(c.getCurrentMood())).append(",")
      .append("\"mood_tendency\":").append(moodTendency == null ? "null" : moodJson(moodTendency)).append(",")
      .append("\"default_mood\":").append(moodJson(c.defaultMood())).append(",");
    sb.append("\"emotions\":[");
    boolean first = true;
    for (Emotion emotion : emotions) {
      if (!first) sb.append(",");
      sb.append(emotionJson(emotion));
      first = false;
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String emotionJson(Emotion emotion) {
    if (emotion == null) return "null";
    EmotionType type = emotion.getType();
    double intensity = emotion.getIntensity();
    double baseline = emotion.getBaseline();
    EmotionAppraisalVars appraisal = emotion.getAppraisalVariables();
    Object rawElicitor = emotion.getElicitor();
    long elicitedAt = emotion.getStart();
    boolean active = intensity > baseline;
    boolean hasElicitation = active || appraisal != null;
    Object elicitor = hasElicitation ? rawElicitor : null;
    Mood pad = EmotionType.Physical.equals(type)
      ? emotion.getPADValues() : EmotionsPADRelation.getEmotionPADMapping(emotion.getType());
    return new StringBuilder("{")
      .append("\"name\":\"").append(escape(type.toString())).append("\",")
      .append("\"intensity\":").append(intensity).append(",")
      .append("\"baseline\":").append(baseline).append(",")
      .append("\"active\":").append(active).append(",")
      .append("\"elicitor\":").append(elicitor == null ? "null" : "\"" + escape(String.valueOf(elicitor)) + "\"").append(",")
      .append("\"elicited_at\":").append(hasElicitation ? Long.toString(elicitedAt) : "null").append(",")
      .append("\"pad\":").append(pad == null ? "null" : padCoordinatesJson(pad)).append(",")
      .append("\"appraisal\":").append(emotionAppraisalJson(appraisal))
      .append("}").toString();
  }

  private static String emotionAppraisalJson(EmotionAppraisalVars appraisal) {
    if (appraisal == null) return "null";
    return new StringBuilder("{")
      .append("\"desirability\":").append(nullableNumber(appraisal.desirability)).append(",")
      .append("\"praiseworthiness\":").append(nullableNumber(appraisal.praiseworthiness)).append(",")
      .append("\"appealingness\":").append(nullableNumber(appraisal.appealingness)).append(",")
      .append("\"likelihood\":").append(nullableNumber(appraisal.likelihood)).append(",")
      .append("\"realization\":").append(appraisal.realization == null ? "null" : appraisal.realization.toString()).append(",")
      .append("\"liking\":").append(nullableNumber(appraisal.liking)).append(",")
      .append("\"agency\":").append(appraisal.agency == null ? "null"
        : "\"" + (appraisal.agency ? "self" : "other") + "\"")
      .append("}").toString();
  }

  private static String nullableNumber(Number value) {
    return value == null ? "null" : value.toString();
  }

  private static String padCoordinatesJson(Mood mood) {
    return new StringBuilder("{")
      .append("\"pleasure\":").append(mood.getPleasure()).append(",")
      .append("\"arousal\":").append(mood.getArousal()).append(",")
      .append("\"dominance\":").append(mood.getDominance()).append("}").toString();
  }

  private static Mood trueMoodTendency(CharacterManager character) {
    try {
      Field field = CharacterManager.class.getDeclaredField("fMoodEngine");
      field.setAccessible(true);
      MoodEngine engine = (MoodEngine) field.get(character);
      return engine == null ? character.defaultMood() : engine.getCurrentMoodTendency();
    } catch (ReflectiveOperationException | SecurityException e) {
      return character.defaultMood();
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

  private String groupAffectJson(GroupManager group) {
    StringBuilder sb = new StringBuilder("{");
    double integrity = group.getSocialIntegrity();
    Mood groupMood = group.getCurrentMood();
    String groupMoodJson = moodJson(groupMood);
    CharacterManager[] groupCharacters = group.getCharacters();
    String similarMood = group.getCharactersInSimilarMood();
    sb.append("\"name\":\"").append(escape(group.getName())).append("\",")
      .append("\"characters\":[").append(characterNamesJson(groupCharacters)).append("],")
      .append("\"affect_computation_paused\":").append(isGroupPaused(group.getName())).append(",")
      .append("\"overall_mood\":").append(groupMoodJson).append(",")
      .append("\"meta_mood\":").append(groupMoodJson).append(",")
      .append("\"social_integrity\":{")
      .append("\"numeric\":").append(integrity).append(",")
      .append("\"label\":\"").append(Convert.valueDescription(integrity)).append("\",")
      .append("\"lower_is_stronger\":true")
      .append("},")
      .append("\"mood_similarity_summary_raw\":")
      .append(similarMood == null ? "null" : "\"" + escape(similarMood) + "\"").append(",")
      .append("\"mood_similarities\":[").append(similarityPairsJson(similarMood, groupCharacters)).append("],")
      .append("\"mood_extremes\":[");
    boolean first = true;
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

  /** Decode only pairs that uniquely match real group members; never invent names by splitting. */
  private static String similarityPairsJson(String summary, CharacterManager[] characters) {
    if (summary == null || "none".equals(summary) || characters == null) return "";
    StringBuilder json = new StringBuilder();
    boolean firstOutput = true;
    for (String rawPair : summary.split(",\\s*")) {
      String firstName = null;
      String secondName = null;
      boolean ambiguous = false;
      for (CharacterManager first : characters) {
        for (CharacterManager second : characters) {
          if (first == second) continue;
          if (!rawPair.equals(first.getName() + " - " + second.getName())) continue;
          if (firstName != null) {
            ambiguous = true;
            break;
          }
          firstName = first.getName();
          secondName = second.getName();
        }
        if (ambiguous) break;
      }
      if (ambiguous || firstName == null) continue;
      if (!firstOutput) json.append(',');
      json.append("{\"first\":\"").append(escape(firstName)).append("\",\"second\":\"")
        .append(escape(secondName)).append("\"}");
      firstOutput = false;
    }
    return json.toString();
  }

  private static String characterNamesJson(CharacterManager[] characters) {
    List<String> names = new ArrayList<>();
    if (characters != null) for (CharacterManager character : characters) names.add(character.getName());
    return quotedStrings(names);
  }

  private void handleGroups(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/groups")) return;
    if ("GET".equals(ex.getRequestMethod())) {
      String json;
      synchronized (am) {
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
        json = sb.append("]}").toString();
      }
      ok(ex, json);
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
    if (body.containsKey("complex_appraisal")) {
      appendGroupComplexAppraisalXml(xml, body.get("complex_appraisal"), name, new HashSet<>(characters));
      validateInternalGroupCompoundSafety(body.get("complex_appraisal"));
    }
    xml.append("</Appraisal></GroupAffect></AffectDefinition>");

    AffectDefinitionDocument doc = AffectDefinitionDocument.Factory.parse(xml.toString());
    AffectDefinition.GroupAffect profile = doc.getAffectDefinition().getGroupAffectArray(0);
    // AffectDefinition requires at least one CharacterAffect at the document level.
    // Validate the generated GroupAffect itself because this adaptor adds it to the
    // already loaded (and complete) definition document below.
    if (!profile.validate()) throw new IllegalArgumentException("generated group does not validate against ALMA Affect.xsd");
    synchronized (am) {
      ensureNewGroupCanInheritGlobalPause(body);
      try {
        am.sInterface.getGroupByName(name);
        throw new ApiException(409, "group already exists: " + name);
      } catch (IllegalArgumentException ignored) {
        // Missing group is expected. This check is inside the creation lock.
      }
      AffectDefinition current = am.sInterface.getDocumentManager().getAffectDefinition();
      int index = current.sizeOfGroupAffectArray();
      AffectDefinition.GroupAffect stored = current.addNewGroupAffect();
      stored.set(profile);
      try {
        am.initGroup(stored);
        inheritGlobalPause(am.sInterface.getGroupByName(name));
      } catch (RuntimeException e) {
        current.removeGroupAffect(index);
        throw e;
      }
    }
  }

  private static void appendGroupComplexAppraisalXml(StringBuilder xml, Object value, String ownerName,
      Set<String> members) {
    if (!(value instanceof List)) throw new IllegalArgumentException("complex_appraisal must be a JSON array");
    List<?> entries = (List<?>) value;
    String[] kindOrder = { "indirect_act", "indirect_emotion", "indirect_mood" };
    Set<String> storageKeys = basicStorageKeys(ownerName);
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      requireExactKeys(entry, "complex_appraisal[" + i + "]", "kind", "signal", "performer", "appraisal");
      String kind = requiredString(entry, "kind");
      boolean supported = false;
      for (String expected : kindOrder) if (expected.equals(kind)) supported = true;
      if (!supported) throw new IllegalArgumentException("groups support only indirect_act, indirect_emotion and indirect_mood");
      String signal = requiredString(entry, "signal");
      String performer = requiredString(entry, "performer");
      if (!members.contains(performer)) {
        throw new IllegalArgumentException("complex_appraisal[" + i + "].performer must be a member of the group: " + performer);
      }
      rejectStorageKeyCollision(storageKeys, performer, signal, i);
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
      rejectImmediateAttractionAttributionCompound(rules, "complex_appraisal[" + i + "].appraisal");
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
    if (!requireExactPath(ex, "/appraisal", "/event")) return;
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
      rejectReservedInternalElicitor(elicitor);
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
      CharacterManager target;
      try {
        target = am.sInterface.getCharacterByName(character);
      } catch (IllegalArgumentException e) {
        fail(ex, 404, "character not found: " + character);
        return;
      }
      AffectInput ai = AppraisalTag.instance().makeAffectInput(character, tag, Double.toString(intensity), elicitor);
      synchronized (am) {
        target = am.sInterface.getCharacterByName(character);
        rejectBrokenLoveHateCompound(target, compoundCandidates(target.getAppraisalVariables(tag), intensity), elicitor);
        processSignalThroughAdaptor(ai);
      }
      ok(ex, "{\"accepted\":true,\"character\":\"" + escape(character)
        + "\",\"tag\":\"" + escape(tag) + "\",\"intensity\":" + intensity
        + ",\"elicitor\":\"" + escape(elicitor) + "\",\"signal_kind\":\""
        + appraisalSignalKind(tag) + "\"}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
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
    if (!requireExactPath(ex, "/pad")) return;
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
      synchronized (am) {
        CharacterManager manager = am.sInterface.getCharacterByName(character);
        if (manager.isAffectComputationPaused()) {
          throw new ApiException(409, "PAD input is not applied while character affect computation is paused: " + character);
        }
        processSignalThroughAdaptor(ai);
      }
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
    if (!requireExactPath(ex, "/eec")) return;
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
        likelihood, realization, liking, agency);
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
      synchronized (am) {
        CharacterManager target = am.sInterface.getCharacterByName(character);
        rejectBrokenLoveHateCompound(target,
          compoundCandidates(praiseworthiness, praiseworthiness != 0.0d, appealingness,
            appealingness != 0.0d, agency), elicitor);
        processSignalThroughAdaptor(ai);
      }
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
      double appealingness, double likelihood, double realization, double liking, String agency)
      throws ApiException {
    boolean de = desirability != 0.0d;
    boolean pr = praiseworthiness != 0.0d;
    boolean ap = appealingness != 0.0d;
    boolean li = likelihood != 0.0d;
    boolean re = realization != 0.0d;
    boolean lk = liking != 0.0d;
    if (re && !de && !pr && !ap && !li && !lk) return "realization";
    if (de && pr && !ap && !li && !re && !lk) return "event_action_compound";
    if (!de && pr && ap && !li && !re && !lk) {
      if ("other".equals(agency)
          && ((praiseworthiness > 0.0d && appealingness > 0.0d)
              || (praiseworthiness < 0.0d && appealingness < 0.0d))) {
        throw new ApiException(422, "the unmodified ALMA 3.0 core crashes while combining same-signed "
          + "other-agency praiseworthiness and appealingness into Love/Hate; send a supported non-compound appraisal instead");
      }
      return "action_object_pair";
    }
    if (de && !pr && !ap && !li && !re && !lk) return "desirability";
    if (de && !pr && !ap && li && !re && !lk) return "desirability_likelihood";
    if (de && !pr && !ap && !li && !re && lk) return "desirability_liking";
    if (!de && pr && !ap && !li && !re && !lk) return "praiseworthiness";
    if (!de && !pr && ap && !li && !re && !lk) return "appealingness";
    throw new ApiException(400, "invalid BasicEEC combination; ALMA supports only: realization; "
      + "desirability; desirability+likelihood; desirability+liking; praiseworthiness; "
      + "appealingness; desirability+praiseworthiness; or a safe praiseworthiness+appealingness pair "
      + "(all unused numeric fields must be 0)");
  }

  private void handleAct(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/act")) return;
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
      CharacterManager performerManager = am.sInterface.getCharacterByName(performer);

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
      synchronized (am) {
        performerManager = am.sInterface.getCharacterByName(performer);
        preflightActCompounds(performerManager, addressees, listeners, type, intensity, elicitor);
        processSignalThroughAdaptor(ai);
      }
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
    if (!requireExactPath(ex, emotion ? "/emotion-display" : "/mood-display")) return;
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
      CharacterManager performerManager = am.sInterface.getCharacterByName(performer);

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
      String selfRuleType = emotion ? "SelfEmotion" : "SelfMood";
      String externalRuleType = emotion ? "ExternalEmotion" : "ExternalMood";
      boolean selfRuleMatched = performerManager.getAppraisalVariables(type, selfRuleType).length > 0;
      boolean hasExplicitRecipients = !addressees.isEmpty() || !listeners.isEmpty();
      boolean groupRuleMatched = hasMatchingGroupDisplayRule(performerManager, type, externalRuleType);
      if (!selfRuleMatched && hasExplicitRecipients) {
        throw new ApiException(422, "the original ALMA core requires performer " + performer
          + " to have a matching " + selfRuleType + " rule before addressees/listeners can appraise this display");
      }
      if (!selfRuleMatched && !groupRuleMatched) {
        throw new ApiException(422, "no effective ALMA appraisal route: performer has no matching "
          + selfRuleType + " rule and no containing group has a matching indirect rule");
      }
      synchronized (am) {
        if (selfRuleMatched) {
          preflightDisplayCompounds(performerManager, addressees, listeners, type, intensity,
            elicitor, selfRuleType, externalRuleType);
        }
        preflightGroupCompoundRisks(performerManager, type, externalRuleType, intensity, elicitor);
        processSignalThroughAdaptor(ai);
      }
      ok(ex, "{\"accepted\":true,\"signal_kind\":\"" + (emotion ? "emotion_display" : "mood_display")
        + "\",\"performer\":\"" + escape(performer) + "\",\"addressees\":[" + quotedStrings(addressees)
        + "],\"listeners\":[" + quotedStrings(listeners) + "],\"type\":\"" + escape(type)
        + "\",\"intensity\":" + intensity + ",\"elicitor\":\"" + escape(elicitor)
        + "\",\"performer_self_rule_matched\":" + selfRuleMatched
        + ",\"group_rule_matched\":" + groupRuleMatched + "}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (IllegalArgumentException e) {
      fail(ex, 400, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "processSignal failed: " + e.getMessage());
    }
  }

  private boolean hasMatchingGroupDisplayRule(CharacterManager performer, String signal, String ruleType) {
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return false;
    for (GroupManager group : groups) {
      if (!group.hasCharacter(performer)) continue;
      AppraisalRules rules = group.getAppraisalRules();
      if (rules != null && rules.getAppraisalRulesByType(performer.getName(), ruleType)
          .getAppraisalVariables(performer.getName(), signal) != null) return true;
    }
    return false;
  }

  private void preflightActCompounds(CharacterManager performer, List<String> addressees, List<String> listeners,
      String signal, double intensity, String elicitor) throws ApiException {
    Map<CharacterManager, CompoundCandidates> candidates = new LinkedHashMap<>();
    mergeCompoundCandidates(candidates, performer, compoundCandidates(performer.getAppraisalVariables(signal), intensity));
    for (String name : addressees) {
      CharacterManager target = am.sInterface.getCharacterByName(name);
      mergeCompoundCandidates(candidates, target,
        compoundCandidates(externalAppraisalVariables(target, performer.getName(), signal, "DirectAct"), intensity));
    }
    for (String name : listeners) {
      CharacterManager target = am.sInterface.getCharacterByName(name);
      mergeCompoundCandidates(candidates, target,
        compoundCandidates(externalAppraisalVariables(target, performer.getName(), signal, "IndirectAct"), intensity));
    }
    rejectCharacterCompoundRisks(candidates, elicitor);
    preflightGroupCompoundRisks(performer, signal, "IndirectAct", intensity, elicitor);
  }

  private void preflightDisplayCompounds(CharacterManager performer, List<String> addressees, List<String> listeners,
      String signal, double intensity, String elicitor, String selfRuleType, String externalRuleType)
      throws ApiException {
    Map<CharacterManager, CompoundCandidates> candidates = new LinkedHashMap<>();
    mergeCompoundCandidates(candidates, performer,
      compoundCandidates(performer.getAppraisalVariables(signal, selfRuleType), intensity));
    for (String name : addressees) {
      CharacterManager target = am.sInterface.getCharacterByName(name);
      mergeCompoundCandidates(candidates, target,
        compoundCandidates(externalAppraisalVariables(target, performer.getName(), signal, externalRuleType), intensity));
    }
    for (String name : listeners) {
      CharacterManager target = am.sInterface.getCharacterByName(name);
      mergeCompoundCandidates(candidates, target,
        compoundCandidates(externalAppraisalVariables(target, performer.getName(), signal, externalRuleType), intensity));
    }
    rejectCharacterCompoundRisks(candidates, elicitor);
  }

  private void preflightGroupCompoundRisks(CharacterManager performer, String signal, String ruleType,
      double intensity, String elicitor) throws ApiException {
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    for (GroupManager group : groups) {
      if (!group.hasCharacter(performer)) continue;
      AppraisalVariables[] variables = groupAppraisalVariables(group, performer.getName(), signal, ruleType);
      if (variables.length == 0) continue;
      if (isGroupPaused(group.getName())) {
        throw new ApiException(409, "signal would appraise paused group " + group.getName()
          + "; resume the group before sending this member signal");
      }
      rejectBrokenLoveHateCompound(group.getName(), groupEmotionHistory(group),
        compoundCandidates(variables, intensity), elicitor);
    }
  }

  private static AppraisalVariables[] groupAppraisalVariables(GroupManager group, String performer,
      String signal, String ruleType) {
    AppraisalRules rules = group.getAppraisalRules();
    if (rules == null) return new AppraisalVariables[0];
    AppraisalVariables[] variables = rules.getAppraisalRulesByType(performer, ruleType)
      .getAppraisalVariables(performer, signal);
    return variables == null ? new AppraisalVariables[0] : variables;
  }

  private static EmotionHistory groupEmotionHistory(GroupManager group) throws ApiException {
    try {
      Field field = GroupManager.class.getDeclaredField("fEmotionHistory");
      field.setAccessible(true);
      EmotionHistory history = (EmotionHistory) field.get(group);
      if (history == null) throw new ReflectiveOperationException("group emotion history is null");
      return history;
    } catch (ReflectiveOperationException | SecurityException e) {
      throw new ApiException(500, "cannot inspect ALMA group emotion history safely: " + e.getMessage());
    }
  }

  private static AppraisalVariables[] externalAppraisalVariables(CharacterManager target, String performer,
      String signal, String ruleType) {
    AppraisalVariables[] variables = target.getAppraisalRules().getAppraisalRulesByType(performer, ruleType)
      .getAppraisalVariables(performer, signal);
    return variables == null ? new AppraisalVariables[0] : variables;
  }

  private static void mergeCompoundCandidates(Map<CharacterManager, CompoundCandidates> all,
      CharacterManager character, CompoundCandidates addition) {
    CompoundCandidates current = all.get(character);
    if (current == null) all.put(character, addition);
    else current.merge(addition);
  }

  private static void rejectCharacterCompoundRisks(Map<CharacterManager, CompoundCandidates> candidates,
      String elicitor) throws ApiException {
    for (Map.Entry<CharacterManager, CompoundCandidates> entry : candidates.entrySet()) {
      rejectBrokenLoveHateCompound(entry.getKey(), entry.getValue(), elicitor);
    }
  }

  private void handlePause(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/pause")) return;
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, String> target = controlTarget(ex);
      String characterName = target.get("character");
      String groupName = target.get("group");
      boolean changed;
      synchronized (am) {
        if (characterName != null) {
          CharacterManager character = getCharacterOr404(characterName);
          changed = !character.isAffectComputationPaused();
          if (changed) character.pauseAffectComputation();
        } else if (groupName != null) {
          GroupManager group = getGroupOr404(groupName);
          ensureGroupPauseSafe(group);
          changed = setGroupPaused(group, true);
        } else {
          ensureAllGroupsPauseSafe();
          changed = setPausedForAll(true);
        }
      }
      ok(ex, "{\"paused\":true,\"changed\":" + changed + pauseScopeJson(characterName, groupName) + "}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "cannot pause affect computation: " + e.getMessage());
    }
  }

  private void handleResume(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/resume")) return;
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, String> target = controlTarget(ex);
      String characterName = target.get("character");
      String groupName = target.get("group");
      boolean changed;
      synchronized (am) {
        if (characterName != null) {
          CharacterManager character = getCharacterOr404(characterName);
          changed = character.isAffectComputationPaused();
          if (changed) character.resumeAffectComputation();
        } else if (groupName != null) {
          changed = setGroupPaused(getGroupOr404(groupName), false);
        } else {
          changed = setPausedForAll(false);
        }
      }
      ok(ex, "{\"paused\":false,\"changed\":" + changed + pauseScopeJson(characterName, groupName) + "}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "cannot resume affect computation: " + e.getMessage());
    }
  }

  private void handleStep(HttpExchange ex) throws IOException {
    if (!requireExactPath(ex, "/step")) return;
    if (!"POST".equals(ex.getRequestMethod())) { fail(ex, 405, "POST only"); return; }
    try {
      Map<String, String> target = controlTarget(ex);
      String characterName = target.get("character");
      String groupName = target.get("group");
      boolean stepped;
      synchronized (am) {
        if (characterName != null) {
          CharacterManager character = getCharacterOr404(characterName);
          if (!character.isAffectComputationPaused()) {
            throw new ApiException(409, "character must be paused before a step: " + characterName);
          }
          character.pauseAffectComputation();
          stepped = character.stepwiseAffectComputation();
        } else if (groupName != null) {
          GroupManager group = getGroupOr404(groupName);
          if (!isGroupPaused(groupName)) throw new ApiException(409, "group must be paused before a step: " + groupName);
          group.pauseAffectComputation();
          stepped = group.stepwiseAffectComputation();
        } else {
          stepped = stepAllPaused();
        }
      }
      ok(ex, "{\"stepped\":" + stepped + pauseScopeJson(characterName, groupName) + "}");
    } catch (ApiException e) {
      fail(ex, e.status, e.getMessage());
    } catch (Exception e) {
      fail(ex, 500, "cannot step affect computation: " + e.getMessage());
    }
  }

  private static String pauseScopeJson(String characterName, String groupName) {
    if (characterName != null) return ",\"scope\":\"character\",\"name\":\"" + escape(characterName) + "\"";
    if (groupName != null) return ",\"scope\":\"group\",\"name\":\"" + escape(groupName) + "\"";
    return ",\"scope\":\"all\"";
  }

  private CharacterManager getCharacterOr404(String name) throws ApiException {
    try {
      return am.sInterface.getCharacterByName(name);
    } catch (IllegalArgumentException e) {
      throw new ApiException(404, "character not found: " + name);
    }
  }

  private GroupManager getGroupOr404(String name) throws ApiException {
    try {
      return am.sInterface.getGroupByName(name);
    } catch (IllegalArgumentException e) {
      throw new ApiException(404, "group not found: " + name);
    }
  }

  private void ensureAllGroupsPauseSafe() throws ApiException {
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    for (GroupManager group : groups) ensureGroupPauseSafe(group);
  }

  private void ensureGroupPauseSafe(GroupManager group) throws ApiException {
    AppraisalRules rules = group.getAppraisalRules();
    if (rules == null) return;
    CharacterManager[] members = group.getCharacters();
    if (members == null) return;
    for (CharacterManager member : members) {
      if (!internalAffectAppraisalEnabled(member.getName())) continue;
      if (hasRuleType(rules, member.getName(), "ExternalEmotion")
          || hasRuleType(rules, member.getName(), "ExternalMood")) {
        throw new ApiException(409, "cannot strictly pause group " + group.getName()
          + " because internal_affect_appraisal for member " + member.getName()
          + " can bypass the REST lifecycle and restart the original group timers");
      }
    }
  }

  private static boolean hasRuleType(AppraisalRules rules, String entity, String ruleType) {
    return rules.getAppraisalRulesByType(entity, ruleType).getKeys(entity).length > 0;
  }

  private boolean internalAffectAppraisalEnabled(String characterName) {
    AffectDefinition definition = am.sInterface.getDocumentManager().getAffectDefinition();
    if (definition == null) return false;
    for (AffectDefinition.CharacterAffect profile : definition.getCharacterAffectList()) {
      if (!characterName.equals(profile.getName()) || profile.getAppraisal() == null) continue;
      return profile.getAppraisal().isSetInternalAffectAppraisal()
        && profile.getAppraisal().getInternalAffectAppraisal();
    }
    return false;
  }

  private synchronized boolean isAllPaused() {
    return allPaused;
  }

  private void ensureNewGroupCanInheritGlobalPause(Map<String, Object> body) throws ApiException {
    if (!isAllPaused() || !body.containsKey("complex_appraisal")) return;
    Object value = body.get("complex_appraisal");
    if (!(value instanceof List)) return; // Normal validation reports this later/earlier.
    for (Object item : (List<?>) value) {
      Map<String, Object> entry;
      try {
        entry = asObject(item, "complex_appraisal entry");
      } catch (IllegalArgumentException e) {
        return;
      }
      Object kind = entry.get("kind");
      Object performer = entry.get("performer");
      if (!(performer instanceof String)) continue;
      if (("indirect_emotion".equals(kind) || "indirect_mood".equals(kind))
          && internalAffectAppraisalEnabled(((String) performer).trim())) {
        throw new ApiException(409, "cannot create this group while global pause is active: internal_affect_appraisal "
          + "for member " + ((String) performer).trim() + " can restart the original group timers");
      }
    }
  }

  private synchronized boolean setPausedForAll(boolean paused) {
    boolean changedAny = allPaused != paused;
    allPaused = paused;
    for (CharacterManager character : am.sInterface.getCharacters()) {
      if (paused && !character.isAffectComputationPaused()) {
        changedAny = character.pauseAffectComputation() || changedAny;
      } else if (!paused && character.isAffectComputationPaused()) {
        changedAny = character.resumeAffectComputation() || changedAny;
      }
    }
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups != null) for (GroupManager group : groups) {
      changedAny = setGroupPaused(group, paused) || changedAny;
    }
    return changedAny;
  }

  private synchronized boolean setGroupPaused(GroupManager group, boolean paused) {
    String name = group.getName();
    if (paused) {
      boolean changed = pausedGroups.add(name);
      // Always call the core pause operation so a timer reopened outside the
      // adaptor cannot survive a repeated pause request.
      group.pauseAffectComputation();
      return changed;
    }
    if (!pausedGroups.contains(name)) return false;
    // Original GroupManager.resumeAffectComputation() does not clear its
    // paused flag. pausedGroups is the adaptor's authoritative state.
    try {
      group.pauseAffectComputation();
      if (!group.resumeAffectComputation()) {
        throw new IllegalStateException("ALMA group refused to resume: " + name);
      }
      // GroupManager.resumeAffectComputation() also leaves its internal decay
      // enabled flag false. Re-enabling replaces the just-created timer and
      // restores the flag so a later pause can cancel decay correctly.
      group.enableEmotionDecay();
      pausedGroups.remove(name);
      return true;
    } catch (RuntimeException e) {
      group.pauseAffectComputation();
      throw e;
    }
  }

  private synchronized boolean stepAllPaused() throws ApiException {
    for (CharacterManager character : am.sInterface.getCharacters()) {
      if (!character.isAffectComputationPaused()) {
        throw new ApiException(409, "all characters and groups must be paused before a global step");
      }
    }
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups != null) for (GroupManager group : groups) {
      if (!pausedGroups.contains(group.getName())) {
        throw new ApiException(409, "all characters and groups must be paused before a global step");
      }
    }
    boolean stepped = true;
    for (CharacterManager character : am.sInterface.getCharacters()) {
      character.pauseAffectComputation();
      stepped = character.stepwiseAffectComputation() && stepped;
    }
    if (groups != null) for (GroupManager group : groups) {
      group.pauseAffectComputation();
      stepped = group.stepwiseAffectComputation() && stepped;
    }
    return stepped;
  }

  private synchronized void inheritGlobalPause(CharacterManager character) {
    if (allPaused) character.pauseAffectComputation();
  }

  private synchronized void inheritGlobalPause(GroupManager group) {
    if (allPaused) setGroupPaused(group, true);
  }

  private synchronized boolean isGroupPaused(String name) {
    return pausedGroups.contains(name);
  }

  /**
   * Keeps all adapter-originated signals serialized with entity lifecycle
   * changes. On a legacy core exception, pending EECs are cleared so one bad
   * request cannot poison later inference. Paused groups are re-paused because
   * GroupManager.setPersonality() can restart their timers while processing an
   * indirect signal.
   */
  private void processSignalThroughAdaptor(AffectInput input) {
    synchronized (am) {
      try {
        am.sInterface.processSignal(input);
      } catch (RuntimeException e) {
        clearPendingEecsAfterFailure();
        throw e;
      } finally {
        reassertPausedGroups();
      }
    }
  }

  private void clearPendingEecsAfterFailure() {
    for (CharacterManager character : am.sInterface.getCharacters()) {
      character.getEmotionEngine().clearEEC();
    }
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    try {
      Field field = GroupManager.class.getDeclaredField("fEmotionEngine");
      field.setAccessible(true);
      for (GroupManager group : groups) {
        EmotionEngine engine = (EmotionEngine) field.get(group);
        if (engine != null) engine.clearEEC();
      }
    } catch (ReflectiveOperationException | SecurityException reflectionFailure) {
      System.err.println("[alma-rest] could not clear a group EEC queue after signal failure: "
        + reflectionFailure.getMessage());
    }
  }

  private synchronized void reassertPausedGroups() {
    if (pausedGroups.isEmpty()) return;
    GroupManager[] groups = am.sInterface.getGroups();
    if (groups == null) return;
    for (GroupManager group : groups) {
      if (pausedGroups.contains(group.getName())) group.pauseAffectComputation();
    }
  }

  private static CompoundCandidates compoundCandidates(AppraisalVariables[] variables, double intensity)
      throws ApiException {
    CompoundCandidates result = new CompoundCandidates();
    if (variables == null) return result;
    int eventEecs = 0;
    int actionEecs = 0;
    int objectEecs = 0;
    for (AppraisalVariables variable : variables) {
      if (variable.getAppealingness() != null) objectEecs++;
      else if (variable.getPraiseworthiness() != null) actionEecs++;
      else eventEecs++;
      if (variable.getPraiseworthiness() != null && variable.getAgency() != null
          && "other".equals(variable.getAgency().toString())) {
        double value = variable.getPraiseworthiness().degree() * intensity;
        if (value < 0.0d) result.reproach = true;
        else result.admiration = true;
      }
      if (variable.getAppealingness() != null) {
        double value = variable.getAppealingness().degree() * intensity;
        if (value < 0.0d) result.disliking = true;
        else result.liking = true;
      }
    }
    if (eventEecs > 1 || actionEecs > 1 || objectEecs > 1) {
      throw new ApiException(422, "matched ALMA appraisal rule contains more than one Event, Action or Object EEC; "
        + "the original core has only one pending slot for each category");
    }
    return result;
  }

  private static CompoundCandidates compoundCandidates(double praiseworthiness, boolean hasPraiseworthiness,
      double appealingness, boolean hasAppealingness, String agency) {
    CompoundCandidates result = new CompoundCandidates();
    if (hasPraiseworthiness && "other".equals(agency)) {
      if (praiseworthiness < 0.0d) result.reproach = true;
      else result.admiration = true;
    }
    if (hasAppealingness) {
      if (appealingness < 0.0d) result.disliking = true;
      else result.liking = true;
    }
    return result;
  }

  private static void rejectBrokenLoveHateCompound(CharacterManager character, CompoundCandidates incoming,
      String elicitor) throws ApiException {
    rejectBrokenLoveHateCompound(character.getName(), character.getEmotionHistory(), incoming, elicitor);
  }

  private static void rejectBrokenLoveHateCompound(String entityName, EmotionHistory history,
      CompoundCandidates incoming, String elicitor) throws ApiException {
    boolean love = (incoming.admiration && incoming.liking)
      || (incoming.admiration && history.getEmotionByElicitor(EmotionType.Liking, elicitor) != null)
      || (incoming.liking && history.getEmotionByElicitor(EmotionType.Admiration, elicitor) != null);
    boolean hate = (incoming.reproach && incoming.disliking)
      || (incoming.reproach && history.getEmotionByElicitor(EmotionType.Disliking, elicitor) != null)
      || (incoming.disliking && history.getEmotionByElicitor(EmotionType.Reproach, elicitor) != null);
    if (love || hate) {
      throw new ApiException(422, "the unmodified ALMA 3.0 Love/Hate compound path is unsafe for affect entity "
        + entityName + " and elicitor " + elicitor
        + "; this request was rejected before the core could corrupt its pending EEC state");
    }
  }

  private static final class CompoundCandidates {
    boolean admiration;
    boolean reproach;
    boolean liking;
    boolean disliking;

    void merge(CompoundCandidates other) {
      admiration = admiration || other.admiration;
      reproach = reproach || other.reproach;
      liking = liking || other.liking;
      disliking = disliking || other.disliking;
    }
  }

  private static boolean requireExactPath(HttpExchange ex, String... allowed) throws IOException {
    String path = ex.getRequestURI().getPath();
    for (String candidate : allowed) if (candidate.equals(path)) return true;
    fail(ex, 404, "endpoint not found: " + path);
    return false;
  }

  private static void ok(HttpExchange ex, String json) throws IOException {
    respond(ex, 200, json);
  }

  private static void created(HttpExchange ex, String json) throws IOException {
    respond(ex, 201, json);
  }

  private static String errorJson(String message) {
    return "{\"error\":\"" + escape(message) + "\"}";
  }

  private static void respond(HttpExchange ex, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(status, body.length);
    ex.getResponseBody().write(body);
    ex.getResponseBody().close();
  }

  private static void fail(HttpExchange ex, int code, String msg) throws IOException {
    String json = errorJson(msg);
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    ex.sendResponseHeaders(code, body.length);
    ex.getResponseBody().write(body);
    ex.getResponseBody().close();
  }

  private static String readBody(HttpExchange ex) throws IOException, ApiException {
    String contentLength = ex.getRequestHeaders().getFirst("Content-Length");
    if (contentLength != null) {
      try {
        if (Long.parseLong(contentLength) > MAX_REQUEST_BODY_BYTES) {
          throw new ApiException(413, "request body exceeds 1048576 bytes");
        }
      } catch (NumberFormatException e) {
        throw new ApiException(400, "invalid Content-Length");
      }
    }
    try (InputStream is = ex.getRequestBody()) {
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int total = 0;
      for (int n; (n = is.read(buf)) != -1;) {
        if (n == 0) continue;
        total += n;
        if (total > MAX_REQUEST_BODY_BYTES) {
          throw new ApiException(413, "request body exceeds 1048576 bytes");
        }
        baos.write(buf, 0, n);
      }
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
    rejectReservedInternalElicitor(elicitor);
    return elicitor;
  }

  private static void rejectReservedInternalElicitor(String elicitor) throws ApiException {
    if (INTERNAL_EMOTION_ELICITOR.equals(elicitor) || INTERNAL_MOOD_ELICITOR.equals(elicitor)) {
      throw new ApiException(422, "elicitor is reserved by the original ALMA internal appraisal timer: " + elicitor);
    }
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

  private static void appendComplexAppraisalXml(StringBuilder xml, Object value, String ownerName) {
    if (!(value instanceof List)) throw new IllegalArgumentException("complex_appraisal must be a JSON array");
    List<?> entries = (List<?>) value;
    String[] kindOrder = { "self_act", "direct_act", "indirect_act", "self_emotion",
      "indirect_emotion", "self_mood", "indirect_mood" };
    Set<String> storageKeys = basicStorageKeys(ownerName);
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      boolean supported = false;
      for (String expected : kindOrder) if (expected.equals(kind)) supported = true;
      if (!supported) throw new IllegalArgumentException("unsupported complex appraisal kind: " + kind);
      boolean needsPerformer = kind.startsWith("direct_") || kind.startsWith("indirect_");
      if (needsPerformer) {
        requireExactKeys(entry, "complex_appraisal[" + i + "]", "kind", "signal", "performer", "appraisal");
      } else {
        requireExactKeys(entry, "complex_appraisal[" + i + "]", "kind", "signal", "appraisal");
      }
      String signal = requiredString(entry, "signal");
      String storageEntity = needsPerformer ? requiredString(entry, "performer") : ownerName;
      rejectStorageKeyCollision(storageKeys, storageEntity, signal, i);
    }
    for (String expectedKind : kindOrder) for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = asObject(entries.get(i), "complex_appraisal[" + i + "]");
      String kind = requiredString(entry, "kind");
      if (!expectedKind.equals(kind)) continue;
      String signal = requiredString(entry, "signal");
      Map<String, Object> rules = requiredObject(entry, "appraisal");
      if (rules.isEmpty()) throw new IllegalArgumentException("complex_appraisal[" + i + "].appraisal cannot be empty");
      validateAppraisalSubset(rules);
      rejectImmediateAttractionAttributionCompound(rules, "complex_appraisal[" + i + "].appraisal");

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

  private static void validateInternalCharacterCompoundSafety(Object value) {
    List<?> entries = (List<?>) value; // appendComplexAppraisalXml validated the shape first.
    CompoundCandidates emotionRules = new CompoundCandidates();
    CompoundCandidates moodRules = new CompoundCandidates();
    for (Object item : entries) {
      Map<String, Object> entry = asObject(item, "complex_appraisal entry");
      String kind = requiredString(entry, "kind");
      if (!("self_emotion".equals(kind) || "self_mood".equals(kind))) continue;
      CompoundCandidates addition = compoundCandidatesFromRuleTags(requiredObject(entry, "appraisal"));
      if ("self_emotion".equals(kind)) emotionRules.merge(addition);
      else moodRules.merge(addition);
    }
    rejectAutonomousCompound(emotionRules, "internal emotion appraisal");
    rejectAutonomousCompound(moodRules, "internal mood appraisal");
  }

  private void validateInternalGroupCompoundSafety(Object value) {
    List<?> entries = (List<?>) value; // appendGroupComplexAppraisalXml validated the shape first.
    Map<String, CompoundCandidates> families = new LinkedHashMap<>();
    for (Object item : entries) {
      Map<String, Object> entry = asObject(item, "complex_appraisal entry");
      String kind = requiredString(entry, "kind");
      if (!("indirect_emotion".equals(kind) || "indirect_mood".equals(kind))) continue;
      String performer = requiredString(entry, "performer");
      if (!internalAffectAppraisalEnabled(performer)) continue;
      // All internal characters use the same fixed elicitor and the group has
      // one shared emotion history, so combine across performers by channel.
      String key = kind;
      CompoundCandidates family = families.get(key);
      if (family == null) {
        family = new CompoundCandidates();
        families.put(key, family);
      }
      family.merge(compoundCandidatesFromRuleTags(requiredObject(entry, "appraisal")));
    }
    for (CompoundCandidates family : families.values()) {
      rejectAutonomousCompound(family, "group listener for internal affect appraisal");
    }
  }

  private static CompoundCandidates compoundCandidatesFromRuleTags(Map<String, Object> rules) {
    CompoundCandidates result = new CompoundCandidates();
    if (rules.containsKey("GoodActOther")) {
      addAttributionCandidate(result, jsonRuleDegree(rules, "GoodActOther", "praiseworthiness"));
    }
    if (rules.containsKey("BadActOther")) {
      addAttributionCandidate(result, jsonRuleDegree(rules, "BadActOther", "praiseworthiness"));
    }
    if (rules.containsKey("NiceThing")) {
      addAttractionCandidate(result, jsonRuleDegree(rules, "NiceThing", "appealingness"));
    }
    if (rules.containsKey("NastyThing")) {
      addAttractionCandidate(result, jsonRuleDegree(rules, "NastyThing", "appealingness"));
    }
    return result;
  }

  private static double jsonRuleDegree(Map<String, Object> rules, String tag, String attribute) {
    Object value = asObject(rules.get(tag), "appraisal." + tag).get(attribute);
    if (!(value instanceof Number)) {
      throw new IllegalArgumentException("appraisal." + tag + "." + attribute + " must be a number");
    }
    return ((Number) value).doubleValue();
  }

  private static void addAttributionCandidate(CompoundCandidates candidates, double degree) {
    if (degree < 0.0d) candidates.reproach = true;
    else candidates.admiration = true;
  }

  private static void addAttractionCandidate(CompoundCandidates candidates, double degree) {
    if (degree < 0.0d) candidates.disliking = true;
    else candidates.liking = true;
  }

  private static Set<String> basicStorageKeys(String ownerName) {
    Set<String> keys = new HashSet<>();
    for (String tag : basicTagNames()) keys.add(storageKey(ownerName, tag));
    return keys;
  }

  private static void rejectStorageKeyCollision(Set<String> storageKeys, String entity, String signal, int index) {
    if (!storageKeys.add(storageKey(entity, signal))) {
      throw new IllegalArgumentException("complex_appraisal[" + index + "] conflicts with another ALMA rule at (performer/entity='"
        + entity + "', signal='" + signal + "'); the original core stores only one rule per pair");
    }
  }

  private static String storageKey(String entity, String signal) {
    return entity.length() + ":" + entity + signal;
  }

  private static void rejectImmediateAttractionAttributionCompound(Map<String, Object> rules, String field) {
    CompoundCandidates candidates = compoundCandidatesFromRuleTags(rules);
    if ((candidates.admiration && candidates.liking)
        || (candidates.reproach && candidates.disliking)) {
      throw new IllegalArgumentException(field + " cannot combine same-signed other-agency action and object "
        + "appraisals; the unmodified ALMA 3.0 Love/Hate compound path crashes for these values");
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
    int eventRules = 0;
    int actionRules = 0;
    int objectRules = 0;
    for (String tag : appraisal.keySet()) {
      if (!isExactAppraisalTag(tag)) throw new IllegalArgumentException("unsupported complex appraisal tag: " + tag);
      String signalKind = appraisalSignalKind(tag);
      if ("event".equals(signalKind)) eventRules++;
      else if ("action".equals(signalKind)) actionRules++;
      else objectRules++;
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
    if (eventRules > 1 || actionRules > 1 || objectRules > 1) {
      throw new IllegalArgumentException("a complex appraisal signal may contain at most one Event tag, "
        + "one Action tag and one Object tag; the original core has one pending EEC slot per category");
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

  private static void requireAllowedKeys(Map<String, Object> object, String field,
      String[] required, String... optional) {
    Set<String> allowed = new HashSet<>();
    for (String key : required) allowed.add(key);
    for (String key : optional) allowed.add(key);
    for (String key : object.keySet()) {
      if (!allowed.contains(key)) throw new IllegalArgumentException("unknown field in " + field + ": " + key);
    }
    for (String key : required) {
      if (!object.containsKey(key)) throw new IllegalArgumentException("missing required field: " + key);
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

  private static boolean optionalBoolean(Map<String, Object> object, String key, boolean defaultValue) {
    if (!object.containsKey(key)) return defaultValue;
    return requiredBoolean(object, key);
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

  private static Map<String, String> controlTarget(HttpExchange ex) throws ApiException {
    Map<String, String> result = new LinkedHashMap<>();
    String query = ex.getRequestURI().getRawQuery();
    if (query == null || query.isEmpty()) return result;
    for (String item : query.split("&", -1)) {
      int equals = item.indexOf('=');
      if (equals <= 0) throw new ApiException(400, "query must be character=name or group=name");
      String key;
      String value;
      try {
        key = decodeQuery(item.substring(0, equals));
        value = decodeQuery(item.substring(equals + 1)).trim();
      } catch (IllegalArgumentException e) {
        throw new ApiException(400, "invalid query encoding");
      }
      if (!("character".equals(key) || "group".equals(key))) {
        throw new ApiException(400, "unknown query parameter: " + key);
      }
      if (value.isEmpty()) throw new ApiException(400, key + " query parameter cannot be empty");
      if (result.containsKey(key)) throw new ApiException(400, "duplicate query parameter: " + key);
      result.put(key, value);
    }
    if (result.size() > 1) throw new ApiException(400, "use either character or group query parameter, not both");
    return result;
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
    System.out.println("  POST /characters {name, personality, mood, emotion, appraisal, complex_appraisal?, internal_affect_appraisal?}");
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
    System.out.println("  POST /pause?[character|group]={name}");
    System.out.println("  POST /resume?[character|group]={name}");
    System.out.println("  POST /step?[character|group]={name}");
  }
}
