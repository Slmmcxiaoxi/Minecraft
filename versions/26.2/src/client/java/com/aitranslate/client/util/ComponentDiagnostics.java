package com.aitranslate.client.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Compact, bounded Component tree diagnostics for stubborn render-path bugs. */
public final class ComponentDiagnostics {
	private ComponentDiagnostics() { }

	public static String describe(Component root) {
		StringBuilder out = new StringBuilder();
		append(root, out, 0, "root");
		return out.toString();
	}

	private static void append(Component node, StringBuilder out, int depth, String position) {
		if (node == null || depth > 16 || out.length() > 6000) return;
		if (!out.isEmpty()) out.append(" | ");
		var contents = node.getContents();
		out.append(position).append(':').append(contents.getClass().getSimpleName());
		if (contents instanceof PlainTextContents plain) out.append(" literal=").append(quote(plain.text()));
		if (contents instanceof TranslatableContents translatable) {
			out.append(" key=").append(translatable.getKey());
			Object[] args = translatable.getArgs();
			for (int i = 0; args != null && i < args.length; i++) {
				Object arg = args[i];
				if (arg instanceof Component component) append(component, out, depth + 1, position + ".arg" + i);
				else out.append(" | ").append(position).append(".arg").append(i).append(':')
						.append(arg == null ? "null" : arg.getClass().getSimpleName()).append('=').append(quote(String.valueOf(arg)));
			}
		}
		for (int i = 0; i < node.getSiblings().size(); i++) append(node.getSiblings().get(i), out, depth + 1,
				position + ".sibling" + i);
	}

	public static boolean containsTranslationKey(Component root, String key) {
		return containsTranslationKey(root, key, 0);
	}

	private static boolean containsTranslationKey(Component node, String key, int depth) {
		if (node == null || depth > 32) return false;
		if (node.getContents() instanceof TranslatableContents value) {
			if (key.equals(value.getKey())) return true;
			for (Object arg : value.getArgs()) {
				if (arg instanceof Component component && containsTranslationKey(component, key, depth + 1)) return true;
			}
		}
		for (Component sibling : node.getSiblings()) {
			if (containsTranslationKey(sibling, key, depth + 1)) return true;
		}
		return false;
	}

	private static String quote(String value) {
		String clean = value == null ? "" : value.replace("\n", "\\n").replace("\r", "\\r");
		if (clean.length() > 180) clean = clean.substring(0, 180) + "…";
		return "'" + clean + "'";
	}
}
