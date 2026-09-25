package com.aitranslate.client.capture;

/**
 * Every text source this mod can translate.
 * <p>
 * The enum name is also used as the per-source cache file name
 * ({@code cache/<context>/<fileName>.json}) and as the key of the per-source
 * enable switches / display-mode overrides in the config.
 */
public enum TextType {
	CHAT("chat", "ai_translate.source.chat"),
	TOOLTIP("tooltip", "ai_translate.source.tooltip"),
	ITEM("item", "ai_translate.source.item"),
	SIGN("sign", "ai_translate.source.sign"),
	BOOK("book", "ai_translate.source.book"),
	CONTAINER("container", "ai_translate.source.container"),
	TITLE("title", "ai_translate.source.title"),
	/**
	 * The action bar has its own cache file since the fifteenth feedback round.
	 * <p>
	 * It used to share {@code title.json} with {@link #TITLE}: both text sources are
	 * written to their own file, and the second write overwrote the first, so the older
	 * of the two was silently missing from the cache on disk - which the new export
	 * probe caught as "92 entries in memory, 90 in the file" (the self test scene has
	 * exactly one title and one action bar line). Entries of a cache written by an older
	 * build still load correctly: they carry their own {@code textType} string, and the
	 * loader reads every file of the bucket.
	 */
	ACTION_BAR("action_bar", "ai_translate.source.action_bar"),
	BOSS_BAR("bossbar", "ai_translate.source.boss_bar"),
	SCOREBOARD("scoreboard", "ai_translate.source.scoreboard"),
	ENTITY_NAME("entity_name", "ai_translate.source.entity_name"),
	DIALOG("dialog", "ai_translate.source.dialog"),
	TEXT_DISPLAY("text_display", "ai_translate.source.text_display"),
	ADVANCEMENT("advancement", "ai_translate.source.advancement"),
	/** Player list (Tab): custom display names, team prefix/suffix, header/footer. */
	PLAYER_LIST("player_list", "ai_translate.source.player_list");

	private final String fileName;
	private final String translationKey;

	TextType(String fileName, String translationKey) {
		this.fileName = fileName;
		this.translationKey = translationKey;
	}

	/** Cache file (without extension) used for this text source. */
	public String fileName() {
		return fileName;
	}

	public String translationKey() {
		return translationKey;
	}
}
