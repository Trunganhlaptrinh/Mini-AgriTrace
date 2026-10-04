package controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

@WebServlet("/api/v1/public/trace-qr/*")
public final class PublicTraceQrServlet extends HttpServlet {
    private static final int QR_SIZE = 280;
    private static final String PUBLIC_BASE_URL_PROPERTY = "agritrace.public.base.url";
    private transient String publicBaseUrl;

    public PublicTraceQrServlet() {
    }

    PublicTraceQrServlet(String publicBaseUrl) {
        this.publicBaseUrl = validatePublicBaseUrl(publicBaseUrl);
    }

    @Override
    public void init() throws ServletException {
        String configured = System.getProperty(PUBLIC_BASE_URL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("AGRITRACE_PUBLIC_BASE_URL");
        }
        try {
            publicBaseUrl = validatePublicBaseUrl(configured);
        } catch (IllegalArgumentException exception) {
            getServletContext().log("Public trace QR base URL is not configured correctly", exception);
            throw new ServletException("Public trace QR URL configuration is invalid", exception);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String batchCode = batchCodeFromPath(request.getPathInfo());
        if (batchCode == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "A batch code is required");
            return;
        }

        String traceUrl = publicBaseUrl + "/?trace="
                + URLEncoder.encode(batchCode, StandardCharsets.UTF_8);
        try {
            var matrix = new QRCodeWriter().encode(
                    traceUrl,
                    BarcodeFormat.QR_CODE,
                    QR_SIZE,
                    QR_SIZE,
                    Map.of(EncodeHintType.MARGIN, 2));
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("image/svg+xml");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            var writer = response.getWriter();
            writer.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                    .append(Integer.toString(matrix.getWidth())).append(' ')
                    .append(Integer.toString(matrix.getHeight()))
                    .append("\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>");
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    if (matrix.get(x, y)) {
                        writer.append("<rect x=\"").append(Integer.toString(x))
                                .append("\" y=\"").append(Integer.toString(y))
                                .append("\" width=\"1\" height=\"1\" fill=\"#10251d\"/>");
                    }
                }
            }
            writer.append("</svg>");
        } catch (com.google.zxing.WriterException exception) {
            getServletContext().log("Public trace QR generation failed", exception);
            response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "QR generation failed");
        }
    }

    private String batchCodeFromPath(String pathInfo) {
        if (pathInfo == null || pathInfo.length() < 2
                || pathInfo.charAt(0) != '/' || pathInfo.indexOf('/', 1) >= 0) {
            return null;
        }
        return pathInfo.substring(1);
    }

    static String validatePublicBaseUrl(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(
                    "Set AGRITRACE_PUBLIC_BASE_URL to the trusted public application URL");
        }
        URI uri;
        try {
            uri = new URI(configured.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Public base URL must be a valid absolute URI", exception);
        }
        String scheme = uri.getScheme();
        boolean localHttp = "http".equalsIgnoreCase(scheme)
                && uri.getHost() != null
                && (uri.getHost().equalsIgnoreCase("localhost")
                        || uri.getHost().equals("127.0.0.1")
                        || uri.getHost().equals("::1"));
        if (uri.getHost() == null
                || !("https".equalsIgnoreCase(scheme) || localHttp)
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Public base URL must use HTTPS (HTTP is allowed only for localhost) without credentials, query, or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String normalizedPath = path.replaceAll("/+$", "");
        String authority = uri.getRawAuthority().toLowerCase(Locale.ROOT);
        return scheme.toLowerCase(Locale.ROOT) + "://" + authority + normalizedPath;
    }
}
