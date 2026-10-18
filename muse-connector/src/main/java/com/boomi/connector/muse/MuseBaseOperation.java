package com.boomi.connector.muse;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

import org.apache.http.impl.client.CloseableHttpClient;

import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.PayloadMetadata;
import com.boomi.connector.api.PropertyMap;
import com.boomi.connector.util.BaseUpdateOperation;
import com.boomi.connector.util.PayloadUtil;
import com.boomi.util.IOUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared plumbing for the operations: request building with the
 * document &gt; operation &gt; connection precedence, per-document outcome
 * recording, and budget short-circuiting.
 */
abstract class MuseBaseOperation extends BaseUpdateOperation {

    private static final long serialVersionUID = 1L;

    protected final OperationContext _context;
    protected final MuseConnector _connector;

    protected MuseBaseOperation(OperationContext context, MuseConnector connector) {
        super(context);
        _context = context;
        _connector = connector;
    }

    /** Outcome of one document, recorded later in document order. */
    protected static final class DocOutcome {
        enum Kind { SUCCESS, DOC_ERROR, AUTH_FATAL }

        final Kind kind;
        final String outputJson;
        final OperationStatus status;
        final String code;
        final String message;
        final MuseApiException authError;
        /** Document properties the process can read downstream (and reporting). Never null. */
        final Map<String, String> trackedProperties;

        private DocOutcome(Kind kind, String outputJson, OperationStatus status,
                String code, String message, MuseApiException authError,
                Map<String, String> trackedProperties) {
            this.kind = kind;
            this.outputJson = outputJson;
            this.status = status;
            this.code = code;
            this.message = message;
            this.authError = authError;
            this.trackedProperties = trackedProperties;
        }

        static DocOutcome success(String outputJson) {
            return success(outputJson, new LinkedHashMap<String, String>());
        }

        static DocOutcome success(String outputJson, Map<String, String> trackedProperties) {
            return new DocOutcome(Kind.SUCCESS, outputJson, null, null, null, null, trackedProperties);
        }

        static DocOutcome docError(OperationStatus status, String code, String message) {
            return new DocOutcome(Kind.DOC_ERROR, null, status, code, message, null,
                    new LinkedHashMap<String, String>());
        }

        static DocOutcome authFatal(MuseApiException e) {
            return new DocOutcome(Kind.AUTH_FATAL, null, null, null, null, e,
                    new LinkedHashMap<String, String>());
        }
    }

    protected MuseConnection connection() {
        try {
            return MuseConnection.from(_context.getConnectionProperties());
        } catch (IllegalArgumentException e) {
            // Bad configuration invalidates the whole execution, not one document.
            throw new ConnectorException(e.getMessage(), e);
        }
    }

    protected CloseableHttpClient httpClient(MuseConnection conn) {
        return _connector.httpClientFor(_context, conn);
    }

    /**
     * Runs one document through the API without recording anything, so the
     * caller can record outcomes in order.
     */
    protected DocOutcome runOne(ObjectData input, CloseableHttpClient http,
            MuseConnection conn, BudgetTracker budget) {
        InputStream data = input.getData();
        try {
            // A dry run spends nothing, so it neither trips on nor counts
            // against the budget: its job is inspection.
            final boolean dryRun = isDryRun(_context.getOperationProperties());
            if (!dryRun) {
                String blocked = budget.blockReason();
                if (blocked != null) {
                    return DocOutcome.docError(OperationStatus.APPLICATION_ERROR, "BUDGET_EXCEEDED",
                            "Skipped: " + blocked + ". Raise the budget or narrow the input.");
                }
            }
            final ObjectNode body;
            try {
                body = buildRequestBody(data, _context.getOperationProperties(), conn);
            } catch (IOException e) {
                return DocOutcome.docError(OperationStatus.APPLICATION_ERROR, "BAD_INPUT",
                        "The input document is not valid JSON.");
            } catch (IllegalArgumentException e) {
                return DocOutcome.docError(OperationStatus.APPLICATION_ERROR, "BAD_INPUT", e.getMessage());
            }
            if (dryRun) {
                // Hand the request back without calling the API, so the user
                // can inspect what a map produces. The response profile does
                // not apply to this document.
                return DocOutcome.success(body.toString());
            }
            try {
                MuseClient.ChatResult result = MuseClient.chat(http, conn, body);
                budget.record(result.promptTokens, result.completionTokens);
                ObjectNode out = buildOutputDocument(result, conn);
                return DocOutcome.success(out.toString(), trackedProperties(result, out));
            } catch (MuseApiException e) {
                if (e.isAuthFailure()) {
                    return DocOutcome.authFatal(e);
                }
                return DocOutcome.docError(OperationStatus.APPLICATION_ERROR,
                        String.valueOf(e.getStatusCode()), e.getMessage());
            } catch (Exception e) {
                input.getLogger().log(Level.SEVERE, "Muse request failed", e);
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                return DocOutcome.docError(OperationStatus.FAILURE, "-1", message);
            }
        } finally {
            IOUtil.closeQuietly(data);
        }
    }

    protected void recordOutcome(ObjectData input, DocOutcome outcome, OperationResponse response) {
        switch (outcome.kind) {
            case SUCCESS:
                // Tracked properties ride on the payload metadata: the
                // process can read them as document properties downstream
                // (Set Properties, Notify, map functions) and they surface
                // in process reporting. Ids must match the descriptor's
                // trackedProperty entries character-for-character.
                PayloadMetadata metadata = response.createMetadata();
                for (Map.Entry<String, String> e : outcome.trackedProperties.entrySet()) {
                    metadata.setTrackedProperty(e.getKey(), e.getValue());
                }
                // Explicit charset: the platform reads result payloads via
                // writeTo(), and the charset pins the encoding it sees.
                response.addResult(input, OperationStatus.SUCCESS, "200", "OK",
                        PayloadUtil.toPayload(outcome.outputJson, StandardCharsets.UTF_8, metadata));
                break;
            case DOC_ERROR:
                response.addResult(input, outcome.status, outcome.code, outcome.message, null);
                break;
            case AUTH_FATAL:
            default:
                throw new ConnectorException(
                        "Muse authentication failed (HTTP " + outcome.authError.getStatusCode()
                                + "). Check the API key in the connection.",
                        outcome.authError);
        }
    }

    /**
     * Builds the chat completions body. Precedence for every knob is
     * document &gt; operation field &gt; connection.
     */
    private ObjectNode buildRequestBody(InputStream data, PropertyMap opProps, MuseConnection conn)
            throws IOException {
        JsonNode doc = MuseClient.MAPPER.readTree(data);
        if (!doc.isObject()) {
            throw new IllegalArgumentException("The input document must be a JSON object.");
        }

        ObjectNode body = MuseClient.MAPPER.createObjectNode();
        body.put("model", firstText(doc, opProps, "model", conn.getModel()));

        ArrayNode messages;
        JsonNode docMessages = doc.get("messages");
        if (docMessages != null && docMessages.isArray() && docMessages.size() > 0) {
            messages = (ArrayNode) docMessages;
        } else {
            String prompt = textOrNull(doc.get("prompt"));
            if (prompt == null || prompt.isEmpty()) {
                throw new IllegalArgumentException(
                        "The input document needs \"prompt\" (a string) or \"messages\" (an array).");
            }
            messages = MuseClient.MAPPER.createArrayNode();
            messages.add(messageNode("user", prompt));
        }

        String system = firstText(doc, opProps, "system", null);
        if (system != null && !system.isEmpty()) {
            ArrayNode withSystem = MuseClient.MAPPER.createArrayNode();
            withSystem.add(messageNode("system", system));
            withSystem.addAll(messages);
            messages = withSystem;
        }
        body.set("messages", messages);

        Double temperature = firstDouble(doc, opProps, "temperature", null);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        Integer maxTokens = firstInt(doc, opProps, "maxTokens", "max_tokens", null);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if ("json".equalsIgnoreCase(firstText(doc, opProps, "responseFormat", "text"))) {
            ObjectNode format = MuseClient.MAPPER.createObjectNode();
            format.put("type", "json_object");
            body.set("response_format", format);
        }
        return body;
    }

    private ObjectNode messageNode(String role, String content) {
        ObjectNode node = MuseClient.MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private ObjectNode buildOutputDocument(MuseClient.ChatResult result, MuseConnection conn) {
        ObjectNode out = MuseClient.MAPPER.createObjectNode();
        out.put("id", result.id);
        out.put("model", result.model);
        out.put("content", result.content);
        out.put("finishReason", result.finishReason);
        ObjectNode usage = MuseClient.MAPPER.createObjectNode();
        usage.put("promptTokens", result.promptTokens);
        usage.put("completionTokens", result.completionTokens);
        usage.put("totalTokens", result.totalTokens());
        out.set("usage", usage);
        out.put("estimatedCostUsd", estimatedCost(result, conn)
                .setScale(6, RoundingMode.HALF_UP).toPlainString());
        return out;
    }

    /**
     * Document properties for the process to read downstream: response id,
     * model, finish reason, token counts, and estimated cost. Values are
     * never null — a missing value becomes an empty string — because the
     * metadata setter does not accept nulls.
     */
    private static Map<String, String> trackedProperties(MuseClient.ChatResult result, ObjectNode out) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("museResponseId", nn(result.id));
        props.put("museModel", nn(result.model));
        props.put("museFinishReason", nn(result.finishReason));
        props.put("musePromptTokens", String.valueOf(result.promptTokens));
        props.put("museCompletionTokens", String.valueOf(result.completionTokens));
        props.put("museTotalTokens", String.valueOf(result.totalTokens()));
        props.put("museEstimatedCostUsd", out.path("estimatedCostUsd").asText(""));
        return props;
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    private BigDecimal estimatedCost(MuseClient.ChatResult result, MuseConnection conn) {
        BigDecimal million = new BigDecimal(1_000_000);
        BigDecimal in = new BigDecimal(result.promptTokens)
                .multiply(conn.getPricePerMillionInputUsd())
                .divide(million, 6, RoundingMode.HALF_UP);
        BigDecimal out = new BigDecimal(result.completionTokens)
                .multiply(conn.getPricePerMillionOutputUsd())
                .divide(million, 6, RoundingMode.HALF_UP);
        return in.add(out);
    }

    /**
     * Dry run is an operation-level switch only: a document asking not to be
     * sent would be a confusing override, so it is deliberately not
     * document-overridable like the request knobs above.
     */
    private static boolean isDryRun(PropertyMap opProps) {
        return Boolean.parseBoolean(opProps.getProperty("dryRun", "false"));
    }

    private static String firstText(JsonNode doc, PropertyMap opProps, String key, String fallback) {
        String fromDoc = textOrNull(doc.get(key));
        if (fromDoc != null) {
            return fromDoc;
        }
        String fromOp = opProps.getProperty(key, "");
        if (fromOp != null && !fromOp.trim().isEmpty()) {
            return fromOp.trim();
        }
        return fallback;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Double firstDouble(JsonNode doc, PropertyMap opProps, String key, Double fallback) {
        JsonNode node = doc.get(key);
        if (node != null && node.isNumber()) {
            return node.asDouble();
        }
        String fromOp = opProps.getProperty(key, "");
        if (fromOp != null && !fromOp.trim().isEmpty()) {
            try {
                return Double.parseDouble(fromOp.trim());
            } catch (NumberFormatException e) {
                // fall through to fallback
            }
        }
        return fallback;
    }

    private static Integer firstInt(JsonNode doc, PropertyMap opProps, String key, String altKey,
            Integer fallback) {
        for (String k : new String[] { key, altKey }) {
            JsonNode node = doc.get(k);
            if (node != null && node.isIntegralNumber()) {
                return node.asInt();
            }
        }
        String fromOp = opProps.getProperty(key, "");
        if (fromOp != null && !fromOp.trim().isEmpty()) {
            try {
                return Integer.parseInt(fromOp.trim());
            } catch (NumberFormatException e) {
                // fall through to fallback
            }
        }
        return fallback;
    }
}
