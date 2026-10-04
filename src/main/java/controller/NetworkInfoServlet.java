package controller;

import com.google.gson.JsonObject;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import security.ApiJson;
import service.NodeRuntime;

@WebServlet("/api/v1/network")
public final class NetworkInfoServlet extends HttpServlet {
    private transient String networkId;

    @Override
    public void init() throws ServletException {
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace network runtime is not initialized");
        }
        networkId = nodeRuntime.peerLedgerService().networkId();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("networkId", networkId);
        ApiJson.write(response, HttpServletResponse.SC_OK,
                ShipmentProposalJson.success("Network information", data));
    }
}
