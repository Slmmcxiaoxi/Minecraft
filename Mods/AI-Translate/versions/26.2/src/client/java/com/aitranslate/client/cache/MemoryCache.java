package com.aitranslate.client.cache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory translation index for the currently active context.
 * <p>
 * Bounded LRU: the map keeps insertion order but is re-ordered on access, so once
 * the configured limit is reached the least recently used entries are evicted
 * first. That keeps long sessions (many maps, thousands of strings) from growing
 * the heap without bound.
 * <p>
 * Access is synchronised because the map is read from the render thread and
 * written from the translation worker threads; the critical sections are tiny
 * (a hash lookup), which is cheaper than the churn of a lock-free structure.
 */
public class MemoryCache implements TranslationCache {
	private final java.util.function.IntSupplier maxEntries;
	private final Map<String, CacheEntry> entries;
	private final AtomicLong hits = new AtomicLong();
	private final AtomicLong misses = new AtomicLong();

	public MemoryCache() {
		this(() -> 5000);
	}

	public MemoryCache(java.util.function.IntSupplier maxEntries) {
		this.maxEntries = maxEntries;
		this.entries = new LinkedHashMap<>(256, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
				// 8 is only a safety net; the configured limit is >= 500 in the UI.
				return size() > Math.max(8, MemoryCache.this.maxEntries.getAsInt());
			}
		};
	}

	@Override
	public synchronized Optional<String> get(String original, String sourceLang, String targetLang) {
		CacheEntry entry = entries.get(CacheEntry.hash(original, sourceLang, targetLang));
		if (entry != null && entry.translated != null && !entry.translated.isBlank()) {
			hits.incrementAndGet();
			return Optional.of(entry.translated);
		}
		misses.incrementAndGet();
		return Optional.empty();
	}

	@Override
	public synchronized void put(String original, String translated, String sourceLang, String targetLang,
			String textType) {
		if (original == null || translated == null || translated.isBlank()) {
			return;
		}
		CacheEntry entry = new CacheEntry(original, translated, sourceLang, targetLang, textType);
		entries.put(entry.key(), entry);
		enforceLimit();
	}

	/**
	 * Adds an entry that was read from disk, dropping stored echoes.
	 * <p>
	 * Fifteenth feedback round: a cache written by an older build can contain an answer
	 * that merely repeats its input ("the Brave" -> "the Brave"). Served from here it
	 * would freeze that text in English for good, because a cache hit is never asked
	 * again - so such an entry is not loaded and the text is requested normally instead.
	 * The disk file is rewritten without it on the next save.
	 */
	public synchronized void put(CacheEntry entry) {
		if (entry == null || entry.translated == null || entry.translated.isBlank()) {
			return;
		}
		if (com.aitranslate.client.scheduler.AnswerQuality.isStoredEcho(entry.original, entry.translated)) {
			droppedEchoes++;
			return;
		}
		entries.put(entry.key(), entry);
		enforceLimit();
	}

	/** How many stored echo answers were rejected on load (diagnostics). */
	public int droppedEchoes() {
		return droppedEchoes;
	}

	private int droppedEchoes;

	/**
	 * Trims the map to the configured limit. {@code removeEldestEntry} only drops
	 * one entry per insert, which is not enough when the user lowers the limit in
	 * the settings while a full cache is loaded.
	 */
	private void enforceLimit() {
		int limit = Math.max(8, maxEntries.getAsInt());
		while (entries.size() > limit) {
			java.util.Iterator<Map.Entry<String, CacheEntry>> iterator = entries.entrySet().iterator();
			if (!iterator.hasNext()) {
				return;
			}
			iterator.next();
			iterator.remove();
		}
	}

	public synchronized void putAll(Collection<CacheEntry> all) {
		for (CacheEntry entry : all) {
			put(entry);
		}
	}

	public synchronized Collection<CacheEntry> entries() {
		return new java.util.ArrayList<>(entries.values());
	}


	public synchronized Map<String, CacheEntry> raw() {
		return entries;
	}

	@Override
	public void save() {
		// Persisting is the DiskCache's job.
	}

	@Override
	public void load() {
		// Loading is the DiskCache's job.
	}

	@Override
	public synchronized void clear() {
		entries.clear();
	}

	@Override
	public synchronized int size() {
		return entries.size();
	}

	public long hits() {
		return hits.get();
	}

	public long misses() {
		return misses.get();
	}

	/** Hit rate in percent, for the performance summary. */
	public long hitRatePercent() {
		long total = hits.get() + misses.get();
		return total == 0 ? 0 : hits.get() * 100 / total;
	}
}
