import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline mixin validator (JDK class-file API based).
 *
 * Validates, against the real Minecraft classes:
 *   1. @Mixin target exists and its kind (class / interface) matches the mixin declaration
 *   2. every "method = ..." descriptor exists in the target or one of its supertypes
 *   3. the handler's static-ness matches the target method's static-ness
 *   4. every @At target member exists in its owner or a supertype
 *   5. every @Redirect target instruction is really present inside the named method
 *      (this is the check that catches "right member, wrong enclosing method")
 *   6. every @Invoker/@Accessor member exists
 *
 * Usage: java MixinValidator <minecraftJar> <srcDir> [extraJar ...]
 *
 * Extra jars are searched when a class is not found in the Minecraft jar: the mod mixes
 * into Cloth Config as well (the config list dispatch fix of the seventeenth round), and
 * those classes live in the Cloth jar rather than in the game.
 */
public final class MixinValidator {
	private static String jarPath;
	private static ZipFile zip;
	/** Additional jars searched for classes that are not in the game jar (Cloth). */
	private static final List<ZipFile> EXTRA_JARS = new ArrayList<>();
	private static final Map<String, ClassModel> MODELS = new HashMap<>();
	private static final List<String> problems = new ArrayList<>();
	/** Informational findings (never fail the run). */
	private static final List<String> notes = new ArrayList<>();
	private static int checks = 0;

	private record MethodInfo(String name, String desc, boolean isStatic) {
	}

	public static void main(String[] args) throws Exception {
		jarPath = args[0];
		Path srcDir = Path.of(args[1]);
		for (int i = 2; i < args.length; i++) {
			try {
				EXTRA_JARS.add(new ZipFile(args[i]));
				System.out.println("extra jar     : " + args[i]);
			} catch (IOException e) {
				System.out.println("extra jar     : " + args[i] + " (not readable, skipped)");
			}
		}
		try (ZipFile ignored = zip = new ZipFile(jarPath)) {
			List<Path> mixins;
			try (Stream<Path> walk = Files.walk(srcDir)) {
				mixins = walk.filter(p -> p.toString().endsWith(".java"))
						.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("mixin"))
						.sorted().collect(Collectors.toList());
			}
			System.out.println("minecraft jar : " + jarPath);
			System.out.println("mixins found  : " + mixins.size());
			for (Path mixin : mixins) {
				validate(mixin);
			}
			checkMixinConfig(srcDir, mixins);
			System.out.println();
			System.out.println("checks        : " + checks);
			if (problems.isEmpty()) {
				System.out.println("RESULT        : OK - every mixin target, handler and instruction resolved");
			} else {
				System.out.println("RESULT        : " + problems.size() + " PROBLEM(S)");
				problems.forEach(p -> System.out.println("  ! " + p));
			}
			if (!notes.isEmpty()) {
				System.out.println();
				System.out.println("INFO          : " + notes.size() + " multi-call-site redirect(s)");
				notes.forEach(n -> System.out.println("  i " + n));
			}
		} finally {
			for (ZipFile extra : EXTRA_JARS) {
				extra.close();
			}
		}
	}

	/**
	 * Every class in the mixin package has to be registered in the mixin config
	 * ({@code src/client/resources/ai_translate.client.mixins.json}).
	 * <p>
	 * A missing entry is not a compile error and not a mixin error either: the
	 * class simply never gets transformed, and the game crashes the first time the
	 * code casts an instance to it ("Illegal classload request for accessor mixin
	 * ..."). That happened while adding the chat/refresh accessors, so it is checked
	 * here from now on.
	 */
	private static void checkMixinConfig(Path srcDir, List<Path> mixins) throws IOException {
		Path config = null;
		for (Path dir = srcDir.toAbsolutePath(); dir != null && config == null; dir = dir.getParent()) {
			Path candidate = dir.resolve("resources").resolve("ai_translate.client.mixins.json");
			if (Files.isRegularFile(candidate)) {
				config = candidate;
			}
		}
		if (config == null) {
			notes.add("mixin config not found next to " + srcDir + " - registration not checked");
			return;
		}
		String json = Files.readString(config, StandardCharsets.UTF_8);
		Matcher array = Pattern.compile("\"client\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
		Set<String> registered = new HashSet<>();
		if (array.find()) {
			Matcher entry = Pattern.compile("\"([A-Za-z0-9_$]+)\"").matcher(array.group(1));
			while (entry.find()) {
				registered.add(entry.group(1));
			}
		}
		for (Path mixin : mixins) {
			String simple = mixin.getFileName().toString().replace(".java", "");
			checks++;
			if (!registered.contains(simple)) {
				problems.add(simple + ": not registered in " + config.getFileName()
						+ " (the class would never be transformed)");
			}
		}
	}

	private static void validate(Path file) throws IOException {
		String source = Files.readString(file, StandardCharsets.UTF_8);
		String simple = file.getFileName().toString().replace(".java", "");

		Matcher mixinMatcher = Pattern.compile("@Mixin\\(([A-Za-z0-9_$.]+)\\.class\\)").matcher(source);
		if (!mixinMatcher.find()) {
			problems.add(simple + ": no @Mixin(...class) annotation");
			return;
		}
		String target = resolve(mixinMatcher.group(1), source);
		if (target == null) {
			problems.add(simple + ": cannot resolve @Mixin target");
			return;
		}
		ClassModel targetModel = model(target);
		if (targetModel == null) {
			problems.add(simple + ": target class " + target + " not found in jar");
			return;
		}

		boolean mixinIsInterface = Pattern.compile("\\binterface\\s+" + simple + "\\b").matcher(source).find();
		boolean targetIsInterface = targetModel.flags().has(AccessFlag.INTERFACE);
		boolean accessorOnly = (source.contains("@Invoker(") || source.contains("@Accessor("))
				&& !source.contains("@Inject(") && !source.contains("@Redirect(")
				&& !source.contains("@ModifyVariable(") && !source.contains("@ModifyArg(")
				&& !source.contains("@ModifyReturnValue(");
		checks++;
		if (mixinIsInterface != targetIsInterface && !(mixinIsInterface && accessorOnly)) {
			problems.add(simple + ": declared as " + (mixinIsInterface ? "interface" : "class")
					+ " but target " + target + " is "
					+ (targetIsInterface ? "an interface (mixin must be an interface)" : "a class (mixin must be a class)"));
		}

		// Mixin merges ordinary static methods into the target and requires them to be
		// private (unless an explicit Mixin annotation gives them different semantics).
		// A package-private helper compiled normally but made the complete mixin fail at
		// game startup with "contains non-private static method". Catch that here.
		Matcher staticHelperMatcher = Pattern.compile(
				"(?m)^\\s*(?!private\\b)(?:(?:public|protected)\\s+)?static\\s+[A-Za-z0-9_<>?,.\\[\\] ]+\\s+([A-Za-z0-9_$]+)\\s*\\(")
				.matcher(source);
		while (staticHelperMatcher.find()) {
			int from = Math.max(0, staticHelperMatcher.start() - 240);
			String before = source.substring(from, staticHelperMatcher.start());
			if (before.contains("@Shadow") || before.contains("@Unique") || before.contains("@Accessor")
					|| before.contains("@Invoker")) {
				continue;
			}
			checks++;
			problems.add(simple + ": static helper '" + staticHelperMatcher.group(1)
					+ "' is not private - Mixin rejects ordinary non-private static methods");
		}

		// A helper method of the mixin whose name exists in the target is treated by Mixin
		// as an *overwrite*, and reducing the visibility fails at runtime with
		// "PRIVATE overwrite method X cannot reduce visibility of PUBLIC target method".
		// That happened with a helper called children() in ClothConfigListMixin (the target's
		// children() is public), which the round 17 verification log caught - the mixin was
		// simply not applied. Helpers therefore have to be named differently (the mod uses the
		// aiTranslate$ prefix).
		Matcher helperMatcher = Pattern.compile("(?m)^\\s*private\\s+(?!static\\b)[A-Za-z0-9_<>?,.\\[\\] ]+\\s+([A-Za-z0-9_]+)\\s*\\(")
				.matcher(source);
		while (helperMatcher.find()) {
			String name = helperMatcher.group(1);
			// @Shadow members must have the target's name (they are the declared access to a
			// private member), and @Unique helpers are explicitly ours. Both are fine.
			int from = Math.max(0, helperMatcher.start() - 240);
			String before = source.substring(from, helperMatcher.start());
			if (before.contains("@Shadow") || before.contains("@Unique") || before.contains("@Overwrite")) {
				continue;
			}
			checks++;
			boolean nonPrivate = targetModel.methods().stream()
					.anyMatch(m -> m.methodName().stringValue().equals(name)
							&& !m.flags().has(AccessFlag.PRIVATE));
			if (nonPrivate) {
				problems.add(simple + ": private helper '" + name + "' shadows a non-private member of "
						+ target + " - Mixin treats it as an overwrite and rejects the reduced visibility"
						+ " (rename it, e.g. aiTranslate$" + name + ")");
			}
		}

		// method = "..." specs (with the static-ness of the following handler)
		// NOTE: for @Redirect the handler's static-ness must match the *redirected* member,
		// for @Inject/@ModifyVariable it must match the enclosing method.
		Matcher methodMatcher = Pattern.compile("method\\s*=\\s*\"([^\"]+)\"").matcher(source);
		while (methodMatcher.find()) {
			String spec = methodMatcher.group(1);
			boolean isRedirect = isRedirectHandler(source, methodMatcher.start());
			boolean handlerStatic = handlerIsStatic(source, methodMatcher.end());
			checks++;
			Optional<MethodInfo> found = findMethod(target, spec);
			if (found.isEmpty()) {
				problems.add(simple + ": no method '" + spec + "' in " + target + " or its supertypes");
				continue;
			}
			if (!isRedirect && found.get().isStatic() != handlerStatic) {
				problems.add(simple + ": handler static=" + handlerStatic + " but " + target + "#" + spec
						+ " static=" + found.get().isStatic());
			}
			if (!spec.contains("(")) {
				checks++;
				if (countMethodsNamed(target, spec) == 0) {
					problems.add(simple + ": no method named '" + spec + "' in " + target);
				}
			}
		}

		// @Redirect: handler static-ness must match the redirected member
		Matcher redirectStatic = Pattern.compile(
				"@Redirect\\([\\s\\S]{0,600}?target\\s*=\\s*\"L([^;]+);([^\"]+)\"", Pattern.DOTALL).matcher(source);
		while (redirectStatic.find()) {
			String owner = redirectStatic.group(1).replace('/', '.');
			String member = redirectStatic.group(2);
			boolean handlerStatic = handlerIsStatic(source, redirectStatic.end());
			checks++;
			Optional<MethodInfo> targetMember = findMember(owner, member);
			if (targetMember.isPresent() && targetMember.get().isStatic() != handlerStatic) {
				problems.add(simple + ": @Redirect handler static=" + handlerStatic + " but redirected "
						+ owner + "#" + member + " static=" + targetMember.get().isStatic());
			}
		}

		// @At value = "..." must be a specifier Mixin knows. "READ" looked plausible and
		// is not: the runtime fails the whole mixin with "READ is not a valid injection
		// point specifier" (field access is FIELD).
		Matcher atValue = Pattern.compile("@At\\(\\s*value\\s*=\\s*\"([A-Z_]+)\"").matcher(source);
		Set<String> knownSpecifiers = Set.of("HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN", "FIELD",
				"NEW", "JUMP", "CONSTANT", "LOAD", "STORE", "CTOR_HEAD");
		while (atValue.find()) {
			checks++;
			if (!knownSpecifiers.contains(atValue.group(1))) {
				problems.add(simple + ": @At value=\"" + atValue.group(1)
						+ "\" is not a valid injection point specifier (FIELD is the one for field access)");
			}
		}

		// @At target = "Lowner;member"
		Matcher targetMatcher = Pattern.compile("target\\s*=\\s*\"L([^;]+);([^\"]+)\"").matcher(source);
		while (targetMatcher.find()) {
			String owner = targetMatcher.group(1).replace('/', '.');
			String member = targetMatcher.group(2);
			checks++;
			if (model(owner) == null) {
				problems.add(simple + ": @At owner not found -> " + owner);
				continue;
			}
			if (findMember(owner, member).isEmpty()) {
				problems.add(simple + ": @At member not found in " + owner + " or its supertypes -> " + member);
			}
		}

		// @Redirect instruction must be inside the method it declares
		Matcher redirectMatcher = Pattern.compile(
				"@Redirect\\(\\s*method\\s*=\\s*\"([^\"]+)\"\\s*,\\s*at\\s*=\\s*@At\\(\\s*value\\s*=\\s*\"[^\"]*\"\\s*,\\s*target\\s*=\\s*\"L([^;]+);([^\"]+)\"",
				Pattern.DOTALL).matcher(source);
		while (redirectMatcher.find()) {
			String methodSpec = redirectMatcher.group(1);
			String owner = redirectMatcher.group(2).replace('/', '.');
			String member = redirectMatcher.group(3);
			checks++;
			int matches = instructionCount(target, methodSpec, owner, member);
			if (matches == 0) {
				problems.add(simple + ": @Redirect instruction NOT found inside " + target + "#" + methodSpec
						+ " -> " + owner + "#" + member);
			} else if (matches > 1) {
				// Informational: Mixin applies @Redirect to EVERY matching call site
				// (verified in game with the scoreboard/title/container hooks), so this
				// is not an error - but the number is worth knowing, because a hook
				// written for one call site then also fires for the others.
				notes.add(simple + ": @Redirect matches " + matches + " call sites inside " + target + "#"
						+ methodSpec + " -> " + owner + "#" + member);
			}
		}

		// @Invoker / @Accessor
		Matcher invoker = Pattern.compile("@(Invoker|Accessor)\\(\"([^\"]+)\"\\)").matcher(source);
		while (invoker.find()) {
			String member = invoker.group(2);
			checks++;
			if (findMember(target, member).isEmpty()) {
				problems.add(simple + ": @" + invoker.group(1) + " member not found in " + target + " -> " + member);
			}
		}

		// @ModifyVariable without an explicit argsOnly silently targets arguments only
		Matcher modifyVariable = Pattern.compile("@ModifyVariable\\(").matcher(source);
		while (modifyVariable.find()) {
			// the annotation spans several lines and contains ')' inside its strings,
			// so scan up to the handler declaration instead of a matching paren
			int end = Math.min(source.length(), modifyVariable.end() + 900);
			String window = source.substring(modifyVariable.end(), end);
			Matcher handler = Pattern.compile("\\n\\s*(public|protected|private)[^;{]*\\(").matcher(window);
			String annotation = handler.find() ? window.substring(0, handler.start()) : window;
			checks++;
			if (!annotation.contains("argsOnly")) {
				problems.add(simple + ": @ModifyVariable without explicit argsOnly (defaults to true = arguments only): "
						+ annotation.replaceAll("\\s+", " ").trim());
			}
		}

		// @Inject handlers must use the callback type that matches the target's return type:
		// void -> CallbackInfo, anything else -> CallbackInfoReturnable. Getting this wrong
		// only shows up at runtime as "CallbackInfoReturnable is required".
		Matcher inject = Pattern.compile("@Inject\\(").matcher(source);
		while (inject.find()) {
			// The whole annotation has to be skipped: its strings contain ')' and the
			// window would otherwise start inside "@At(...)" and read that as the handler.
			int afterAnnotation = callEnd(source, inject.end() - 1);
			Matcher methodAttribute = Pattern.compile("method\\s*=\\s*\"([^\"]+)\"")
					.matcher(source.substring(inject.start(), afterAnnotation));
			if (!methodAttribute.find()) {
				continue;
			}
			String methodSpec = methodAttribute.group(1);
			checks++;
			Optional<MethodInfo> method = findMethod(target, methodSpec);
			if (method.isEmpty()) {
				continue;
			}
			boolean returnsVoid = method.get().desc().endsWith(")V");
			int windowEnd = Math.min(source.length(), afterAnnotation + 700);
			Matcher handler = Pattern
					.compile("\\n\\s*(public|protected|private)[^;{]*\\(")
					.matcher(source.substring(afterAnnotation, windowEnd));
			if (!handler.find()) {
				continue;
			}
			// The parameter list spans to the closing paren, and the second parameter
			// (the callback) is where the type shows up - so the slice has to cover the
			// whole parameter list, not just its first line.
			int handlerStart = afterAnnotation + handler.start();
			int handlerParen = afterAnnotation + handler.end() - 1;
			String signature = source.substring(handlerStart, callEnd(source, handlerParen));
			boolean usesReturnable = signature.contains("CallbackInfoReturnable");
			if (returnsVoid && usesReturnable) {
				problems.add(simple + ": @Inject into " + target + "#" + methodSpec
						+ " returns void but the handler takes CallbackInfoReturnable");
			}
			if (!returnsVoid && !usesReturnable) {
				problems.add(simple + ": @Inject into " + target + "#" + methodSpec
						+ " returns a value but the handler takes CallbackInfo (CallbackInfoReturnable is required)");
			}
		}

		// @Shadow members must exist in the target.
		// The pattern only consumes annotations between @Shadow and the declaration
		// itself: a wildcard window used to run on into method bodies and report local
		// variables as shadowed members (a false positive that hid the real rule).
		Matcher shadow = Pattern.compile(
				"@Shadow(?:\\s*\\([^)]*\\))?\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"
						+ "([\\w<>,.\\[\\] ?]+?)\\s+([\\w$]+)\\s*[;(=]")
				.matcher(source);
		while (shadow.find()) {
			String member = shadow.group(2);
			checks++;
			if (findMember(target, member).isEmpty()) {
				problems.add(simple + ": @Shadow member not found in " + target + " -> " + member);
			}
		}
	}

	private static boolean instructionPresent(String target, String methodSpec, String owner, String member) {
		return instructionCount(target, methodSpec, owner, member) > 0;
	}

	/**
	 * How many times the instruction appears in the named method. {@code @Redirect}
	 * needs exactly one; more than one makes the mixin fail to apply.
	 */
	private static int instructionCount(String target, String methodSpec, String owner, String member) {
		// A member is either "name(descriptor)" (method/field with a type) or
		// "name:Ltype;" (a plain field, as used by @Redirect value="READ"/"WRITE").
		int paren = member.indexOf('(');
		boolean fieldTarget = paren < 0;
		int colon = member.indexOf(':');
		String name = fieldTarget
				? (colon < 0 ? member : member.substring(0, colon))
				: member.substring(0, paren);
		String desc = fieldTarget ? null : member.substring(paren);
		List<MethodModel> candidates = new ArrayList<>();
		for (MethodModel method : allMethods(target)) {
			if (!method.methodName().stringValue().equals(methodSpec.contains("(")
					? methodSpec.substring(0, methodSpec.indexOf('('))
					: methodSpec)) {
				continue;
			}
			if (methodSpec.contains("(") && !method.methodType().stringValue().equals(methodSpec.substring(methodSpec.indexOf('(')))) {
				continue;
			}
			candidates.add(method);
		}
		int found = 0;
		for (MethodModel method : candidates) {
			Optional<java.lang.classfile.CodeModel> code = method.code();
			if (code.isEmpty()) {
				continue;
			}
			for (CodeElement element : code.get()) {
				if (!fieldTarget && element instanceof InvokeInstruction invoke) {
					if (invoke.owner().asInternalName().replace('/', '.').equals(owner)
							&& invoke.name().stringValue().equals(name)
							&& invoke.type().stringValue().equals(desc)) {
						found++;
					}
				}
				if (fieldTarget && element instanceof FieldInstruction field) {
					if (field.owner().asInternalName().replace('/', '.').equals(owner)
							&& field.name().stringValue().equals(name)) {
						found++;
					}
				}
			}
		}
		return found;
	}

	// ------------------------------------------------------------------ helpers

	/**
	 * Index just past the closing parenthesis of the call whose opening parenthesis is
	 * at {@code openParen}. String literals and escapes are skipped, so an annotation
	 * containing {@code ")"} does not end it early.
	 */
	private static int callEnd(String source, int openParen) {
		int depth = 0;
		boolean inString = false;
		for (int i = openParen; i < source.length(); i++) {
			char c = source.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '"') {
					inString = false;
				}
				continue;
			}
			if (c == '"') {
				inString = true;
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i + 1;
				}
			}
		}
		return Math.min(source.length(), openParen + 900);
	}

	/** True when the annotation starting before {@code annotationStart} is a @Redirect. */
	private static boolean isRedirectHandler(String source, int annotationStart) {
		int from = Math.max(0, annotationStart - 120);
		String window = source.substring(from, annotationStart);
		return window.lastIndexOf("@Redirect(") > window.lastIndexOf("@Inject(")
				&& window.lastIndexOf("@Redirect(") > window.lastIndexOf("@ModifyVariable(");
	}

	private static boolean handlerIsStatic(String source, int from) {		Matcher m = Pattern.compile("\\n\\s*(public|protected|private)?\\s*(static\\s+)?[\\w<>,.\\[\\] ?]+\\s+[\\w$]+\\s*\\(")
				.matcher(source.substring(from, Math.min(source.length(), from + 900)));
		return m.find() && m.group(2) != null;
	}

	private static String resolve(String simpleName, String source) {
		if (simpleName.contains(".")) {
			// Nested class reference such as Display.TextDisplay: resolve the head
			// through the imports and append the rest.
			int dot = simpleName.indexOf('.');
			String head = simpleName.substring(0, dot);
			String tail = simpleName.substring(dot);
			Matcher nested = Pattern.compile("import\\s+([A-Za-z0-9_.]+\\.)" + Pattern.quote(head) + ";")
					.matcher(source);
			if (nested.find()) {
				return nested.group(1) + head + tail;
			}
			return simpleName;
		}
		Matcher m = Pattern.compile("import\\s+([A-Za-z0-9_.]+\\.)" + simpleName + ";").matcher(source);
		return m.find() ? m.group(1) + simpleName : null;
	}

	private static ClassModel model(String fqn) {
		return MODELS.computeIfAbsent(fqn, name -> {
			if (name.startsWith("java.") || name.startsWith("javax.")) {
				return null;
			}
			ZipEntry entry = zip.getEntry(name.replace('.', '/') + ".class");
			if (entry == null) {
				// nested class: a.b.Outer.Inner -> a/b/Outer$Inner.class
				int index = name.length();
				while (entry == null && (index = name.lastIndexOf('.', index - 1)) > 0) {
					String candidate = name.substring(0, index).replace('.', '/') + "$"
							+ name.substring(index + 1).replace('.', '$') + ".class";
					entry = zip.getEntry(candidate);
				}
			}
			if (entry == null) {
				for (ZipFile extra : EXTRA_JARS) {
					ZipEntry inExtra = findEntry(extra, name);
					if (inExtra != null) {
						try (var in = extra.getInputStream(inExtra)) {
							return ClassFile.of().parse(in.readAllBytes());
						} catch (IOException | IllegalArgumentException e) {
							return null;
						}
					}
				}
				return null;
			}
			try (var in = zip.getInputStream(entry)) {
				return ClassFile.of().parse(in.readAllBytes());
			} catch (IOException | IllegalArgumentException e) {
				return null;
			}
		});
	}

	/** Finds a class entry in a jar, resolving {@code Outer.Inner} to {@code Outer$Inner}. */
	private static ZipEntry findEntry(ZipFile jar, String fqn) {
		ZipEntry entry = jar.getEntry(fqn.replace('.', '/') + ".class");
		if (entry != null) {
			return entry;
		}
		int index = fqn.length();
		while (entry == null && (index = fqn.lastIndexOf('.', index - 1)) > 0) {
			String candidate = fqn.substring(0, index).replace('.', '/') + "$"
					+ fqn.substring(index + 1).replace('.', '$') + ".class";
			entry = jar.getEntry(candidate);
		}
		return entry;
	}

	/**
	 * True when the class declares (or inherits) a method with this name - the same walk
	 * the rest of the validator uses for member lookups.
	 */
	private static boolean hasAnyMethodNamed(ClassModel model, String name) {
		return model.methods().stream().anyMatch(m -> m.methodName().stringValue().equals(name));
	}
	private static List<MethodModel> allMethods(String fqn) {
		ClassModel m = model(fqn);
		return m == null ? List.of() : m.methods();
	}

	private static Optional<MethodInfo> findMember(String owner, String member) {
		// "name(desc)" for a method, "name" or "name:Ltype;" for a field.
		int paren = member.indexOf('(');
		String name = paren >= 0 ? member.substring(0, paren) : member;
		String desc = paren >= 0 ? member.substring(paren) : null;
		int colon = name.indexOf(':');
		if (colon >= 0) {
			name = name.substring(0, colon);
		}
		Set<String> visited = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		queue.add(owner);
		while (!queue.isEmpty()) {
			String current = queue.poll();
			if (!visited.add(current)) {
				continue;
			}
			ClassModel m = model(current);
			if (m == null) {
				continue;
			}
			for (MethodModel method : m.methods()) {
				if (method.methodName().stringValue().equals(name)
						&& (desc == null || method.methodType().stringValue().equals(desc))) {
					return Optional.of(new MethodInfo(name, method.methodType().stringValue(),
							method.flags().has(AccessFlag.STATIC)));
				}
			}
			for (var field : m.fields()) {
				if (desc == null && field.fieldName().stringValue().equals(name)) {
					return Optional.of(new MethodInfo(name, field.fieldType().stringValue(), false));
				}
			}
			if (m.superclass().isPresent()) {
				queue.add(m.superclass().get().asInternalName().replace('/', '.'));
			}
			m.interfaces().forEach(i -> queue.add(i.asInternalName().replace('/', '.')));
		}
		return Optional.empty();
	}

	private static Optional<MethodInfo> findMethod(String owner, String spec) {
		return findMember(owner, spec);
	}

	private static int countMethodsNamed(String owner, String name) {
		return (int) allMethods(owner).stream().filter(m -> m.methodName().stringValue().equals(name)).count();
	}
}
