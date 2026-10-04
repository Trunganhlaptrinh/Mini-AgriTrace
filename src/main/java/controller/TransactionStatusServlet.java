package controller;

import com.google.gson.JsonObject;
import dal.PersistenceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Optional;
import java.util.function.Function;
import model.TransactionStatusSnapshot;
import security.ApiJson;
import service.NodeRuntime;
import service.TransactionService;

@WebServlet("/api/v1/transactions/*")
public final class TransactionStatusServlet extends HttpServlet {
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private transient Function<String, Optional<TransactionStatusSnapshot>> statusLookup;

    public TransactionStatusServlet() {
    }

    TransactionStatusServlet(TransactionService transactionService) {
        this(transactionService::findStatus);
    }

    TransactionStatusServlet(
            Function<String, Optional<TransactionStatusSnapshot>> statusLookup
    ) {
        this.statusLookup = statusLookup;
    }

    @Override
    public void init() throws ServletException {
        if (statusLookup != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace transaction runtime is not initialized");
        }
        statusLookup = nodeRuntime.transactionService()::findStatus;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String transactionId = transactionIdFromPath(request.getPathInfo());
        if (transactionId == null) {
            notFound(response);
            return;
        }
        try {
            Optional<TransactionStatusSnapshot> result = statusLookup.apply(transactionId);
            if (result.isEmpty()) {
                notFound(response);
                return;
            }
            ApiJson.write(response, HttpServletResponse.SC_OK, responseBody(result.orElseThrow()));
        } catch (PersistenceException exception) {
            getServletContext().log("Transaction status lookup failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Transaction service is unavailable", "TRANSACTION_SERVICE_UNAVAILABLE"));
        }
    }

    private JsonObject responseBody(TransactionStatusSnapshot snapshot) {
        JsonObject data = new JsonObject();
        data.addProperty("transactionId", snapshot.transactionId());
        data.addProperty("eventId", snapshot.eventId());
        data.addProperty("transactionType", snapshot.transactionType());
        data.addProperty("payloadHash", snapshot.payloadHash());
        data.addProperty("status", snapshot.status().name());
        data.addProperty("updatedAt", UTC_MILLIS.format(snapshot.updatedAt()));
        if (snapshot.status() == model.TransactionStatus.REJECTED) {
            data.addProperty("rejectionCode", snapshot.rejectionCode());
        }
        if (snapshot.status() == model.TransactionStatus.CONFIRMED) {
            JsonObject block = new JsonObject();
            block.addProperty("height", snapshot.blockHeight());
            block.addProperty("hash", snapshot.blockHash());
            block.addProperty("timestamp", UTC_MILLIS.format(snapshot.blockTimestamp()));
            data.add("block", block);
        }
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Transaction status");
        response.add("data", data);
        return response;
    }

    private String transactionIdFromPath(String pathInfo) {
        if (pathInfo == null || pathInfo.length() != 65 || pathInfo.charAt(0) != '/') {
            return null;
        }
        String transactionId = pathInfo.substring(1);
        return transactionId.matches("[0-9a-f]{64}") ? transactionId : null;
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("Transaction was not found", "TRANSACTION_NOT_FOUND"));
    }
}
