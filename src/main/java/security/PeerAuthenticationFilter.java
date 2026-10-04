package security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import network.PeerAuthenticator;
import network.PeerAuthenticationException;
import model.PeerRegistration;
import service.NodeRuntime;

@WebFilter("/api/v1/internal/p2p/*")
public final class PeerAuthenticationFilter implements Filter {
    public static final String AUTHENTICATED_PEER_ATTRIBUTE =
            PeerAuthenticationFilter.class.getName() + ".peer";
    private static final String CERTIFICATE_CHAIN_ATTRIBUTE =
            "jakarta.servlet.request.X509Certificate";
    private final PeerAuthenticator peerAuthenticator;

    public PeerAuthenticationFilter() {
        this.peerAuthenticator = null;
    }

    PeerAuthenticationFilter(PeerAuthenticator peerAuthenticator) {
        this.peerAuthenticator = java.util.Objects.requireNonNull(
                peerAuthenticator, "peerAuthenticator");
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain
    ) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        PeerAuthenticator authenticator = peerAuthenticator;
        if (authenticator == null) {
            Object runtimeAttribute = request.getServletContext()
                    .getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
            if (runtimeAttribute instanceof NodeRuntime runtime) {
                authenticator = runtime.peerAuthenticator();
            }
        }
        if (authenticator == null) {
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Peer runtime is unavailable", "PEER_RUNTIME_UNAVAILABLE"));
            return;
        }
        Object certificateAttribute = request.getAttribute(CERTIFICATE_CHAIN_ATTRIBUTE);
        if (!(certificateAttribute instanceof X509Certificate[] certificateChain)
                || certificateChain.length == 0) {
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("A mutual-TLS peer certificate is required", "PEER_CERTIFICATE_REQUIRED"));
            return;
        }
        try {
            PeerRegistration peer = authenticator.authenticate(certificateChain);
            request.setAttribute(AUTHENTICATED_PEER_ATTRIBUTE, peer);
            chain.doFilter(request, response);
        } catch (PeerAuthenticationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error(exception.getMessage(), "PEER_NOT_AUTHORIZED"));
        } catch (RuntimeException exception) {
            request.getServletContext().log("Could not authenticate P2P peer against canonical registry", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Canonical peer authorization is unavailable",
                            "PEER_AUTHORIZATION_UNAVAILABLE"));
        }
    }
}
