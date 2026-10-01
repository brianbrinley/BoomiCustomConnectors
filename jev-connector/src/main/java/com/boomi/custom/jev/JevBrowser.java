package com.boomi.custom.jev;

import com.boomi.connector.api.BrowseContext;
import com.boomi.connector.api.ConnectionTester;
import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.ContentType;
import com.boomi.connector.api.ObjectDefinition;
import com.boomi.connector.api.ObjectDefinitionRole;
import com.boomi.connector.api.ObjectDefinitions;
import com.boomi.connector.api.ObjectType;
import com.boomi.connector.api.ObjectTypes;
import com.boomi.connector.util.BaseBrowser;
import com.boomi.custom.jev.client.JevClient;
import com.boomi.custom.jev.client.JevHttpResponse;
import com.boomi.custom.jev.review.InvalidInputException;
import com.boomi.custom.jev.review.OutputSchema;
import com.boomi.custom.jev.review.QuestionSet;
import com.boomi.custom.jev.review.RequestMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Collection;

/**
 * Exposes a single "Document Review" object type. The request profile is unstructured (any text document); the
 * response profile is a JSON schema generated from the operation's Question Set when one is configured.
 */
public class JevBrowser extends BaseBrowser implements ConnectionTester {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JevBrowser(JevConnection<BrowseContext> connection) {
        super(connection);
    }

    @Override
    @SuppressWarnings("unchecked")
    public JevConnection<BrowseContext> getConnection() {
        return (JevConnection<BrowseContext>) super.getConnection();
    }

    @Override
    public ObjectTypes getObjectTypes() {
        return new ObjectTypes().withTypes(new ObjectType()
                .withId(JevConstants.OBJECT_TYPE_REVIEW)
                .withLabel("Document Review")
                .withHelpText("Send a text document to JEV and receive structured, confidence-gated answers"));
    }

    @Override
    public ObjectDefinitions getObjectDefinitions(String objectTypeId, Collection<ObjectDefinitionRole> roles) {
        ObjectDefinitions definitions = new ObjectDefinitions();
        for (ObjectDefinitionRole role : roles) {
            if (role == ObjectDefinitionRole.INPUT) {
                definitions.withDefinitions(new ObjectDefinition()
                        .withInputType(ContentType.BINARY)
                        .withOutputType(ContentType.NONE));
            } else {
                definitions.withDefinitions(new ObjectDefinition()
                        .withInputType(ContentType.NONE)
                        .withOutputType(ContentType.JSON)
                        .withElementName("")
                        .withJsonSchema(OutputSchema.build(configuredQuestions())));
            }
        }
        return definitions;
    }

    /** The Question Set from the operation being imported, or null if absent/invalid (generic schema is used). */
    private QuestionSet configuredQuestions() {
        String mode = getContext().getOperationProperties().getProperty(JevConstants.REQUEST_MODE);
        String json = getContext().getOperationProperties().getProperty(JevConstants.QUESTION_SET);
        if (RequestMode.fromValue(mode) != RequestMode.DOCUMENT_REVIEW || json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return QuestionSet.parse(json);
        } catch (InvalidInputException e) {
            throw new ConnectorException("Cannot import profile: " + e.getMessage(), e);
        }
    }

    /** Sends a one-question request to confirm the URL, credentials and model are accepted. */
    @Override
    public void testConnection() {
        JevClient client = getConnection().createClient();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", client.getSettings().getDefaultModel());
        request.put("state", "Boomi connector connection test.");
        ObjectNode question = request.putObject("questions").putObject("connection_test");
        question.put("type", QuestionSet.TYPE_NOUL);
        question.put("instructions", "Is this text a connection test?");
        try {
            JevHttpResponse response = client.decide(request);
            if (!response.isSuccess()) {
                throw new ConnectorException(String.valueOf(response.getStatusCode()),
                        "JEV rejected the test request: " + response.errorMessage());
            }
        } catch (IOException e) {
            throw new ConnectorException("Could not reach JEV at " + client.getSettings().getEndpoint() + ": " + e, e);
        }
    }
}
