package security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

@WebFilter("/api/v1/*")
public final class AuthenticationFilter implements Filter {
    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain
    ) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (isLogin(request) || isPublicTrace(request) || isPublicTraceQr(request)
                || isPublicNetworkInfo(request) || isInternalPeerRoute(request)) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SessionAttributes.USER_ID) == null) {
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("Authentication is required", "UNAUTHENTICATED"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isLogin(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getServletPath().endsWith("/auth/login");
    }

    private boolean isPublicTrace(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/public/batches".equals(request.getServletPath())) {
            return false;
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            return false;
        }
        String[] segments = pathInfo.split("/");
        return segments.length == 3
                && segments[0].isEmpty()
                && !segments[1].isBlank()
                && "trace".equals(segments[2]);
    }

    private boolean isPublicTraceQr(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/public/trace-qr".equals(request.getServletPath())) {
            return false;
        }
        String pathInfo = request.getPathInfo();
        return pathInfo != null && pathInfo.length() > 1
                && pathInfo.charAt(0) == '/' && pathInfo.indexOf('/', 1) < 0;
    }

    private boolean isPublicNetworkInfo(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && "/api/v1/network".equals(request.getServletPath());
    }

    private boolean isInternalPeerRoute(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return servletPath != null
                && ("/api/v1/internal/p2p".equals(servletPath)
                        || servletPath.startsWith("/api/v1/internal/p2p/"));
    }
}
