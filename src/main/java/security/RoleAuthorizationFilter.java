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

@WebFilter(urlPatterns = {"/api/v1/admin/*", "/api/v1/node/*"})
public final class RoleAuthorizationFilter implements Filter {
    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain
    ) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SessionAttributes.USER_ID) == null) {
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("Authentication is required", "UNAUTHENTICATED"));
            return;
        }
        if (!"ADMIN".equals(session.getAttribute(SessionAttributes.ROLE))) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error("Administrator access is required", "FORBIDDEN"));
            return;
        }
        chain.doFilter(request, response);
    }
}
