package com.aitranslate.client.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

/**
 * Access to the chat line cache rebuild.
 * <p>
 * {@code rescaleChat()} would work too, but it also calls
 * {@code resetChatScroll()} - refreshing the chat after every finished
 * translation would then yank the view back to the newest line while the player
 * is reading history. The private {@code refreshTrimmedMessages()} rebuilds only
 * the derived lines, which is exactly what the translation needs.
 * <p>
 * The {@code trimmedMessages} accessor exists for the self test only: reading the
 * built lines is the one piece of direct evidence for the ninth feedback round
 * ("turning the master switch off must put the original text back into the chat"),
 * because the rendered result cannot be asserted from inside the game.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
	@Invoker("refreshTrimmedMessages")
	void aiTranslate$refreshTrimmedMessages();

	@Invoker("getLinesPerPage")
	int aiTranslate$getLinesPerPage();

	@Accessor("chatScrollbarPos")
	int aiTranslate$getChatScrollbarPos();

	@Accessor("chatScrollbarPos")
	void aiTranslate$setChatScrollbarPos(int position);

	@Accessor("newMessageSinceScroll")
	boolean aiTranslate$getNewMessageSinceScroll();

	@Accessor("newMessageSinceScroll")
	void aiTranslate$setNewMessageSinceScroll(boolean value);

	@Accessor("trimmedMessages")
	List<GuiMessage.Line> aiTranslate$trimmedMessages();
}
