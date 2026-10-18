package com.boomi.connector.muse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.http.impl.client.CloseableHttpClient;

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

/**
 * Serves the Import wizard's object types and the Test Connection probe.
 * The schemas are static: the request and response shapes are fixed by the
 * connector, so there is nothing to discover from the API.
 */
public class MuseBrowser extends BaseBrowser implements ConnectionTester {

    private static final long serialVersionUID = 1L;

    private static final String REQUEST_TYPE_ID = "chatRequest";
    private static final String RESPONSE_TYPE_ID = "chatResponse";
    private static final String MODEL_LIST_TYPE_ID = "modelList";

    private static final String REQUEST_SCHEMA = "{"
            + "\"$schema\":\"http://json-schema.org/draft-04/schema#\","
            + "\"definitions\":{"
            + "\"ChatRequest\":{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"prompt\":{\"type\":\"string\",\"description\":\"The user message. Omit when messages is set.\"},"
            + "\"system\":{\"type\":\"string\",\"description\":\"Instructions the model follows.\"},"
            + "\"messages\":{\"type\":\"array\",\"items\":{\"type\":\"object\"},"
            + "\"description\":\"Full conversation in OpenAI chat format. Overrides prompt.\"},"
            + "\"model\":{\"type\":\"string\",\"description\":\"Model for this document. Defaults to the connection's model.\"},"
            + "\"temperature\":{\"type\":\"number\",\"description\":\"0 for focused, up to 2 for adventurous.\"},"
            + "\"maxTokens\":{\"type\":\"integer\",\"description\":\"Caps the response length.\"},"
            + "\"responseFormat\":{\"type\":\"string\",\"description\":\"\\\"text\\\" or \\\"json\\\".\"}"
            + "}"
            + "}"
            + "}"
            + "}";

    private static final String RESPONSE_SCHEMA = "{"
            + "\"$schema\":\"http://json-schema.org/draft-04/schema#\","
            + "\"definitions\":{"
            + "\"ChatResponse\":{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"id\":{\"type\":\"string\"},"
            + "\"model\":{\"type\":\"string\"},"
            + "\"content\":{\"type\":[\"string\",\"null\"]},"
            + "\"finishReason\":{\"type\":[\"string\",\"null\"]},"
            + "\"usage\":{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"promptTokens\":{\"type\":\"integer\"},"
            + "\"completionTokens\":{\"type\":\"integer\"},"
            + "\"totalTokens\":{\"type\":\"integer\"}"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}";

    private static final String MODEL_LIST_INPUT_SCHEMA = "{"
            + "\"$schema\":\"http://json-schema.org/draft-04/schema#\","
            + "\"definitions\":{"
            + "\"ModelListInput\":{"
            + "\"type\":\"object\","
            + "\"description\":\"Ignored. Send any single document to trigger the call.\""
            + "}"
            + "}"
            + "}";

    private static final String MODEL_LIST_SCHEMA = "{"
            + "\"$schema\":\"http://json-schema.org/draft-04/schema#\","
            + "\"definitions\":{"
            + "\"ModelList\":{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"models\":{"
            + "\"type\":\"array\","
            + "\"items\":{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"id\":{\"type\":\"string\","
            + "\"description\":\"Model name to use in the chat operations.\"},"
            + "\"ownedBy\":{\"type\":[\"string\",\"null\"]}"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}"
            + "}";

    public MuseBrowser(BrowseContext context) {
        super(context);
    }

    @Override
    public ObjectTypes getObjectTypes() {
        List<ObjectType> types = new ArrayList<>();
        types.add(new ObjectType().withId(REQUEST_TYPE_ID).withLabel("Chat Request")
                .withHelpText("The JSON document sent to the Chat Completion operations."));
        types.add(new ObjectType().withId(RESPONSE_TYPE_ID).withLabel("Chat Response")
                .withHelpText("The JSON document the connector returns, with token usage."));
        types.add(new ObjectType().withId(MODEL_LIST_TYPE_ID).withLabel("Model List")
                .withHelpText("The models your API key can reach, as returned by List Models."));
        return new ObjectTypes().withTypes(types).withTotalCount(types.size());
    }

    @Override
    public ObjectDefinitions getObjectDefinitions(String objectTypeId,
            Collection<ObjectDefinitionRole> roles) {
        ObjectDefinitions result = new ObjectDefinitions();
        boolean wantInput = roles.contains(ObjectDefinitionRole.INPUT);
        boolean wantOutput = roles.contains(ObjectDefinitionRole.OUTPUT);

        if (REQUEST_TYPE_ID.equals(objectTypeId) && (wantInput || roles.isEmpty())) {
            ObjectDefinition def = new ObjectDefinition();
            def.setJsonSchema(REQUEST_SCHEMA);
            // Mandatory, and an RFC 6901 pointer: "/definitions/ChatRequest".
            def.setElementName("/definitions/ChatRequest");
            def.setInputType(ContentType.JSON);
            result.getDefinitions().add(def);
        }
        if (RESPONSE_TYPE_ID.equals(objectTypeId) && (wantOutput || roles.isEmpty())) {
            ObjectDefinition def = new ObjectDefinition();
            def.setJsonSchema(RESPONSE_SCHEMA);
            def.setElementName("/definitions/ChatResponse");
            def.setOutputType(ContentType.JSON);
            result.getDefinitions().add(def);
        }
        if (MODEL_LIST_TYPE_ID.equals(objectTypeId) && (wantInput || roles.isEmpty())) {
            ObjectDefinition def = new ObjectDefinition();
            def.setJsonSchema(MODEL_LIST_INPUT_SCHEMA);
            def.setElementName("/definitions/ModelListInput");
            def.setInputType(ContentType.JSON);
            result.getDefinitions().add(def);
        }
        if (MODEL_LIST_TYPE_ID.equals(objectTypeId) && (wantOutput || roles.isEmpty())) {
            ObjectDefinition def = new ObjectDefinition();
            def.setJsonSchema(MODEL_LIST_SCHEMA);
            def.setElementName("/definitions/ModelList");
            def.setOutputType(ContentType.JSON);
            result.getDefinitions().add(def);
        }
        return result;
    }

    @Override
    public void testConnection() {
        final MuseConnection conn;
        try {
            conn = MuseConnection.from(getContext().getConnectionProperties());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(e.getMessage(), e);
        }
        // Test Connection is infrequent: a short-lived client is fine, and it
        // keeps pooled state out of the browse path entirely.
        try (CloseableHttpClient http = MuseClient.newPooledClient(conn)) {
            MuseClient.probe(http, conn);
        } catch (MuseApiException e) {
            throw new ConnectorException(e.getMessage(), e);
        } catch (IOException e) {
            throw new ConnectorException("Could not reach the Muse API: " + e.getMessage(), e);
        }
    }
}
