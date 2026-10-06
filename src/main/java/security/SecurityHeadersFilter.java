package security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Adds browser security headers to every response produced by this web application. */
@WebFilter("/*")
public final class SecurityHeadersFilter implements Filter {
    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse servletResponse,
            FilterChain chain
    ) throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        SecurityResponseHeaders.apply(response);
        chain.doFilter(request, response);
    }
}
