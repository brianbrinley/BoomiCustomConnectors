package com.boomi.custom.jev;

import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.Payload;
import com.boomi.connector.api.PayloadMetadata;
import com.boomi.connector.api.UpdateRequest;
import com.boomi.connector.util.BaseUpdateOperation;
import com.boomi.connector.util.PayloadUtil;
import com.boomi.connector.util.ResponseUtil;
import com.boomi.custom.jev.client.JevClient;
import com.boomi.custom.jev.client.JevHttpResponse;
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
        JevClient client = getConnection().createClient();
        ReviewConfig baseConfig = ReviewConfig.from(getContext().getOperationProperties(),
                client.getSettings().getDefaultModel());

        for (ObjectData document : request) {
            try {
                process(document, baseConfig.withOverrides(document), client, response);
            } catch (InvalidInputException e) {
                document.getLogger().log(Level.WARNING, "Document rejected: {0}", e.getMessage());
                response.addEmptyResult(document, OperationStatus.APPLICATION_ERROR, INVALID_INPUT, e.getMessage());
            } catch (IOException e) {
                document.getLogger().log(Level.WARNING, "JEV call failed", e);
                response.addEmptyResult(document, OperationStatus.APPLICATION_ERROR, CONNECTION_ERROR,
                        "Could not reach JEV: " + e);
            } catch (RuntimeException e) {
                document.getLogger().log(Level.SEVERE, "Unexpected error processing document", e);
                ResponseUtil.addExceptionFailure(response, document, e);
            }
        }
    }

    private void process(ObjectData document, ReviewConfig config, JevClient client, OperationResponse response)
            throws InvalidInputException, IOException {
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

        ReviewConfig.PropertyFlags flags = config.getPropertyFlags();
        JevHttpResponse jevResponse = client.decide(jevRequest);
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

        ObjectNode result = ResultMapper.map(body, questions, threshold, config.isIncludeRaw());
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
