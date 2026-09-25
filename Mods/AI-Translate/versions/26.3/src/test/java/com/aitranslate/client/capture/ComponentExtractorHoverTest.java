package com.aitranslate.client.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Hover events ({@code hoverEvent} in a component style).
 * <p>
 * A hover tooltip is not part of the text tree: it lives in the style of a node,
 * so the walker has to look there as well. These tests pin that behaviour down,
 * including the deliberate decision to leave click events alone (their payload is
 * a command or a URL - translating it would break the click instead of
 * translating a visible text).
 */
class ComponentExtractorHoverTest {

	private static Component withHover(Component node, Component hoverText) {
		return node.copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hoverText)));
	}

	@Test
	void hoverTextIsExtracted() {
		Component message = withHover(Component.literal("Read the inscription"),
				Component.literal("Sealed by the Order of Dawn"));

		List<String> texts = ComponentExtractor.uniqueTexts(message);

		assertEquals(List.of("Read the inscription", "Sealed by the Order of Dawn"), texts);
	}

	@Test
	void hoverTextOfAMultiPartTooltipIsExtracted() {
		Component hover = Component.literal("Only the brave")
				.append(Component.literal(" go north").withStyle(Style.EMPTY.withColor(0xFFAA00)));
		Component message = withHover(Component.literal("Check the map"), hover);

		List<String> texts = ComponentExtractor.uniqueTexts(message);

		// Keys carry no surrounding whitespace since the thirteenth round (it is put
		// back around the translation at render time), so the styled part is keyed as
		// "go north" and not as " go north".
		assertEquals(List.of("Check the map", "Only the brave", "go north"), texts);
	}

	@Test
	void nestedHoverTextIsExtracted() {
		Component inner = withHover(Component.literal("inner"), Component.literal("deepest"));
		Component message = withHover(Component.literal("outer"), inner);

		List<String> texts = ComponentExtractor.uniqueTexts(message);

		assertEquals(List.of("outer", "inner", "deepest"), texts);
	}

	@Test
	void hoverTextIsRebuiltWithTheTranslation() {
		Component message = withHover(Component.literal("Read the inscription"),
				Component.literal("Sealed by the Order of Dawn"));

		Component rebuilt = ComponentExtractor.rebuild(message,
				Map.of("Read the inscription", "阅读铭文", "Sealed by the Order of Dawn", "黎明教团所封"));

		assertEquals("阅读铭文", rebuilt.getString());
		HoverEvent hover = rebuilt.getStyle().getHoverEvent();
		assertTrue(hover instanceof HoverEvent.ShowText);
		assertEquals("黎明教团所封", ((HoverEvent.ShowText) hover).value().getString());
	}

	@Test
	void rebuildingDoesNotTouchTheOriginal() {
		Component originalHover = Component.literal("Sealed by the Order of Dawn");
		Component message = withHover(Component.literal("Read the inscription"), originalHover);

		Component rebuilt = ComponentExtractor.rebuild(message,
				Map.of("Read the inscription", "阅读铭文", "Sealed by the Order of Dawn", "黎明教团所封"));

		assertNotSame(message, rebuilt);
		assertEquals("Read the inscription", message.getString());
		assertEquals("Sealed by the Order of Dawn",
				((HoverEvent.ShowText) message.getStyle().getHoverEvent()).value().getString());
	}

	@Test
	void aTranslationOnlyInsideTheHoverStillCounts() {
		Component message = withHover(Component.literal("Read the inscription"),
				Component.literal("Sealed by the Order of Dawn"));

		assertTrue(ComponentExtractor.hasTranslation(message, Map.of("Sealed by the Order of Dawn", "黎明教团所封")));
		assertFalse(ComponentExtractor.hasTranslation(message, Map.of("Something else", "无关")));
	}

	@Test
	void clickEventsAreNotTranslated() {
		Component message = Component.literal("Run it").withStyle(style -> style
				.withClickEvent(new ClickEvent.RunCommand("/say hello")));

		assertEquals(List.of("Run it"), ComponentExtractor.uniqueTexts(message));
		Component rebuilt = ComponentExtractor.rebuild(message, Map.of("Run it", "执行"));
		assertTrue(rebuilt.getStyle().getClickEvent() instanceof ClickEvent.RunCommand);
		assertEquals("/say hello", ((ClickEvent.RunCommand) rebuilt.getStyle().getClickEvent()).command());
	}

	@Test
	void hoverOfASiblingIsExtractedToo() {
		Component message = Component.literal("first")
				.append(withHover(Component.literal("second"), Component.literal("hover of second")));

		assertEquals(List.of("first", "second", "hover of second"), ComponentExtractor.uniqueTexts(message));
	}

	@Test
	void aNodeWithoutHoverKeepsItsStyle() {
		Style style = Style.EMPTY.withColor(0xFF0000);
		Component message = Component.literal("plain").withStyle(style);

		Component rebuilt = ComponentExtractor.rebuild(message, Map.of("plain", "纯文本"));

		assertSame(style, rebuilt.getStyle());
	}
}
