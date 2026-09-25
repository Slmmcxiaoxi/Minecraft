package com.aitranslate.client.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import com.google.gson.JsonObject;

/** A single cached translation together with its metadata. */
public final class CacheEntry {
	public String hash;
	public String original;
	public String translated;
	public String sourceLang;
	public String targetLang;
	public String textType;
	public long updatedAt;

	public CacheEntry() {
	}

	public CacheEntry(String original, String translated, String sourceLang, String targetLang, String textType) {
		this.hash = hash(original, sourceLang, targetLang);
		this.original = original;
		this.translated = translated;
		this.sourceLang = sourceLang;
		this.targetLang = targetLang;
		this.textType = textType;
		this.updatedAt = System.currentTimeMillis();
	}

	/**
	 * The cache key of a text: the first 16 bytes of
	 * {@code SHA-256(sourceLang + NUL + targetLang + NUL + original)} in hex.
	 * <p>
	 * The language pair is part of the key on purpose - the same English line has a
	 * different translation per target language, and a map played in Chinese must not
	 * pick up the entries of an English run. A cryptographic digest means two
	 * different texts can never share a slot, which is what lets the scheduler use
	 * the hash as its queue identity as well.
	 */
	public static String hash(String original, String sourceLang, String targetLang) {
		String raw = (sourceLang == null ? "auto" : sourceLang) + '\u0000'
				+ (targetLang == null ? "" : targetLang) + '\u0000'
				+ (original == null ? "" : original);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(32);
			for (int i = 0; i < 16; i++) {
				sb.append(String.format(Locale.ROOT, "%02x", bytes[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(raw.hashCode());
		}
	}

	/** The map key this entry is stored under (recomputed when the file did not carry one). */
	public String key() {
		return hash == null ? hash(original, sourceLang, targetLang) : hash;
	}

	/** This entry as it is written to the per-world cache file. */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("hash", key());
		json.addProperty("original", original);
		json.addProperty("translated", translated);
		json.addProperty("sourceLang", sourceLang);
		json.addProperty("targetLang", targetLang);
		json.addProperty("textType", textType);
		json.addProperty("updatedAt", updatedAt);
		return json;
	}

	/**
	 * Reads an entry from a cache file.
	 * <p>
	 * Tolerant on purpose: a file written by an older build may miss fields (the
	 * defaults mirror the mod's own defaults) or carry no hash at all, in which case
	 * the hash is recomputed so the entry stays reachable. A damaged entry is never
	 * a reason to lose the whole cache file.
	 */
	public static CacheEntry fromJson(JsonObject json) {
		CacheEntry entry = new CacheEntry();
		entry.hash = json.has("hash") ? json.get("hash").getAsString() : null;
		entry.original = json.has("original") ? json.get("original").getAsString() : "";
		entry.translated = json.has("translated") ? json.get("translated").getAsString() : "";
		entry.sourceLang = json.has("sourceLang") ? json.get("sourceLang").getAsString() : "auto";
		entry.targetLang = json.has("targetLang") ? json.get("targetLang").getAsString() : "zh_cn";
		entry.textType = json.has("textType") ? json.get("textType").getAsString() : "chat";
		entry.updatedAt = json.has("updatedAt") ? json.get("updatedAt").getAsLong() : System.currentTimeMillis();
		if (entry.hash == null) {
			entry.hash = hash(entry.original, entry.sourceLang, entry.targetLang);
		}
		return entry;
	}
}
