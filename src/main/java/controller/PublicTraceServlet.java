package controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import service.NodeRuntime;
import service.TraceabilityService;
import security.ApiJson;

@WebServlet("/api/v1/public/batches/*")
public final class PublicTraceServlet extends HttpServlet {
    private transient TraceabilityService traceabilityService;

    public PublicTraceServlet() {
    }

    PublicTraceServlet(TraceabilityService traceabilityService) {
        this.traceabilityService = traceabilityService;
    }

    @Override
    public void init() throws ServletException {
        if (traceabilityService != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace traceability runtime is not initialized");
        }
        traceabilityService = nodeRuntime.traceabilityService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String batchCode = batchCodeFromPath(request.getPathInfo());
        if (batchCode == null) {
            notFound(response);
            return;
        }
        try {
            var result = traceabilityService.findPublicTrace(batchCode);
            if (result.isEmpty()) {
                notFound(response);
                return;
            }
            ApiJson.write(response, HttpServletResponse.SC_OK,
                    TraceabilityJson.publicTrace(result.orElseThrow()));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (RuntimeException exception) {
            getServletContext().log("Public trace chain verification failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Canonical traceability data is unavailable",
                            "CHAIN_VERIFICATION_FAILED"));
        }
    }

    private String batchCodeFromPath(String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        String[] segments = pathInfo.split("/");
        if (segments.length != 3 || !segments[0].isEmpty()
                || segments[1].isBlank() || !"trace".equals(segments[2])) {
            return null;
        }
        return segments[1];
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("Batch was not found", "BATCH_NOT_FOUND"));
    }
}
