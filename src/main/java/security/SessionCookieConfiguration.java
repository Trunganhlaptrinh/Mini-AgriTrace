package security;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.annotation.WebListener;

@WebListener
public final class SessionCookieConfiguration implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        SessionCookieConfig cookies = context.getSessionCookieConfig();
        cookies.setHttpOnly(true);
        cookies.setSecure(true);
        cookies.setAttribute("SameSite", "Lax");
    }
}
