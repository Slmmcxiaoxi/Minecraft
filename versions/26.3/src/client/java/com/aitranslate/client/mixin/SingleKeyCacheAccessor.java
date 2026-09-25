package com.aitranslate.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.util.SingleKeyCache;

/**
 * Exposes the internals of {@link SingleKeyCache} so a cached label can be
 * invalidated.
 * <p>
 * {@code MultiLineTextWidget} caches its {@code MultiLineLabel} behind a single
 * key (message + size). Re-setting the same message would produce an equal key
 * and therefore no rebuild, so the cached key is dropped instead - the next
 * lookup recomputes the label, which runs it through the translation hook again.
 * Used for dialog bodies, which would otherwise keep showing the text they were
 * first built with.
 */
@Mixin(SingleKeyCache.class)
public interface SingleKeyCacheAccessor {
	@Accessor("cacheKey")
	void aiTranslate$setCacheKey(Object key);

	@Accessor("cachedValue")
	void aiTranslate$setCachedValue(Object value);
}
