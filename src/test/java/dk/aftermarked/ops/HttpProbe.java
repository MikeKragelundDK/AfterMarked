package dk.aftermarked.ops;

import java.io.UncheckedIOException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * A real HTTP client against a real port.
 *
 * <p>MockMvc runs inside the application context and can therefore say nothing about
 * the management port (8081), which has its own child context, its own servlet
 * container and potentially its own — or no — filter chain. That is precisely the
 * question the operations tests have to answer empirically, and it takes a client
 * that speaks TCP.
 *
 * <p>Deliberately does not follow redirects: a 302 to a login page is a response the
 * test must be able to see, not something the client should hide.
 */
final class HttpProbe {

	private final HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private final String baseUrl;

	HttpProbe(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	HttpResponse<String> get(String path) {
		return send(request(path).GET().build());
	}

	HttpResponse<String> getWithLogin(String path, String username, String password) {
		String basic = Base64.getEncoder()
				.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
		return send(request(path).header("Authorization", "Basic " + basic).GET().build());
	}

	/** The value of a response header, or an empty string when it is absent. */
	static String header(HttpResponse<String> response, String name) {
		Optional<String> value = response.headers().firstValue(name);
		return value.orElse("");
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
