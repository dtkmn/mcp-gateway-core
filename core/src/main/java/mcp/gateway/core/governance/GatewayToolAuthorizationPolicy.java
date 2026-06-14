package mcp.gateway.core.governance;

/**
 * Runtime mode for MCP tool authorization inside a gateway governance pass.
 *
 * @param enabled whether authorization should run
 * @param rejectUnmapped whether unmapped authorizable actions should be rejected
 * @param rejectDenied whether mapped denied actions should be rejected
 */
public record GatewayToolAuthorizationPolicy(boolean enabled,
                                             boolean rejectUnmapped,
                                             boolean rejectDenied) {
    /**
     * Authorization disabled.
     *
     * @return disabled policy
     */
    public static GatewayToolAuthorizationPolicy disabled() {
        return new GatewayToolAuthorizationPolicy(false, false, false);
    }

    /**
     * Authorization warning mode. Decisions are evaluated and observable, but
     * denied or unmapped requests are allowed through.
     *
     * @return warning policy
     */
    public static GatewayToolAuthorizationPolicy warn() {
        return new GatewayToolAuthorizationPolicy(true, false, false);
    }

    /**
     * Authorization enforcement mode. Denied or unmapped authorizable requests
     * are rejected.
     *
     * @return enforcing policy
     */
    public static GatewayToolAuthorizationPolicy enforce() {
        return new GatewayToolAuthorizationPolicy(true, true, true);
    }
}
