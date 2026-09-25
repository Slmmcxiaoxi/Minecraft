import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny OpenAI-compatible stub used to verify the "batch failed -> retry the
 * texts one by one" fallback of the mod.
 * <p>
 * It deliberately misbehaves the way the user's local translation model did:
 * for a batch of more than one text it returns an array with three elements
 * missing, so the mod has to reject the answer; for a single text it answers
 * correctly. A run against this stub therefore only produces translations if the
 * per-text fallback works.
 * <p>
 * Run: java StubServer.java 8099   (single-file source launch, JDK 11+)
 */
public final class StubServer {

	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 8099;
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/v1/chat/completions", StubServer::chat);
		server.createContext("/v1/models", exchange -> respond(exchange, 200,
				"{\"object\":\"list\",\"data\":[{\"id\":\"stub\",\"object\":\"model\"}]}"));
		server.setExecutor(null);
		server.start();
		System.out.println("stub listening on http://127.0.0.1:" + port);
	}

	private static void chat(HttpExchange exchange) throws java.io.IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		List<String> texts = extractTexts(body);
		int count = texts.size();
		StringBuilder array = new StringBuilder("[");
		int returned = count > 1 ? Math.max(1, count - 3) : count; // short answer for batches
		for (int i = 0; i < returned; i++) {
			if (i > 0) {
				array.append(',');
			}
			array.append('"').append(escape("[stub] " + texts.get(i))).append('"');
		}
		array.append(']');
		System.out.println("request: " + count + " text(s) -> answering " + returned);
		String content = escape(array.toString());
		respond(exchange, 200, "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
				+ content + "\"}}]}");
	}

	/** Minimal JSON scan: the string elements of the top level "texts" array. */
	private static List<String> extractTexts(String json) {
		// The mod sends the payload as a JSON *string* inside the message content,
		// so its inner quotes arrive escaped; unescape before scanning.
		json = json.replace("\\\"", "\"");
		List<String> texts = new ArrayList<>();
		int key = json.indexOf("\"texts\"");
		if (key < 0) {
			return texts;
		}
		int start = json.indexOf('[', key);
		if (start < 0) {
			return texts;
		}
		int i = start + 1;
		while (i < json.length()) {
			char c = json.charAt(i);
			if (c == ']') {
				break;
			}
			if (c == '"') {
				StringBuilder value = new StringBuilder();
				i++;
				while (i < json.length() && json.charAt(i) != '"') {
					char ch = json.charAt(i);
					if (ch == '\\' && i + 1 < json.length()) {
						ch = unescape(json.charAt(i + 1));
						i++;
					}
					value.append(ch);
					i++;
				}
				texts.add(value.toString());
			}
			i++;
		}
		return texts;
	}

	private static char unescape(char c) {
		return switch (c) {
			case 'n' -> '\n';
			case 't' -> '\t';
			case 'r' -> '\r';
			default -> c;
		};
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
