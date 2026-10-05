package com.boomi.custom.jev;

import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.Payload;
import com.boomi.connector.api.PayloadMetadata;
import com.boomi.connector.api.PropertyMap;
import com.boomi.connector.api.UpdateRequest;
import com.boomi.connector.util.BaseUpdateOperation;
import com.boomi.connector.util.PayloadUtil;
import com.boomi.connector.util.ResponseUtil;
import com.boomi.custom.jev.client.JevClient;
import com.boomi.custom.jev.client.JevHttpResponse;
import com.boomi.custom.jev.client.RateLimitGate;
import com.boomi.custom.jev.concurrent.CallWindow;
import com.boomi.custom.jev.review.DocumentReader;
import com.boomi.custom.jev.review.InvalidInputException;
import com.boomi.custom.jev.review.QuestionSet;
import com.boomi.custom.jev.review.RequestBuilder;
import com.boomi.custom.jev.review.RequestMode;
import com.boomi.custom.jev.review.ResultMapper;
import com.boomi.custom.jev.review.ResultProperties;
import com.boomi.custom.jev.review.ReviewConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;

/**
 * EXECUTE "Review Document": one JEV call per input document, one structured output document per input.
 *
 * <ul>
 *   <li>SUCCESS (code 200..299) with the mapped result; the message is DECIDED or NEEDS_REVIEW.</li>
 *   <li>APPLICATION_ERROR "INVALID_INPUT" when the document or configuration is rejected before calling JEV.</li>
 *   <li>APPLICATION_ERROR with the HTTP status when JEV returns an error (body is passed through).</li>
 *   <li>APPLICATION_ERROR "CONNECTION_ERROR" when JEV cannot be reached after retries.</li>
 * </ul>
 *
 * <p>Each document goes through three steps: <em>prepare</em> (read it and build the request), <em>call</em> JEV,
 * and <em>finish</em> (map the answer and add the result). Prepare and finish always run on the calling thread, in
 * document order, so every Boomi SDK object is only touched from that thread. Only the call step is affected by
 * Max Concurrent Requests: documents are taken in windows of that size and the window's calls run in parallel.
 * At 1 (the default) the window holds one document and its call runs in line, with no worker threads.
 */
public class JevReviewOperation extends BaseUpdateOperation {

    static final String INVALID_INPUT = "INVALID_INPUT";
    static final String CONNECTION_ERROR = "CONNECTION_ERROR";
    static final String INVALID_RESPONSE = "INVALID_RESPONSE";

    public JevReviewOperation(JevConnection<OperationContext> connection) {
        super(connection);
    }

    @Override
    @SuppressWarnings("unchecked")
    public JevConnection<OperationContext> getConnection() {
        return (JevConnection<OperationContext>) super.getConnection();
    }

    @Override
    protected void executeUpdate(UpdateRequest request, OperationResponse response) {
        PropertyMap operationProperties = getContext().getOperationProperties();
        int concurrency = CallWindow.clamp(operationProperties.getLongProperty(
                JevConstants.MAX_CONCURRENT_REQUESTS, JevConstants.DEFAULT_MAX_CONCURRENT_REQUESTS));
        JevClient client = getConnection().createClient();
        if (concurrency > 1) {
            // Parallel workers share one pause, so a 429 seen by one holds the others back too
            client = client.withRateLimitGate(new RateLimitGate());
        }
        ReviewConfig baseConfig = ReviewConfig.from(operationProperties, client.getSettings().getDefaultModel());

        try (CallWindow calls = new CallWindow(concurrency)) {
            // At most one window of documents is held in memory at a time
            List<Slot> window = new ArrayList<>(concurrency);
            for (ObjectData document : request) {
                window.add(prepare(document, baseConfig));
                if (window.size() >= concurrency) {
                    flush(window, calls, client, response);
                    window.clear();
                }
            }
            flush(window, calls, client, response);
        }
    }

    /** One input document: either ready to send, or already failed while being prepared. */
    private static final class Slot {
        final ObjectData document;
        final Prepared prepared;
        final Exception failure;

        Slot(ObjectData document, Prepared prepared, Exception failure) {
            this.document = document;
            this.prepared = prepared;
            this.failure = failure;
        }
    }

    /** Everything the call and finish steps need, resolved on the calling thread. */
    private static final class Prepared {
        final ReviewConfig config;
        final Double threshold;
        final String text;
        final ObjectNode jevRequest;
        final QuestionSet questions;

        Prepared(ReviewConfig config, Double threshold, String text, ObjectNode jevRequest, QuestionSet questions) {
            this.config = config;
            this.threshold = threshold;
            this.text = text;
            this.jevRequest = jevRequest;
            this.questions = questions;
        }
    }

    /** Step 1, calling thread: resolve the settings, read the document and build the JEV request. */
    private static Slot prepare(ObjectData document, ReviewConfig baseConfig) {
        try {
            ReviewConfig config = baseConfig.withOverrides(document);
            Double threshold = config.getThreshold();
            String text;
            try (InputStream in = document.getData()) {
                text = DocumentReader.readText(in, config.getMaxDocumentBytes());
            }

            ObjectNode jevRequest;
            QuestionSet questions;
            if (config.getMode() == RequestMode.RAW_REQUEST) {
                RequestBuilder.RawRequest raw = RequestBuilder.fromRaw(text, config.getModel());
                jevRequest = raw.getRequest();
                questions = raw.getQuestions();
            } else {
                if (config.getQuestionSetJson() == null || config.getQuestionSetJson().trim().isEmpty()) {
                    throw new InvalidInputException(QuestionSet.REQUIRED_MESSAGE + " [Found: "
                            + config.getQuestionSetSources() + "]");
                }
                questions = QuestionSet.parse(config.getQuestionSetJson());
                jevRequest = RequestBuilder.forReview(text, config.getStateFormat(), config.getStateKey(), questions,
                        config.getModel());
            }
            return new Slot(document, new Prepared(config, threshold, text, jevRequest, questions), null);
        } catch (InvalidInputException | IOException | RuntimeException e) {
            return new Slot(document, null, e);
        }
    }

    /**
     * Steps 2 and 3 for one window: call JEV for every prepared document (in parallel when allowed), then add one
     * result per document, in the order the documents arrived.
     */
    private void flush(List<Slot> window, CallWindow calls, JevClient client, OperationResponse response) {
        if (window.isEmpty()) {
            return;
        }
        List<Callable<JevHttpResponse>> requests = new ArrayList<>(window.size());
        for (Slot slot : window) {
            if (slot.prepared != null) {
                ObjectNode jevRequest = slot.prepared.jevRequest;
                requests.add(() -> client.decide(jevRequest));
            }
        }
        List<CallWindow.Outcome<JevHttpResponse>> outcomes = calls.run(requests);

        String fallbackNotice = calls.takeFallbackNotice();
        if (fallbackNotice != null) {
            window.get(0).document.getLogger().log(Level.WARNING, fallbackNotice);
        }

        int next = 0;
        for (Slot slot : window) {
            if (slot.prepared == null) {
                addFailure(slot.document, slot.failure, response);
                continue;
            }
            CallWindow.Outcome<JevHttpResponse> outcome = outcomes.get(next++);
            if (outcome.failed()) {
                addFailure(slot.document, outcome.getError(), response);
                continue;
            }
            try {
                finish(slot.document, slot.prepared, outcome.getValue(), response);
            } catch (RuntimeException e) {
                addFailure(slot.document, e, response);
            }
        }
    }

    private static void addFailure(ObjectData document, Exception e, OperationResponse response) {
        if (e instanceof InvalidInputException) {
            document.getLogger().log(Level.WARNING, "Document rejected: {0}", e.getMessage());
            response.addEmptyResult(document, OperationStatus.APPLICATION_ERROR, INVALID_INPUT, e.getMessage());
        } else if (e instanceof IOException) {
            document.getLogger().log(Level.WARNING, "JEV call failed", e);
            response.addEmptyResult(document, OperationStatus.APPLICATION_ERROR, CONNECTION_ERROR,
                    "Could not reach JEV: " + e);
        } else {
            document.getLogger().log(Level.SEVERE, "Unexpected error processing document", e);
            ResponseUtil.addExceptionFailure(response, document, e);
        }
    }

    /** Step 3, calling thread: turn JEV's response into the result for this document. */
    private static void finish(ObjectData document, Prepared prepared, JevHttpResponse jevResponse,
            OperationResponse response) {
        ReviewConfig config = prepared.config;
        String text = prepared.text;
        ReviewConfig.PropertyFlags flags = config.getPropertyFlags();
        String code = String.valueOf(jevResponse.getStatusCode());
        if (!jevResponse.isSuccess()) {
            PayloadMetadata metadata = null;
            if (flags.any()) {
                metadata = response.createMetadata();
                ResultProperties.applyError(metadata, code, flags.documentProperties(), flags.trackedProperties());
                if (flags.keepOriginalDocument()) {
                    ResultProperties.applyOriginalDocument(metadata, text);
                }
            }
            response.addResult(document, OperationStatus.APPLICATION_ERROR, code, jevResponse.errorMessage(),
                    payload(jevResponse.getBody(), metadata));
            return;
        }

        JsonNode body;
        try {
            body = jevResponse.bodyAsJson();
        } catch (IOException e) {
            response.addResult(document, OperationStatus.APPLICATION_ERROR, INVALID_RESPONSE,
                    "JEV returned a non-JSON response", ResponseUtil.toPayload(jevResponse.getBody(), StandardCharsets.UTF_8));
            return;
        }
        if (body == null || !body.isObject()) {
            response.addResult(document, OperationStatus.APPLICATION_ERROR, INVALID_RESPONSE,
                    "JEV response is not a JSON object", ResponseUtil.toPayload(jevResponse.getBody(), StandardCharsets.UTF_8));
            return;
        }

        ObjectNode result = ResultMapper.map(body, prepared.questions, prepared.threshold, config.isIncludeRaw());
        PayloadMetadata metadata = null;
        if (flags.any()) {
            metadata = response.createMetadata();
            ResultProperties.apply(metadata, result, flags.documentProperties(), flags.trackedProperties());
            if (flags.keepOriginalDocument()) {
                ResultProperties.applyOriginalDocument(metadata, text);
            }
        }
        response.addResult(document, OperationStatus.SUCCESS, code, result.path("status").asText(),
                payload(result.toString(), metadata));
    }

    private static Payload payload(String content, PayloadMetadata metadata) {
        return metadata == null
                ? ResponseUtil.toPayload(content, StandardCharsets.UTF_8)
                : PayloadUtil.toPayload(content, StandardCharsets.UTF_8, metadata);
    }
}
