package mcp.gateway.core.url;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Normalized URL scope that can test whether a candidate URL is inside a base URL boundary.
 * ASCII and punycode host names are normalized. Raw Unicode host names, user
 * information, encoded backslashes, control characters, and multiply encoded
 * paths are rejected to avoid interpretation ambiguity at downstream clients.
 */
public final class UrlScope {
    private final String scheme;
    private final String host;
    private final int port;
    private final String path;

    private UrlScope(String scheme, String host, int port, String path) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.path = path;
    }

    /**
     * Parses an absolute base URL into a scope.
     *
     * @param baseUrl absolute base URL
     * @return parsed URL scope
     * @throws IllegalArgumentException when the URL is not absolute, lacks a host,
     *         or contains an ambiguous authority or path
     */
    public static UrlScope parse(String baseUrl) {
        UrlParts parts = parseParts(baseUrl);
        if (parts == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute URL with scheme and host");
        }
        return new UrlScope(parts.scheme(), parts.host(), parts.port(), parts.path());
    }

    /**
     * Returns whether a candidate URL is inside this scope.
     *
     * @param candidateUrl candidate absolute URL
     * @return {@code true} when scheme, host, effective port, and path are in scope
     */
    public boolean contains(String candidateUrl) {
        UrlParts candidate = parseParts(candidateUrl);
        return candidate != null
                && scheme.equals(candidate.scheme())
                && host.equals(candidate.host())
                && port == candidate.port()
                && containsPath(candidate.path());
    }

    private boolean containsPath(String candidatePath) {
        if ("/".equals(path)) {
            return true;
        }
        return candidatePath.equals(path) || candidatePath.startsWith(path + "/");
    }

    private static UrlParts parseParts(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim()).normalize();
            String scheme = normalizeScheme(uri.getScheme());
            Authority authority = parseAuthority(uri.getRawAuthority());
            String path = normalizePath(uri.getRawPath(), uri.getPath());
            if (scheme == null || authority == null || path == null) {
                return null;
            }
            return new UrlParts(
                    scheme,
                    authority.host(),
                    effectivePort(scheme, authority.port()),
                    path
            );
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return null;
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String normalized = host.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        if (normalized.indexOf('%') >= 0) {
            return null;
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (normalized.charAt(index) > 0x7f) {
                return null;
            }
        }
        normalized = IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }

    private static String normalizePath(String rawPath, String decodedPath) throws URISyntaxException {
        if (containsEncodedPercent(rawPath) || containsUnsafePathCharacter(decodedPath)) {
            return null;
        }
        String path = decodedPath;
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = new URI(null, null, path, null).normalize().getPath();
        if (normalized == null || normalized.isBlank()) {
            return "/";
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private static Authority parseAuthority(String rawAuthority) {
        if (rawAuthority == null || rawAuthority.isBlank() || rawAuthority.indexOf('@') >= 0) {
            return null;
        }

        String rawHost;
        int port = -1;
        if (rawAuthority.startsWith("[")) {
            int closingBracket = rawAuthority.indexOf(']');
            if (closingBracket < 0) {
                return null;
            }
            rawHost = rawAuthority.substring(0, closingBracket + 1);
            String remainder = rawAuthority.substring(closingBracket + 1);
            if (!remainder.isEmpty()) {
                if (!remainder.startsWith(":")) {
                    return null;
                }
                port = parsePort(remainder.substring(1));
                if (port < 0) {
                    return null;
                }
            }
        } else {
            int colon = rawAuthority.lastIndexOf(':');
            if (colon >= 0) {
                if (rawAuthority.indexOf(':') != colon) {
                    return null;
                }
                rawHost = rawAuthority.substring(0, colon);
                port = parsePort(rawAuthority.substring(colon + 1));
                if (port < 0) {
                    return null;
                }
            } else {
                rawHost = rawAuthority;
            }
        }

        String host = normalizeHost(rawHost);
        return host == null ? null : new Authority(host, port);
    }

    private static int parsePort(String rawPort) {
        if (rawPort.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < rawPort.length(); index++) {
            char character = rawPort.charAt(index);
            if (character < '0' || character > '9') {
                return -1;
            }
        }
        try {
            int port = Integer.parseInt(rawPort);
            return port <= 65_535 ? port : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static boolean containsEncodedPercent(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        for (int index = 0; index + 2 < rawPath.length(); index++) {
            if (rawPath.charAt(index) == '%'
                    && rawPath.charAt(index + 1) == '2'
                    && rawPath.charAt(index + 2) == '5') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUnsafePathCharacter(String path) {
        if (path == null) {
            return false;
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '\\'
                    || character == '\ufffd'
                    || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private record UrlParts(String scheme, String host, int port, String path) {
    }

    private record Authority(String host, int port) {
    }
}
