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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@WebFilter("/api/v1/*")
public final class CsrfFilter implements Filter {
    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain
    ) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (isSafeMethod(request.getMethod()) || isLogin(request)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SessionAttributes.USER_ID) == null) {
            chain.doFilter(request, response);
            return;
        }
        Object expected = session == null ? null : session.getAttribute(SessionAttributes.CSRF_TOKEN);
        String provided = request.getHeader("X-CSRF-Token");
        if (!(expected instanceof String expectedToken)
                || provided == null
                || !MessageDigest.isEqual(
                        expectedToken.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error("A valid CSRF token is required", "CSRF_TOKEN_INVALID"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isLogin(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getServletPath().endsWith("/auth/login");
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }
}
