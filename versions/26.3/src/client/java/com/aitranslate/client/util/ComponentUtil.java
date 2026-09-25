package com.aitranslate.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

/**
 * Read-only helpers around {@link Component}.
 * <p>
 * Everything here works on copies - the original component tree handed in by
 * the game is never modified.
 */
public final class ComponentUtil {
	private ComponentUtil() {
	}

	/** True when this component node is a hardcoded {@code literal} leaf. */
	public static boolean isLiteral(Component component) {
		return component != null && component.getContents() instanceof PlainTextContents;
	}

	public static String literalText(Component component) {
		if (component != null && component.getContents() instanceof PlainTextContents plain) {
			return plain.text();
		}
		return null;
	}

	/** Flattens the tree to its literal leaves (translatable/score/... nodes yield nothing). */
	public static List<Component> literalLeaves(Component root) {
		List<Component> leaves = new ArrayList<>();
		collect(root, leaves, 0);
		return leaves;
	}

	private static void collect(Component node, List<Component> out, int depth) {
		if (node == null || depth > 32) {
			return;
		}
		if (node.getContents() instanceof PlainTextContents) {
			out.add(node);
		}
		for (Component sibling : node.getSiblings()) {
			collect(sibling, out, depth + 1);
		}
	}

	/** Plain text of a component, never {@code null}. */
	public static String plainText(Component component) {
		return component == null ? "" : component.getString();
	}

	public static Component emptyIfNull(Component component) {
		return component == null ? Component.empty() : component;
	}

	/** Applies a style patch to the root of a copy of {@code component}. */
	public static MutableComponent restyle(Component component, java.util.function.UnaryOperator<Style> operator) {
		MutableComponent copy = component.copy();
		copy.setStyle(operator.apply(copy.getStyle()));
		return copy;
	}
}
