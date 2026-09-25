package com.aitranslate.client.scheduler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Lightweight, offline language classification used before an API request. */
final class LanguageHeuristics {
	private static final Pattern WORD = Pattern.compile("\\p{L}+");
	private static final Map<String, Set<String>> COMMON = Map.ofEntries(
			Map.entry("en", Set.of("the", "and", "is", "are", "this", "that", "you", "your", "with", "for",
					"from", "not", "hello", "good", "morning", "night", "chapter", "beginning", "world", "player")),
			Map.entry("de", Set.of("der", "die", "das", "und", "ist", "sind", "ein", "eine", "dies", "diese",
					"nicht", "mit", "für", "guten", "morgen", "reisender", "deutsch", "welt", "spieler")),
			Map.entry("fr", Set.of("le", "la", "les", "un", "une", "des", "et", "est", "sont", "ce", "cette",
					"pas", "avec", "pour", "bonjour", "voyageur", "monde", "joueur")),
			Map.entry("es", Set.of("el", "la", "los", "las", "un", "una", "y", "es", "son", "este", "esta",
					"no", "con", "para", "hola", "mundo", "jugador")),
			Map.entry("pt", Set.of("o", "a", "os", "as", "um", "uma", "e", "é", "são", "este", "esta", "não",
					"com", "para", "olá", "mundo", "jogador")),
			Map.entry("it", Set.of("il", "lo", "la", "gli", "un", "una", "e", "è", "sono", "questo", "questa",
					"non", "con", "per", "ciao", "mondo", "giocatore")),
			Map.entry("nl", Set.of("de", "het", "een", "en", "is", "zijn", "dit", "dat", "niet", "met", "voor",
					"goed", "morgen", "wereld", "speler")),
			Map.entry("pl", Set.of("ten", "ta", "to", "i", "jest", "są", "nie", "z", "dla", "dzień", "dobry",
					"świat", "gracz")),
			Map.entry("tr", Set.of("bu", "bir", "ve", "ile", "için", "değil", "merhaba", "günaydın", "dünya", "oyuncu")));
	private static final Map<String, Set<String>> DISTINCTIVE = Map.ofEntries(
			Map.entry("en", Set.of("water", "death", "sheep", "red", "yellow", "winner", "loser", "exit", "entrance",
					"notice", "warning", "city", "adventure")),
			Map.entry("de", Set.of("wasser", "tod", "schaf", "gelb", "mikrowelle", "gewinner", "verlierer",
					"ausgang", "eingang", "achtung", "kapitel", "stadt", "abenteuer")),
			Map.entry("fr", Set.of("eau", "mouton", "rouge", "jaune", "vainqueur", "perdant", "sortie", "entrée",
					"merci", "attention", "chapitre", "ville", "aventure")),
			Map.entry("es", Set.of("agua", "muerte", "oveja", "rojo", "amarillo", "ganador", "perdedor", "salida",
					"entrada", "gracias", "aviso", "ciudad", "aventura")),
			Map.entry("pt", Set.of("água", "morte", "ovelha", "vermelho", "amarelo", "vencedor", "perdedor", "saída",
					"entrada", "obrigado", "cidade")),
			Map.entry("it", Set.of("acqua", "morte", "pecora", "rosso", "giallo", "vincitore", "perdente", "uscita",
					"ingresso", "grazie", "città")));

	private LanguageHeuristics() {
	}

	static boolean matchesConfiguredSource(String text, String configured) {
		String wanted = base(configured);
		if (wanted.equals("auto") || wanted.isEmpty()) return true;
		Optional<String> detected = detect(text);
		return detected.isEmpty() || compatible(wanted, detected.get());
	}

	static Optional<String> detect(String text) {
		if (text == null || text.isBlank()) return Optional.empty();
		if (contains(text, 0x3040, 0x30ff)) return Optional.of("ja");
		if (contains(text, 0xac00, 0xd7af)) return Optional.of("ko");
		if (contains(text, 0x3400, 0x9fff)) return Optional.of("zh");
		if (contains(text, 0x0370, 0x03ff)) return Optional.of("el");
		if (contains(text, 0x0590, 0x05ff)) return Optional.of("he");
		if (contains(text, 0x0600, 0x06ff)) return Optional.of("ar");
		if (contains(text, 0x0900, 0x097f)) return Optional.of("hi");
		if (contains(text, 0x0e00, 0x0e7f)) return Optional.of("th");
		if (contains(text, 0x0400, 0x052f)) {
			String lower = text.toLowerCase(Locale.ROOT);
			return Optional.of(lower.matches(".*[іїєґ].*") ? "uk" : "ru");
		}
		return detectLatin(text);
	}

	private static Optional<String> detectLatin(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		Map<String, Integer> scores = new HashMap<>();
		var matcher = WORD.matcher(lower);
		while (matcher.find()) {
			String token = matcher.group();
			for (var language : COMMON.entrySet()) {
				if (language.getValue().contains(token)) scores.merge(language.getKey(), 1, Integer::sum);
			}
			for (var language : DISTINCTIVE.entrySet()) {
				if (language.getValue().contains(token)) scores.merge(language.getKey(), 3, Integer::sum);
			}
		}
		// Characters which are decisive even in a short label.
		boost(scores, "de", lower, "ß");
		boost(scores, "tr", lower, "ğışİı");
		boost(scores, "pl", lower, "ąćęłńśźż");
		boost(scores, "es", lower, "¿¡ñ");
		boost(scores, "pt", lower, "ãõ");
		String best = null;
		int bestScore = 0;
		boolean tied = false;
		for (var score : scores.entrySet()) {
			if (score.getValue() > bestScore) {
				best = score.getKey(); bestScore = score.getValue(); tied = false;
			} else if (score.getValue() == bestScore && bestScore > 0) {
				tied = true;
			}
		}
		return bestScore >= 2 && !tied ? Optional.of(best) : Optional.empty();
	}

	private static void boost(Map<String, Integer> scores, String language, String text, String chars) {
		for (int i = 0; i < chars.length(); i++) {
			if (text.indexOf(chars.charAt(i)) >= 0) {
				scores.merge(language, 3, Integer::sum);
				return;
			}
		}
	}

	private static boolean compatible(String wanted, String detected) {
		if (wanted.equals(detected)) return true;
		// Closely related configured variants share a script when local classification
		// cannot safely distinguish them.
		return Set.of("zh", "yue").contains(wanted) && Set.of("zh", "yue").contains(detected);
	}

	private static String base(String language) {
		if (language == null) return "auto";
		String normalized = language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		int separator = normalized.indexOf('_');
		return separator < 0 ? normalized : normalized.substring(0, separator);
	}

	private static boolean contains(String value, int start, int end) {
		return value.codePoints().anyMatch(code -> code >= start && code <= end);
	}
}
