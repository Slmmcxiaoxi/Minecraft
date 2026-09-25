package com.aitranslate.client.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.aitranslate.AITranslateMod;
import com.aitranslate.client.cache.CacheEntry;
import com.aitranslate.client.cache.CacheManager;
import com.aitranslate.client.capture.TextType;
import com.aitranslate.client.config.ModConfig;
import com.aitranslate.client.display.TranslationSupport;
import com.aitranslate.client.focus.FocusTracker;
import com.aitranslate.client.provider.ModelAnswerException;
import com.aitranslate.client.provider.ProviderManager;
import com.aitranslate.client.provider.TranslationProvider;
import com.aitranslate.client.provider.TranslationTransportException;
import com.aitranslate.client.util.PlaceholderUtil;
import net.minecraft.client.Minecraft;

/**
 * Central translation dispatcher.
 * <p>
 * Responsibilities: language detection, de-duplication, batching, cache lookup,
 * asynchronous requests with retry and main-thread notifications. Rendering
 * code never blocks on this class - it either finds a cached translation or
 * keeps rendering the original text until the callback fires.
 * <p>
 * Tenth feedback round: the dispatcher also owns the two things that decide
 * whether a long book page ever arrives -
 * <ul>
 * <li>how much text one request may carry ({@link BatchPlanner}: at most
 * {@code maxBatchSize} elements <em>and</em> {@code maxBatchChars} characters),
 * with the long texts split into cacheable segments ({@link LongText});</li>
 * <li>what a failure means ({@link FailurePolicy}: consecutive transport
 * failures only, retry first, keep the original text and carry on - the master
 * switch is the last resort, never the first reaction).</li>
 * </ul>
 * Requests carry a {@link TranslationPriority} so that the text the player is
 * looking at is served before background work.
 */
public class TranslationScheduler {
	private static final Logger LOGGER = AITranslateMod.LOGGER;

	/** Small delay so that a burst of texts (e.g. a chat page) is sent as one request. */
	private static final long DEBOUNCE_MS = 80L;
	/** How long a text whose <em>answer</em> was unusable is not retried. */
	private static final long FAILURE_TTL_MS = 60_000L;
	/**
	 * How long a text waits after a transport failure (tenth feedback round).
	 * <p>
	 * Shorter than {@link #FAILURE_TTL_MS} on purpose: a timeout or a refused
	 * connection usually means "the server is busy right now", so retrying after a
	 * few seconds is how the mod recovers on its own from a hiccup. It also means
	 * the consecutive-failure counter moves fast enough for the player to be told
	 * within about a minute when the API is really gone, instead of after five
	 * minutes of a silent, untranslated game.
	 */
	private static final long TRANSPORT_RETRY_MS = 10_000L;
	/**
	 * Retry delay for a book page whose answer was unusable (eleventh feedback
	 * round). Much shorter than {@link #FAILURE_TTL_MS}: the page is only requested
	 * while it is on screen, so waiting a minute means the player has already
	 * scrolled past it. A local model usually gets the same page right on the next
	 * attempt, especially because a single text also retries with the plain-text
	 * protocol (see {@code OpenAIProvider}).
	 */
	private static final long BOOK_ANSWER_RETRY_MS = 15_000L;
	/**
	 * A batch that has not answered after this long is abandoned (tenth feedback
	 * round): the texts go back to the queue for a later attempt and the request
	 * slot is released, so one stuck request can never block the whole pipeline.
	 * The provider's own timeout (per attempt) is smaller, so this is the safety
	 * net for a connection that hangs without ever completing.
	 */
	private static final long MIN_WATCHDOG_MS = 180_000L;

	/** The watchdog, long enough for every retry of the provider to finish. */
	private long watchdogMs() {
		long attempts = Math.max(1, config.maxRetries + 1);
		return Math.max(MIN_WATCHDOG_MS,
				(long) Math.max(30, config.longRequestTimeoutSeconds) * attempts * 1000L + 30_000L);
	}

	private final ModConfig config;
	private final CacheManager cacheManager;
	private final ProviderManager providerManager;
	private final TextDetector detector = new TextDetector();

	private final Map<String, QueuedTranslation> queue = new ConcurrentHashMap<>();
	/** O(1) FIFO indexes; stale hashes are discarded lazily after promotion/cancellation. */
	private final java.util.EnumMap<TranslationPriority, java.util.concurrent.ConcurrentLinkedDeque<String>>
			priorityQueues = new java.util.EnumMap<>(TranslationPriority.class);
	private final Map<String, Long> queuedAt = new ConcurrentHashMap<>();
	private static final long LOW_PRIORITY_TTL_MS = 30_000L;
	private static final int MAX_CONCURRENT_REQUESTS = 2;
	private volatile long lastStalePurge;
	/** Simple token bucket: at most {@code maxRequestsPerSecond} requests per second. */
	private final RateLimiter rateLimiter = new RateLimiter();
	private final FailurePolicy failurePolicy = new FailurePolicy();
	/** Performance counters for {@code /aitranslate perf}. */
	private final java.util.concurrent.atomic.AtomicLong batches = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong latencySumMs = new java.util.concurrent.atomic.AtomicLong();
	private final java.util.concurrent.atomic.AtomicLong latencyCount = new java.util.concurrent.atomic.AtomicLong();
	private final AtomicLong sequence = new AtomicLong();
	/** Invalidates completions belonging to a world/server that has been left. */
	private final AtomicLong contextGeneration = new AtomicLong();
	/** Batches currently in flight, for the watchdog and for the stats line. */
	private final Map<Long, InFlightBatch> inFlightBatches = new ConcurrentHashMap<>();
	private volatile long nextBatchAllowedAt;
	private final Map<String, List<Runnable>> waiters = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Map<String, Long> failed = new ConcurrentHashMap<>();
	/** Texts whose last failure was a transport failure (shorter retry delay). */
	private final Set<String> failedTransport = ConcurrentHashMap.newKeySet();
	/**
	 * Long texts currently being translated in segments: original hash -> group.
	 * The parts travel as normal (small) requests and are re-joined when the last
	 * one is back.
	 */
	private final Map<String, SegmentGroup> segments = new ConcurrentHashMap<>();
	/** Part hash -> hash of every long text the part belongs to. */
	private final Map<String, Set<String>> partOwner = new ConcurrentHashMap<>();
	/**
	 * Texts whose batch failed permanently. They are re-sent one by one, because a
	 * model that returns the wrong number of elements for twenty texts almost
	 * always answers a single text correctly - without this, a whole page of text
	 * would silently stay untranslated (observed with a local 7B translation
	 * model that answered 12 of 19 elements).
	 */
	private final java.util.Deque<QueuedTranslation> retrySingles =
			new java.util.concurrent.ConcurrentLinkedDeque<>();
	/** Hashes that already used their single retry (keeps the fallback bounded). */
	private final Set<String> singleRetried = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
	private final ScheduledExecutorService timer;

	private volatile Runnable invalidator = () -> {
	};
	/**
	 * Called with the error message when the API failed often enough in a row to
	 * be considered down (tenth feedback round: five consecutive transport
	 * failures, not one). The client entrypoint wires this to the
	 * "API 连接失败" notification.
	 */
	private volatile java.util.function.BiConsumer<String, Integer> failureNotifier = (reason, count) -> {
	};
	/**
	 * Text types whose translation arrived since the last refresh; the refresh can
	 * then rebuild only what is affected (the chat line cache is by far the most
	 * expensive rebuild, so it is skipped for sign/item/… translations).
	 */
	private final Set<com.aitranslate.client.capture.TextType> changedTypes =
			java.util.EnumSet.noneOf(com.aitranslate.client.capture.TextType.class);

	private volatile long requestCount;
	private volatile long translatedCount;
	private volatile long failureCount;
	private volatile String lastErrorText = "";
	/** Texts that were not requested because "only translate what is visible" is on. */
	/**
	 * Warm-up window after entering a world (sixth feedback round).
	 * <p>
	 * World join loads thousands of chunks, block entities and entities; the texts
	 * that become visible in that moment (signs, entity names, container titles) used
	 * to be requested all at once, while the world's disk cache was still being read.
	 * Until {@link #warmUpUntil} requests are held back entirely, and for
	 * {@link #WARM_UP_RAMP_MS} after that the request rate is lowered, so the first
	 * seconds belong to the game and to the cache load.
	 */
	private volatile long warmUpUntil;
	private static final long WARM_UP_RAMP_MS = 4000L;
	private static final int WARM_UP_REQUESTS_PER_SECOND = 2;
	private final java.util.concurrent.atomic.AtomicLong heldBackCount =
			new java.util.concurrent.atomic.AtomicLong();

	public TranslationScheduler(ModConfig config, CacheManager cacheManager, ProviderManager providerManager) {
		this.config = config;
		this.cacheManager = cacheManager;
		this.providerManager = providerManager;
		this.detector.setLanguages(config.sourceLang, config.targetLang);
		for (TranslationPriority priority : TranslationPriority.values()) {
			priorityQueues.put(priority, new java.util.concurrent.ConcurrentLinkedDeque<>());
		}
		this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "ai-translate-scheduler");
			thread.setDaemon(true);
			return thread;
		});
	}

	public TextDetector detector() {
		return detector;
	}

	/** Called on the client thread when new translations arrived (e.g. re-trim chat). */
	public void setInvalidator(Runnable invalidator) {
		this.invalidator = invalidator == null ? () -> {
		} : invalidator;
	}

	/** Called when the API failed often enough in a row (see {@code ApiFailureNotifier}). */
	public void setFailureNotifier(java.util.function.BiConsumer<String, Integer> notifier) {
		this.failureNotifier = notifier == null ? (reason, count) -> {
		} : notifier;
	}

	/**
	 * Text types that got a translation since this method was last called. Used to
	 * rebuild only the affected render caches.
	 */
	public Set<com.aitranslate.client.capture.TextType> drainChangedTypes() {
		Set<com.aitranslate.client.capture.TextType> copy =
				java.util.EnumSet.noneOf(com.aitranslate.client.capture.TextType.class);
		for (com.aitranslate.client.capture.TextType type : com.aitranslate.client.capture.TextType.values()) {
			if (changedTypes.remove(type)) {
				copy.add(type);
			}
		}
		return copy;
	}

	/** Master switch AND per-source switch AND a usable API configuration. */
	public boolean isEnabled(TextType type) {
		return isAvailable() && config.isSourceEnabled(type);
	}

	/**
	 * Whether translation can happen at all: the master switch is on and the API is
	 * configured (or the offline test provider is active).
	 * <p>
	 * Used by sources whose own switch is more specific than the text type - a chat
	 * line is translated according to <em>which command</em> produced it
	 * (thirteenth feedback round), so the generic chat switch does not decide that
	 * case.
	 */
	public boolean isAvailable() {
		if (!config.translateEnabled) {
			return false;
		}
		// Without a Base URL / API key every request would fail, and those failures
		// would be counted towards "the API is down" (tenth feedback round). Not
		// requesting at all keeps the original text and the counters clean; the
		// player is told once per world that the API is not configured.
		return config.isApiConfigured();
	}

	/**
	 * Synchronous cache lookup - safe to call from the render thread.
	 * <p>
	 * Results produced by the offline test provider are kept in a separate memory
	 * only cache: they must never be written to the persistent cache, otherwise
	 * the fake "[译] ..." text would keep showing up long after test mode is
	 * switched off.
	 */
	public Optional<String> cached(TextType type, String text) {
		if (text == null || text.isEmpty()) {
			return Optional.empty();
		}
		detector.setLanguages(config.sourceLang, config.targetLang);
		if (com.aitranslate.client.AITranslateModClient.dictionaryManager != null) {
			Optional<String> fixed = com.aitranslate.client.AITranslateModClient.dictionaryManager.lookup(text,
					config.sourceLang, config.targetLang, !config.dictionaryCaseSensitive);
			if (fixed.isPresent()) {
				return fixed;
			}
		}
		// The filters run on reads as well, not only before a request: a cache file can
		// contain entries that were written before a filter existed (a real player name
		// translated by an older build, for instance), and showing them would break the
		// rule that player names stay untouched.
		if (!detector.shouldTranslate(text)) {
			return Optional.empty();
		}
		Optional<String> cached = cacheManager.current().get(text, config.sourceLang, config.targetLang);
		if (cached.isPresent() && AnswerQuality.hasClearlyWrongScript(cached.get(), config.targetLang)) {
			// Older builds could store a Korean fallback under zh_cn. Treat it as a
			// miss; the next valid answer overwrites the same cache key.
			return Optional.empty();
		}
		if (cached.isPresent()) {
			String cleaned = com.aitranslate.client.provider.ModelAnswers.cleanTranslation(cached.get());
			if (cleaned.isBlank()) {
				return Optional.empty();
			}
			if (!cleaned.equals(cached.get())) {
				// Repair polluted entries written by an older build (for example
				// {"text":"注意"}) as they are encountered, so the user does not have
				// to delete a whole world's cache after upgrading.
				cacheManager.current().put(text, cleaned, config.sourceLang, config.targetLang, type.name());
				return Optional.of(cleaned);
			}
		}
		return cached;
	}

	/**
	 * Requests a translation at the priority the current focus suggests. The
	 * callback runs on the client thread once the translation is available (either
	 * immediately when it is cached, or later).
	 *
	 * @return {@code true} when a network request was queued.
	 */
	public boolean request(TextType type, String text, Runnable onReady) {
		return request(type, text, FocusTracker.priorityFor(type, text), onReady);
	}

	/**
	 * Requests a translation at an explicit priority (tenth feedback round).
	 *
	 * @return {@code true} when a network request was queued.
	 */
	public boolean request(TextType type, String text, TranslationPriority priority, Runnable onReady) {
		return request(type, text, priority, onReady, null);
	}

	/**
	 * Same, with a label that names the source in the log ("book page 19/21").
	 * <p>
	 * Eleventh feedback round: a book page that could not be translated has to be
	 * identifiable in the log. The label travels with the queue entry, so both the
	 * "unusable answer" warning and the success line name the page.
	 */
	public boolean request(TextType type, String text, TranslationPriority priority, Runnable onReady, String label) {
		if (label != null && label.startsWith("diagnostic:")) {
			LOGGER.info("[trace:{}:3-scheduler] entered source='{}' type={} enabled={} available={}", label,
					abbreviate(text), type, isEnabled(type), isAvailable());
		}
		if (!isEnabled(type) || text == null || text.isEmpty()) {
			return false;
		}
		TranslationPriority effective = priority == null ? TranslationPriority.NORMAL : priority;
		String hash = CacheEntry.hash(text, config.sourceLang, config.targetLang);
		if (cached(type, text).isPresent()) {
			return false;
		}
		Long failedAt = failed.get(hash);
		if (failedAt != null) {
			long ttl = failedTransport.contains(hash) ? TRANSPORT_RETRY_MS : answerFailureTtl(type);
			if (System.currentTimeMillis() - failedAt < ttl) {
				return false;
			}
			failed.remove(hash);
			failedTransport.remove(hash);
		}
		if (!detector.shouldTranslate(text)) {
			if (config.debugLog) {
				// "Skipped" without a reason is what made the twelfth round's
				// <Death> bug invisible: the text was dropped by the placeholder
				// filter and nothing in the log said so.
				com.aitranslate.client.util.DebugLog.once("skip:" + hash, "[skip] \"{}\": {}",
						abbreviate(text), detector.skipReason(text));
			}
			if (logsSource(label)) {
				LOGGER.info("[{}] not requested: {} ({} chars, \"{}\")", label, detector.skipReason(text),
						text.length(), abbreviate(text));
			}
			return false;
		}
		if (inFlight.contains(hash) || segments.containsKey(hash)) {
			addWaiter(hash, onReady);
			return false;
		}
		// A long text is split before it is queued: every segment is a small,
		// separately cacheable request, and the joined result is stored under the
		// original text (tenth feedback round, book pages).
		if (LongText.needsSplit(text, config.maxTextSegmentChars)) {
			return enqueueSegments(type, text, hash, effective, onReady, label);
		}
		if (!queue.containsKey(hash) && !makeQueueRoom(effective)) {
			if (config.debugLog) {
				LOGGER.debug("Queue limit reached; deferred {} text \"{}\"", effective, abbreviate(text));
			}
			return false;
		}
		QueuedTranslation pending = new QueuedTranslation(type, text, hash, effective, sequence.incrementAndGet(), null,
				label);
		boolean queued = queue.putIfAbsent(hash, pending) == null;
		if (!queued) {
			// Already queued: keep the more urgent priority of the two, so a text that
			// the crosshair has moved onto is promoted instead of demoted.
			queue.computeIfPresent(hash, (key, existing) -> {
				TranslationPriority promoted = existing.priority().moreUrgent(effective);
				if (promoted != existing.priority()) {
					QueuedTranslation replacement = existing.withPriority(promoted);
					index(replacement);
					return replacement;
				}
				return existing;
			});
		}
		addWaiter(hash, onReady);
		if (queued) {
			queuedAt.put(hash, System.currentTimeMillis());
			index(pending);
			if (logsSource(label)) {
				if (label.startsWith("diagnostic:")) {
					LOGGER.info("[{}] request queued source='{}' ({} chars, priority {})", label, abbreviate(text),
							text.length(), effective);
				} else {
					LOGGER.info("[{}] queued ({} chars, priority {})", label, text.length(), effective);
				}
			}
			scheduleFlush();
		}
		return queued;
	}

	/**
	 * Bounds render-driven work. More urgent text may evict the oldest not-yet-started
	 * entry of equal or lower urgency; background text can never evict focused work.
	 */
	private synchronized boolean makeQueueRoom(TranslationPriority incoming) {
		int limit = Math.max(100, config.maxPendingTranslations);
		if (queue.size() < limit) {
			return true;
		}
		String oldestHash = null;
		long oldestTime = Long.MAX_VALUE;
		for (Map.Entry<String, QueuedTranslation> entry : queue.entrySet()) {
			QueuedTranslation candidate = entry.getValue();
			if (candidate.isSegment() || candidate.priority().rank() < incoming.rank()) {
				continue;
			}
			long time = queuedAt.getOrDefault(entry.getKey(), Long.MAX_VALUE);
			if (time < oldestTime) {
				oldestTime = time;
				oldestHash = entry.getKey();
			}
		}
		if (oldestHash == null) {
			return false;
		}
		QueuedTranslation removed = queue.remove(oldestHash);
		queuedAt.remove(oldestHash);
		waiters.remove(oldestHash);
		if (removed != null && config.debugLog) {
			LOGGER.debug("Queue limit {}: evicted oldest {} text \"{}\" for {} work", limit,
					removed.priority(), abbreviate(removed.text()), incoming);
		}
		return removed != null;
	}

	private void index(QueuedTranslation pending) {
		priorityQueues.get(pending.priority()).offerLast(pending.hash());
	}

	private void clearQueueIndexes() {
		priorityQueues.values().forEach(java.util.Deque::clear);
		queuedAt.clear();
	}

	/**
	 * How long a text whose answer was unusable waits before it is asked again.
	 * <p>
	 * A book page is only requested while it is displayed, so a page that failed
	 * has to be retried while the player is still reading it - a full minute later
	 * they have usually turned to the next page and never see the translation
	 * (eleventh feedback round).
	 */
	private long answerFailureTtl(TextType type) {
		return type == TextType.BOOK ? BOOK_ANSWER_RETRY_MS : FAILURE_TTL_MS;
	}

	/** True when the label belongs to a source the player asked to see logged. */
	private boolean logsSource(String label) {
		if (label == null || label.isBlank()) {
			return false;
		}
		return label.startsWith("diagnostic:") || config.logBookPages || config.debugLog;
	}

	/** Requests a whole set of texts (chat page, sign lines, book page). */
	public void requestAll(TextType type, Collection<String> texts, Runnable onReady) {
		requestAll(type, texts, null, onReady);
	}

	/** Requests a whole set of texts at one priority. */
	public void requestAll(TextType type, Collection<String> texts, TranslationPriority priority, Runnable onReady) {
		if (texts == null || texts.isEmpty()) {
			return;
		}
		Set<String> unique = new LinkedHashSet<>(texts);
		for (String text : unique) {
			request(type, text, priority, onReady);
		}
	}

	private void addWaiter(String hash, Runnable onReady) {
		if (onReady == null) {
			return;
		}
		waiters.computeIfAbsent(hash, k -> new ArrayList<>()).add(onReady);
	}

	// ------------------------------------------------------------ warm-up

	/**
	 * Starts the warm-up window (called when a world was entered). Requests are held
	 * back for {@code delayMs} and then run at a reduced rate for a few more seconds.
	 */
	public void beginWarmUp(long delayMs) {
		warmUpUntil = System.currentTimeMillis() + Math.max(0L, delayMs);
		LOGGER.info("AI Translate warm-up: translation requests pause for {} ms after world join", delayMs);
	}

	private boolean isWarmingUp() {
		return System.currentTimeMillis() < warmUpUntil;
	}

	/** The rate limit to use right now: reduced during the warm-up ramp. */
	private int currentRateLimit() {
		long rampEnd = warmUpUntil + WARM_UP_RAMP_MS;
		if (System.currentTimeMillis() < rampEnd) {
			return Math.min(Math.max(1, config.maxRequestsPerSecond), WARM_UP_REQUESTS_PER_SECOND);
		}
		return Math.max(1, config.maxRequestsPerSecond);
	}

	/** True while the post-join warm-up (pause or ramp) is still running. */
	public boolean inWarmUp() {
		return System.currentTimeMillis() < warmUpUntil + WARM_UP_RAMP_MS;
	}

	public long heldBackRequests() {
		return heldBackCount.get();
	}

	// -------------------------------------------------------- long texts

	/**
	 * Queues the segments of a long text. The original text is not queued itself -
	 * its hash is registered as "in flight" through {@link #segments} until the last
	 * segment is answered, and the joined result is then cached under the original
	 * hash so the render side sees an ordinary cache hit.
	 */
	private boolean enqueueSegments(TextType type, String text, String hash, TranslationPriority priority,
			Runnable onReady, String label) {
		List<String> parts = LongText.split(text, config.maxTextSegmentChars);
		if (parts.size() <= 1) {
			QueuedTranslation pending = new QueuedTranslation(type, text, hash, priority, sequence.incrementAndGet(),
					null, label);
			if (queue.putIfAbsent(hash, pending) == null) {
				queuedAt.put(hash, System.currentTimeMillis());
				index(pending);
			}
			addWaiter(hash, onReady);
			scheduleFlush();
			return true;
		}
		SegmentGroup group = new SegmentGroup(type, text, hash, parts);
		segments.put(hash, group);
		addWaiter(hash, onReady);
		boolean queued = false;
		int fromCache = 0;
		for (int i = 0; i < parts.size(); i++) {
			String part = parts.get(i);
			String partHash = CacheEntry.hash(part, config.sourceLang, config.targetLang);
			group.partHashes()[i] = partHash;
			String cachedPart = cachedPart(part, partHash);
			if (cachedPart != null) {
				// Already translated earlier (a repeated sentence, a re-opened page):
				// take it from the cache without another request.
				group.accept(i, cachedPart);
				fromCache++;
				continue;
			}
			// A part may belong to more than one long text (the same sentence on two
			// pages): every owner is completed when the part comes back.
			partOwner.computeIfAbsent(partHash, key -> ConcurrentHashMap.newKeySet()).add(hash);
			QueuedTranslation pending = new QueuedTranslation(type, part, partHash, priority,
					sequence.incrementAndGet(), hash, segmentLabel(label, i + 1, parts.size()));
			if (queue.putIfAbsent(partHash, pending) == null) {
				queuedAt.put(partHash, System.currentTimeMillis());
				index(pending);
				queued = true;
			}
		}
		if (config.debugLog || config.logBookPages) {
			LOGGER.info("[long-text] {}{} chars split into {} segments ({} from cache)", text.length(),
					label == null ? "" : " (" + label + ")", parts.size(), fromCache);
		}
		if (group.isComplete()) {
			finishSegment(group);
			return true;
		}
		if (queued) {
			scheduleFlush();
		}
		return queued;
	}

	/** "book page 19/21" + " part 2/3", so a segment names its page in the log. */
	private static String segmentLabel(String label, int part, int total) {
		if (label == null || label.isBlank()) {
			return null;
		}
		return label + " part " + part + "/" + total;
	}

	/** The cached translation of one segment, or {@code null}. */
	private String cachedPart(String part, String partHash) {
		if (com.aitranslate.client.AITranslateModClient.dictionaryManager != null) {
			Optional<String> fixed = com.aitranslate.client.AITranslateModClient.dictionaryManager.lookup(part,
					config.sourceLang, config.targetLang, !config.dictionaryCaseSensitive);
			if (fixed.isPresent()) {
				return fixed.get();
			}
		}
		if (cacheManager == null) {
			return null;
		}
		return cacheManager.current().get(part, config.sourceLang, config.targetLang).orElse(null);
	}

	/** One segment came back (translated or not). */
	private void completeSegment(String partHash, String translated) {
		Set<String> owners = partOwner.remove(partHash);
		if (owners == null || owners.isEmpty()) {
			return;
		}
		for (String owner : owners) {
			SegmentGroup group = segments.get(owner);
			if (group == null) {
				continue;
			}
			int index = group.indexOf(partHash);
			if (index < 0) {
				continue;
			}
			group.accept(index, translated);
			if (group.isComplete()) {
				finishSegment(group);
			}
		}
	}

	/** All segments answered: store the joined translation under the original text. */
	private void finishSegment(SegmentGroup group) {
		segments.remove(group.originalHash());
		String joined = group.joined();
		boolean anyTranslated = group.anyTranslated();
		if (anyTranslated) {
			if (cacheManager != null) {
				cacheManager.current().put(group.originalText(), joined, config.sourceLang, config.targetLang,
						group.type().name());
			}
			translatedCount++;
			changedTypes.add(group.type());
			TranslationSupport.bumpGeneration();
			notifyInvalidator();
			if (config.debugLog) {
				LOGGER.info("[translate] long text ({} chars, {} segments) -> {} chars",
						group.originalText().length(), group.parts().size(), joined.length());
			}
		} else {
			// Every segment failed (their own failure state is already recorded):
			// keep the original text for now and do not retry it immediately.
			failed.putIfAbsent(group.originalHash(), System.currentTimeMillis());
		}
		notifyWaiters(Set.of(group.originalHash()));
	}

	// ------------------------------------------------------------ flushing

	private void scheduleFlush() {
		scheduleFlushIn(DEBOUNCE_MS);
	}

	private void scheduleFlushIn(long delayMs) {
		if (flushScheduled.compareAndSet(false, true)) {
			timer.schedule(this::flush, Math.max(10L, delayMs), TimeUnit.MILLISECONDS);
		}
	}

	private void flush() {
		flushScheduled.set(false);
		if (!config.translateEnabled) {
			queue.clear();
			clearQueueIndexes();
			return;
		}
		abandonStuckBatches();
		if (inFlightBatches.size() >= Math.min(MAX_CONCURRENT_REQUESTS, Math.max(1, config.httpThreads))) {
			scheduleFlushIn(50L);
			return;
		}
		if (isWarmingUp()) {
			// Entering a world: hold everything back until the game has finished its
			// initial load and the world's cache has been read from disk (sixth
			// feedback round). The texts stay queued, so nothing is lost - most of
			// them are cache hits by the time the window is over.
			heldBackCount.incrementAndGet();
			scheduleFlushIn(200L);
			return;
		}
		if (!rateLimiter.tryAcquire()) {
			// Too many requests per second: wait for the next slot instead of
			// hammering the API (and risking a 429).
			scheduleFlushIn(rateLimiter.millisUntilNextSlot());
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextBatchAllowedAt) {
			// Spacing between batches (tenth feedback round): a page of text must not
			// arrive as a burst of ten requests.
			scheduleFlushIn(nextBatchAllowedAt - now);
			return;
		}
		List<QueuedTranslation> batch = selectBatch();
		if (batch.isEmpty()) {
			return;
		}
		send(batch);
		if (!queue.isEmpty() || !retrySingles.isEmpty()) {
			// More work queued while this batch was being assembled: come back after
			// the configured gap instead of immediately.
			scheduleFlushIn(Math.max(50L, config.batchIntervalMs));
		}
	}

	/** The next batch: a single retry, or one FIFO priority queue without sorting. */
	private List<QueuedTranslation> selectBatch() {
		List<QueuedTranslation> batch = new ArrayList<>();
		// One single-text retry per flush: it must travel alone (mixing it into a
		// batch would re-run the request that just failed), and the rate limiter
		// bounds how fast the fallbacks drain, so a storm of retries cannot turn
		// into a burst of requests.
		QueuedTranslation single = retrySingles.poll();
		if (single != null) {
			// The retry travels alone (mixing it into a batch would re-run the request
			// that just failed) and takes precedence over everything else.
			queue.remove(single.hash());
			queuedAt.remove(single.hash());
			inFlight.add(single.hash());
			batch.add(single);
			return batch;
		}
		int maxItems = Math.max(1, Math.min(config.maxBatchSize, providerManager.provider().maxBatchSize()));
		int maxChars = Math.max(1, config.maxBatchChars);
		long now = System.currentTimeMillis();
		for (TranslationPriority priority : TranslationPriority.values()) {
			var index = priorityQueues.get(priority);
			int chars = 0;
			while (batch.size() < maxItems) {
				String hash = index.pollFirst();
				if (hash == null) {
					break;
				}
				QueuedTranslation candidate = queue.get(hash);
				if (candidate == null || candidate.priority() != priority) {
					continue;
				}
				Long since = queuedAt.get(hash);
				if (priority == TranslationPriority.LOW && since != null
						&& now - since > LOW_PRIORITY_TTL_MS) {
					queue.remove(hash, candidate);
					queuedAt.remove(hash);
					continue;
				}
				if (!batch.isEmpty() && chars + candidate.text().length() > maxChars) {
					index.offerFirst(hash);
					break;
				}
				if (queue.remove(hash, candidate)) {
					queuedAt.remove(hash);
					inFlight.add(hash);
					batch.add(candidate);
					chars += candidate.text().length();
				}
			}
			// One request contains one priority only. This avoids rescanning and keeps
			// foreground and stale background work in separate API batches.
			if (!batch.isEmpty()) {
				break;
			}
		}
		return batch;
	}

	private void send(List<QueuedTranslation> batch) {
		List<String> texts = new ArrayList<>(batch.size());
		int chars = 0;
		for (QueuedTranslation pending : batch) {
			texts.add(pending.text());
			chars += pending.text().length();
		}

		TranslationProvider provider = providerManager.provider();
		requestCount++;
		batches.incrementAndGet();
		final int payloadChars = chars;
		long startedAt = System.currentTimeMillis();
		nextBatchAllowedAt = startedAt + Math.max(0L, config.batchIntervalMs);
		long batchId = sequence.incrementAndGet();
		long batchContext = contextGeneration.get();
		InFlightBatch inFlightBatch = new InFlightBatch(batch, startedAt);
		inFlightBatches.put(batchId, inFlightBatch);
		if (config.debugLog) {
			LOGGER.info("[batch] {} text(s), {} chars, priority {} -> {}", batch.size(), chars,
					batch.get(0).priority(), provider.name());
			for (QueuedTranslation pending : batch) {
				LOGGER.info("[translate] {}: {}", pending.textType(), abbreviate(pending.text()));
			}
		}
		for (QueuedTranslation pending : batch) {
			if (pending.label() != null && pending.label().startsWith("diagnostic:")) {
				LOGGER.info("[trace:{}:5-request] provider={} source='{}' wire='{}' batchSize={}", pending.label(),
						provider.name(), abbreviate(pending.text()), abbreviate(texts.get(batch.indexOf(pending))),
						batch.size());
			}
		}
		provider.translate(texts, config.sourceLang, config.targetLang)
				.whenComplete((results, error) -> {
					inFlightBatches.remove(batchId);
					if (batchContext != contextGeneration.get()) {
						// This answer belongs to a world/server that has already been left.
						return;
					}
					try {
						latencySumMs.addAndGet(System.currentTimeMillis() - startedAt);
						latencyCount.incrementAndGet();
						if (error != null) {
							failureCount++;
							lastErrorText = rootMessage(error);
							LOGGER.warn("Translation request failed ({} text(s), {} chars): {}", batch.size(),
									payloadChars, lastErrorText);
							onFailure(batch, error);
						} else {
							onSuccess(batch, results);
						}
					} catch (Throwable callbackError) {
						LOGGER.error("Translation completion callback failed; affected texts return to original", callbackError);
						Set<String> done = new HashSet<>();
						for (QueuedTranslation pending : batch) {
							release(pending);
							fail(pending.hash(), false);
							done.add(pending.hash());
						}
						notifyWaiters(done);
					} finally {
						// Leftovers (e.g. more texts queued while we were waiting).
						if (!queue.isEmpty() || !retrySingles.isEmpty()) scheduleFlush();
					}
				});
	}

	/** Drops queued work and makes older asynchronous completions stale. */
	public void onContextChanged() {
		contextGeneration.incrementAndGet();
		queue.clear();
		clearQueueIndexes();
		inFlight.clear();
		inFlightBatches.clear();
		failed.clear();
		failedTransport.clear();
		singleRetried.clear();
		retrySingles.clear();
		segments.clear();
		partOwner.clear();
		waiters.clear();
	}

	/**
	 * Reclaims texts from a request that has been in flight for far longer than the
	 * provider's own timeout (tenth feedback round). The request itself cannot be
	 * cancelled from here, but its texts stop blocking the queue and become
	 * eligible for a fresh (smaller) request.
	 */
	private void abandonStuckBatches() {
		long now = System.currentTimeMillis();
		long watchdog = watchdogMs();
		boolean abandoned = false;
		for (Map.Entry<Long, InFlightBatch> entry : inFlightBatches.entrySet()) {
			InFlightBatch stuck = entry.getValue();
			if (now - stuck.startedAt() < watchdog) {
				continue;
			}
			if (inFlightBatches.remove(entry.getKey()) == null) {
				continue;
			}
			abandoned = true;
			LOGGER.warn("Abandoning a request that has been running for {} ms ({} text(s)) - "
					+ "the texts stay queued for a later attempt",
					now - stuck.startedAt(), stuck.batch().size());
			for (QueuedTranslation pending : stuck.batch()) {
				release(pending);
				failed.remove(pending.hash());
				failedTransport.remove(pending.hash());
				QueuedTranslation retry = pending.withPriority(TranslationPriority.LOW);
				if (queue.putIfAbsent(pending.hash(), retry) == null) {
					queuedAt.put(pending.hash(), System.currentTimeMillis());
					index(retry);
				}
			}
		}
		if (abandoned) {
			failurePolicy.recordTransportFailure();
		}
	}

	private void onSuccess(List<QueuedTranslation> batch, List<String> results) {
		Set<String> ready = new HashSet<>();
		boolean anyTranslated = false;
		for (int i = 0; i < batch.size(); i++) {
			QueuedTranslation pending = batch.get(i);
			release(pending);
			String translated = i < results.size() ? results.get(i) : null;
			if (pending.label() != null && pending.label().startsWith("diagnostic:")) {
				LOGGER.info("[trace:{}:6-result] source='{}' success={} result='{}'", pending.label(),
						abbreviate(pending.text()), translated != null && !translated.isBlank(), abbreviate(translated));
			}
			if (translated == null || translated.isBlank() || !PlaceholderUtil.validate(pending.text(), translated)) {
				// The API answered, the answer was just unusable for this text.
				fail(pending.hash(), false);
				// A segment that came back unusable keeps the original text inside the
				// joined result instead of blocking the whole long text.
				completeSegment(pending.hash(), pending.text());
				continue;
			}
			if (AnswerQuality.isEcho(pending.text(), translated, detector.isMostlyTargetLanguage(pending.text()))) {
				// The model repeated the input instead of translating it. Storing that
				// answer would freeze the text in English forever, because the next look-up
				// is a cache hit and the text is never asked again - the fifteenth
				// feedback round's "the team suffix is not translated (again)" report:
				// the cache held "the Brave" -> "the Brave" while the prefix next to it
				// was translated. Treated like an unusable answer (retried after the
				// answer-failure delay) and deliberately not cached.
				LOGGER.info("[{}] answer repeats the input, not cached: \"{}\"",
						logsSource(pending.label()) ? pending.label() : "translate", abbreviate(pending.text()));
				fail(pending.hash(), false);
				completeSegment(pending.hash(), pending.text());
				continue;
			}
			if (AnswerQuality.hasClearlyWrongScript(translated, config.targetLang)) {
				LOGGER.warn("[{}] answer uses the wrong script for {}, not cached: \"{}\"",
						logsSource(pending.label()) ? pending.label() : "translate", config.targetLang,
						abbreviate(translated));
				fail(pending.hash(), false);
				completeSegment(pending.hash(), pending.text());
				continue;
			}
			if (cacheManager != null) {
				cacheManager.current().put(pending.text(), translated.trim(),
						config.sourceLang, config.targetLang, pending.textType().name());
			}
			translatedCount++;
			changedTypes.add(pending.textType());
			anyTranslated = true;
			if (logsSource(pending.label())) {
				if (pending.label().startsWith("diagnostic:")) {
					LOGGER.info("[{}] translation returned source='{}' result='{}'", pending.label(),
							abbreviate(pending.text()), abbreviate(translated.trim()));
				} else {
					LOGGER.info("[{}] translated ({} chars -> {} chars)", pending.label(), pending.text().length(),
							translated.trim().length());
				}
			}
			if (pending.isSegment()) {
				completeSegment(pending.hash(), translated.trim());
			} else {
				ready.add(pending.hash());
			}
		}
		// The request came back: the API is up, whatever else happened.
		failurePolicy.recordSuccess();
		if (anyTranslated) {
			ready.forEach(singleRetried::remove);
			// New translations are available: every render cache (chat lines, sign
			// lines, book pages, text display lines, dialog labels) rebuilds itself
			// on the next frame.
			TranslationSupport.bumpGeneration();
			// Caches that do not compare the generation per frame (the chat line
			// cache) and screens with their own text caches need an explicit nudge.
			// The call only sets a flag - the actual rebuild is coalesced to at most
			// one per tick, ten per second (see RefreshCoordinator).
			notifyInvalidator();
		}
		notifyWaiters(ready);
	}

	private void notifyInvalidator() {
		try {
			invalidator.run();
		} catch (RuntimeException e) {
			LOGGER.warn("Invalidator failed", e);
		}
	}

	/**
	 * Marks a text as failed and remembers <em>why</em>, because the retry delay
	 * differs: a transport failure is retried after seconds (the server may just be
	 * busy), an unusable answer after a minute (asking the same model again that
	 * fast rarely helps).
	 */
	private void fail(String hash, boolean transport) {
		failed.put(hash, System.currentTimeMillis());
		if (transport) {
			failedTransport.add(hash);
		} else {
			failedTransport.remove(hash);
		}
	}

	private void onFailure(List<QueuedTranslation> batch, Throwable error) {
		Throwable cause = rootCause(error);
		boolean transport = !(cause instanceof ModelAnswerException);
		boolean permanent = cause instanceof TranslationTransportException t && t.permanent();
		if (transport) {
			failurePolicy.recordTransportFailure();
		} else {
			failurePolicy.recordAnswerFailure();
		}
		Set<String> done = new HashSet<>();
		for (QueuedTranslation pending : batch) {
			release(pending);
			if (logsSource(pending.label())) {
				// Which page failed and why - the diagnostic the eleventh feedback
				// round asked for ("页面索引、内容摘要、失败原因").
				LOGGER.warn("[{}] failed: {} ({} chars, \"{}\")", pending.label(), rootMessage(error),
						pending.text().length(), abbreviate(pending.text()));
			}
			if (transport) {
				// Transport failure: the text keeps its original for now and is
				// retried after a short delay (TRANSPORT_RETRY_MS) - a busy or
				// restarted server must not leave the text untranslated forever, but
				// it must not be hammered either. Segments rejoin their long text
				// straight away, so a failed part never blocks the page.
				fail(pending.hash(), true);
				if (pending.isSegment()) {
					completeSegment(pending.hash(), pending.text());
				} else {
					done.add(pending.hash());
				}
				continue;
			}
			// A batch is retried text by text before giving up: models occasionally
			// answer a large batch with the wrong number of elements, and dropping
			// the whole batch would leave those texts in English forever.
			if (singleRetried.add(pending.hash())) {
				retrySingles.add(pending);
				// Stay marked as pending while waiting for the single retry, so the
				// render path cannot queue the same text as a normal batch again
				// (that would re-run the very request that just failed).
				inFlight.add(pending.hash());
				continue;
			}
			fail(pending.hash(), false);
			if (pending.isSegment()) {
				completeSegment(pending.hash(), pending.text());
			} else {
				done.add(pending.hash());
			}
		}
		if (!done.isEmpty()) {
			notifyWaiters(done);
		}
		// Only a run of transport failures means the API is down; a single slow
		// request (a long book page, for instance) must not switch the mod off. A
		// clearly wrong configuration surfaces after two failures instead of five,
		// because it will never fix itself.
		int needed = permanent ? Math.min(2, Math.max(1, config.failureThreshold))
				: Math.max(1, config.failureThreshold);
		if (transport && failurePolicy.shouldNotify(needed)) {
			failureNotifier.accept(lastErrorText, failurePolicy.consecutiveFailures());
		}
		if (!retrySingles.isEmpty()) {
			scheduleFlush();
		}
	}

	private void release(QueuedTranslation pending) {
		inFlight.remove(pending.hash());
	}

	private static String rootMessage(Throwable error) {
		Throwable cause = rootCause(error);
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private static Throwable rootCause(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause
				&& !(cause instanceof ModelAnswerException)
				&& !(cause instanceof TranslationTransportException)) {
			cause = cause.getCause();
		}
		return cause;
	}

	/**
	 * Runs the registered callbacks on the client thread. Callbacks are executed
	 * even on failure so that callers can stop tracking the text.
	 */
	private void notifyWaiters(Set<String> hashes) {
		Minecraft client = Minecraft.getInstance();
		for (String hash : hashes) {
			List<Runnable> callbacks = waiters.remove(hash);
			if (callbacks == null || callbacks.isEmpty()) {
				continue;
			}
			if (client == null) {
				for (Runnable callback : callbacks) {
					try {
						callback.run();
					} catch (RuntimeException e) {
						LOGGER.warn("Translation callback failed", e);
					}
				}
				continue;
			}
			client.execute(() -> {
				for (Runnable callback : callbacks) {
					try {
						callback.run();
					} catch (RuntimeException e) {
						LOGGER.warn("Translation callback failed", e);
					}
				}
			});
		}
	}

	// -------------------------------------------------------------- focus

	/**
	 * The player's focus moved (screen opened/closed, crosshair target changed):
	 * queued texts that belong to the old focus drop to LOW so they cannot delay
	 * what the player is looking at now (tenth feedback round).
	 * <p>
	 * Only the texts that really left the focus are touched. A crosshair that moves
	 * while a book is open must not demote the book page the player is reading;
	 * opening a screen must not demote the HUD; closing it must not demote the
	 * world text that is visible again.
	 */
	public void onFocusChanged(boolean screenChanged, boolean crosshairChanged) {
		boolean screenOpen = FocusTracker.screenOpen();
		int demoted = 0;
		for (Map.Entry<String, QueuedTranslation> entry : queue.entrySet()) {
			QueuedTranslation pending = entry.getValue();
			if (pending.isSegment() || pending.priority() == TranslationPriority.LOW) {
				continue;
			}
			if (!leftTheFocus(pending.textType(), screenChanged, crosshairChanged, screenOpen)) {
				continue;
			}
			QueuedTranslation demotedPending = pending.withPriority(TranslationPriority.LOW);
			if (queue.replace(entry.getKey(), pending, demotedPending)) {
				index(demotedPending);
				demoted++;
			}
		}
		if (demoted > 0) {
			LOGGER.debug("Focus changed: {} queued text(s) demoted to background", demoted);
		}
	}

	/** True when this queued text belonged to the focus the player just left. */
	private static boolean leftTheFocus(TextType type, boolean screenChanged, boolean crosshairChanged,
			boolean screenOpen) {
		boolean worldText = switch (type) {
			case SIGN, ENTITY_NAME, TEXT_DISPLAY -> true;
			default -> false;
		};
		if (screenChanged) {
			// The screen took the focus: what is behind it is no longer what the
			// player sees. The screen closed: its own sources are gone.
			return screenOpen ? worldText : !worldText;
		}
		// Only the crosshair moved: the world text it was pointing at lost its
		// promotion, everything on screen keeps its own priority.
		return crosshairChanged && worldText;
	}

	/** Called every client tick: keeps the player-name blacklist fresh. */
	public void onClientTick() {
		Minecraft client = Minecraft.getInstance();
		Set<String> names = new HashSet<>();
		if (client != null && client.player != null) {
			names.add(client.player.getGameProfile().name());
		}
		if (client != null && client.getConnection() != null) {
			client.getConnection().getOnlinePlayers().forEach(info -> names.add(info.getProfile().name()));
		}
		detector.setPlayerNames(names);
		long now = System.currentTimeMillis();
		if (now - lastStalePurge >= 5_000L) {
			lastStalePurge = now;
			for (Map.Entry<String, QueuedTranslation> entry : queue.entrySet()) {
				Long since = queuedAt.get(entry.getKey());
				if (entry.getValue().priority() == TranslationPriority.LOW && since != null
						&& now - since > LOW_PRIORITY_TTL_MS && queue.remove(entry.getKey(), entry.getValue())) {
					queuedAt.remove(entry.getKey());
				}
			}
		}
	}

	public long requestCount() {
		return requestCount;
	}

	/**
	 * Drops the in-memory translations (and the pending queue) so the next render
	 * asks the API again. The persistent per-world cache is not touched; the manual
	 * refresh key uses this when "clear the memory cache when refreshing" is on.
	 */
	public int clearMemoryCache() {
		int dropped = 0;
		queue.clear();
		clearQueueIndexes();
		failed.clear();
		singleRetried.clear();
		retrySingles.clear();
		segments.clear();
		partOwner.clear();
		if (cacheManager != null) {
			dropped += cacheManager.clearMemory();
		}
		return dropped;
	}

	public long translatedCount() {
		return translatedCount;
	}

	public long failureCount() {
		return failureCount;
	}

	public String lastError() {
		return lastErrorText;
	}

	public int pendingCount() {
		return queue.size() + inFlight.size() + segments.size();
	}

	/** Consecutive transport failures - the counter that decides when to warn. */
	public int consecutiveFailures() {
		return failurePolicy.consecutiveFailures();
	}

	public void shutdown() {
		timer.shutdownNow();
	}

	// ------------------------------------------------------- performance

	/** Requests (batches) sent so far. */
	public long batchCount() {
		return batches.get();
	}

	/** Average round trip time of a translation request in milliseconds. */
	public long averageLatencyMs() {
		long count = latencyCount.get();
		return count == 0 ? 0 : latencySumMs.get() / count;
	}

	public int inFlightCount() {
		return inFlight.size();
	}

	/** Queued entries per priority level, for the debug log and the self test. */
	public String queueBreakdown() {
		int high = 0;
		int normal = 0;
		int low = 0;
		for (QueuedTranslation pending : queue.values()) {
			switch (pending.priority()) {
				case HIGH -> high++;
				case NORMAL -> normal++;
				case LOW -> low++;
			}
		}
		return "高 " + high + " / 中 " + normal + " / 低 " + low;
	}

	/** One line summary used by {@code /aitranslate perf} and the periodic log. */
	public String performanceSummary() {
		return "请求 " + requestCount + " 次 / 批次 " + batches.get() + " / 成功 " + translatedCount + " 条 / 失败 "
				+ failureCount + " 条 / 平均延迟 " + averageLatencyMs() + " ms / 进行中 " + inFlightCount()
				+ (inWarmUp() ? " / 入场预热中（已延后 " + heldBackCount.get() + " 次）" : "")
				+ " / 排队 " + queue.size() + "（" + queueBreakdown() + "）"
				+ " / 连续失败 " + failurePolicy.consecutiveFailures();
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() <= 160 ? text : text.substring(0, 160) + "…";
	}

	/** Token bucket: allows a burst equal to the configured rate. */
	private final class RateLimiter {
		private long windowStart;
		private int used;

		synchronized boolean tryAcquire() {
			// Reduced during the post-join warm-up ramp (sixth feedback round).
			int limit = currentRateLimit();
			long now = System.currentTimeMillis();
			if (now - windowStart >= 1000L) {
				windowStart = now;
				used = 0;
			}
			if (used < limit) {
				used++;
				return true;
			}
			return false;
		}

		synchronized long millisUntilNextSlot() {
			long elapsed = System.currentTimeMillis() - windowStart;
			return Math.max(50L, 1000L - elapsed);
		}
	}

	/** A batch that was handed to the provider and has not answered yet. */
	private record InFlightBatch(List<QueuedTranslation> batch, long startedAt) {
	}

	/**
	 * The segments of one long text. Thread safe: segments come back from the
	 * provider's worker threads.
	 */
	private static final class SegmentGroup {
		private final TextType type;
		private final String originalText;
		private final String originalHash;
		private final List<String> parts;
		private final String[] results;
		private final String[] partHashes;
		private int completed;
		private int translatedParts;

		SegmentGroup(TextType type, String originalText, String originalHash, List<String> parts) {
			this.type = type;
			this.originalText = originalText;
			this.originalHash = originalHash;
			this.parts = parts;
			this.results = new String[parts.size()];
			this.partHashes = new String[parts.size()];
		}

		TextType type() {
			return type;
		}

		String originalText() {
			return originalText;
		}

		String originalHash() {
			return originalHash;
		}

		List<String> parts() {
			return parts;
		}

		String[] partHashes() {
			return partHashes;
		}

		synchronized void accept(int index, String result) {
			if (index < 0 || index >= results.length || results[index] != null || result == null) {
				return;
			}
			results[index] = result;
			completed++;
			if (!result.equals(parts.get(index))) {
				translatedParts++;
			}
		}

		synchronized int completedCount() {
			return completed;
		}

		synchronized boolean isComplete() {
			return completed >= parts.size();
		}

		synchronized boolean anyTranslated() {
			return translatedParts > 0;
		}

		synchronized int indexOf(String partHash) {
			for (int i = 0; i < partHashes.length; i++) {
				if (partHash.equals(partHashes[i])) {
					return i;
				}
			}
			return -1;
		}

		String joined() {
			List<String> values = new ArrayList<>(parts.size());
			synchronized (this) {
				for (int i = 0; i < parts.size(); i++) {
					values.add(results[i]);
				}
			}
			return LongText.join(parts, values);
		}
	}
}
