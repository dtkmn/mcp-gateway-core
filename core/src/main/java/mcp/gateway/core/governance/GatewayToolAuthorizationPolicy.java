package mcp.gateway.core.governance;

/**
 * Runtime mode for MCP tool authorization inside a gateway governance pass.
 * <p>
 * The policy separates "should authorization be evaluated" from "should a
 * negative authorization decision reject the request." This lets adapters expose
 * disabled, warn-only, and enforce modes while the core governance pipeline
 * keeps one decision model.
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
     * <p>
     * The governance pipeline skips authorization entirely. Protection may still
     * run when a protection evaluator is enabled.
     *
     * @return disabled policy
     */
    public static GatewayToolAuthorizationPolicy disabled() {
        return new GatewayToolAuthorizationPolicy(false, false, false);
    }

    /**
     * Authorization warning mode. Decisions are evaluated and observable, but
     * denied or unmapped authorizable requests are allowed through unless another
     * governance step rejects them.
     *
     * @return warning policy
     */
    public static GatewayToolAuthorizationPolicy warn() {
        return new GatewayToolAuthorizationPolicy(true, false, false);
    }

    /**
     * Authorization enforcement mode. Denied or unmapped authorizable requests
     * are rejected before protection evaluation.
     *
     * @return enforcing policy
     */
    public static GatewayToolAuthorizationPolicy enforce() {
        return new GatewayToolAuthorizationPolicy(true, true, true);
    }
}
