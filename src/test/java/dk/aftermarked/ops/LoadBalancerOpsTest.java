package dk.aftermarked.ops;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPERATIONS — the load balancer (PLAN §2.7, layer 2 of the cache policy).
 *
 * <p>nginx stands between the world and the application and can therefore break
 * things no application test sees: the host name that carries operator resolution, a
 * retransmission of a POST that turns into two payments, or a cache that serves one
 * customer's page to another.
 */
class LoadBalancerOpsTest {

	/**
	 * Only the active directives — comment lines are cut away. Otherwise a comment
	 * that <em>explains</em> why something is not there could make the test believe
	 * that it is there.
	 */
	private static final String CONFIG = String.join("\n",
			ProjectFile.activeLines("deploy/nginx/nginx.conf"));

	/**
	 * PLAN §2.7 rule 2: the operator is resolved from the host name. If the proxy
	 * forwards its own name (the nginx default is the upstream name, that is "app"),
	 * the application sees the host name "app", finds no operator and answers 421 to
	 * EVERYTHING.
	 */
	@Test
	void givenTheNginxConfig_whenProxyingARequest_thenTheClientHostNameIsForwardedUnchanged() {
		assertThat(CONFIG)
				.as("without this line the application sees the upstream name instead of the customer domain")
				.contains("proxy_set_header Host $host;");
	}

	/**
	 * {@code non_idempotent} would let nginx resend a POST that has already reached a
	 * replica. On the purchase path that is two payments for one purchase. The money
	 * zone in CLAUDE.md: never an amount that can be charged twice.
	 */
	@Test
	void givenTheNginxConfig_whenAReplicaDies_thenAPostIsNeverResentToTheNextReplica() {
		assertThat(CONFIG)
				.as("proxy_next_upstream must be present, so a dead replica does not become a 502 for the user")
				.contains("proxy_next_upstream ");
		assertThat(CONFIG)
				.as("""
						non_idempotent makes POST resendable. Combined with the purchase saga \
						(pay → swap) that can become two Stripe charges for one purchase.""")
				.doesNotContain("non_idempotent");
	}

	@Test
	void givenTheNginxConfig_whenInspectingItsProxyTimeouts_thenBothDirectionsAreSetExplicitly() {
		assertThat(CONFIG)
				.as("without a connect timeout a request against a dead replica hangs until TCP gives up")
				.contains("proxy_connect_timeout")
				.as("read/send default to 60s — a whole minute with a worker tied up per hanging request")
				.contains("proxy_read_timeout")
				.contains("proxy_send_timeout");
	}

	/**
	 * Client-controlled forwarding headers would otherwise pass through unchanged. The
	 * application does not read them today (proven in {@link WebSecurityOpsTest}), but
	 * the proxy is the right place to neutralize them: then it also holds if someone
	 * later enables {@code server.forward-headers-strategy}.
	 */
	@Test
	void givenAClientSuppliedForwardingHeader_whenTheRequestPassesTheEdge_thenTheHeaderIsOverwritten() {
		assertThat(CONFIG)
				.as("a foreign X-Forwarded-Host is an attempt to become a different operator")
				.contains("proxy_set_header X-Forwarded-Host $host;")
				.contains("proxy_set_header Forwarded \"\";");
	}

	/**
	 * A cache in the load balancer is layer 2 of PLAN §2.7 — a deliberate decision with
	 * a key that contains the operator. Enabled without that, one customer's
	 * marketplace is served to another. The same class of bug as a missing RLS filter.
	 */
	@Test
	void givenTheNginxConfig_whenSearchingForCacheDirectives_thenNoProxyCacheIsEnabledByAccident() {
		assertThat(CONFIG)
				.as("""
						PLAN §2.7: caching is opt-in, and a cache key without operator_id is \
						a data leak between customers. If layer 2 is to be taken into use, it \
						happens deliberately — and with a test that proves the key.""")
				.doesNotContain("proxy_cache");
	}

	@Test
	void givenTheNginxConfig_whenServingAnyResponse_thenNginxDoesNotRevealItsVersion() {
		assertThat(CONFIG)
				.as("otherwise the Server header and the error pages tell a scanner exactly which version is running")
				.contains("server_tokens off;");
	}

	/**
	 * Docker's DNS returns the replica addresses. Without a variable in
	 * {@code proxy_pass} nginx freezes the lookup at startup, and a replica that has
	 * been restarted with a new IP is never found again — half the traffic would go to
	 * an address that does not exist.
	 */
	@Test
	void givenARestartedReplicaWithANewAddress_whenNginxProxiesTheNextRequest_thenTheUpstreamIsResolvedAgain() {
		assertThat(CONFIG).contains("resolver 127.0.0.11");
		assertThat(CONFIG)
				.as("a variable in proxy_pass forces a fresh DNS lookup; a fixed address would freeze at startup")
				.contains("set $upstream")
				.contains("proxy_pass $upstream;");
	}

}
