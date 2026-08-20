package com.jundaodsj.insightops.infrastructure.delivery;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class WebhookUrlPolicy {
    private WebhookUrlPolicy() { }

    public static URI syntax(String raw) {
        try {
            if (raw == null || raw.isBlank() || raw.length() > 2048) throw invalid();
            URI uri = URI.create(raw.trim()).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isBlank()
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || "localhost".equals(host) || host.endsWith(".localhost")) {
                throw invalid();
            }
            return uri;
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && "Webhook URL must use a public HTTPS endpoint".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalid();
        }
    }

    public static URI resolvedPublic(String raw) {
        URI uri = syntax(raw);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException("Webhook host did not resolve");
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                boolean carrierGradeNat = bytes.length == 4 && (bytes[0] & 0xff) == 100
                        && (bytes[1] & 0xc0) == 64;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || uniqueLocalV6 || carrierGradeNat) {
                    throw new IllegalArgumentException("Webhook host resolved to a non-public address");
                }
            }
            return uri;
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Webhook host could not be resolved", exception);
        }
    }

    public static String masked(URI uri) {
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + "/***";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Webhook URL must use a public HTTPS endpoint");
    }
}
