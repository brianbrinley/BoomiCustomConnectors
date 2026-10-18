package com.boomi.connector.muse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.impl.client.CloseableHttpClient;

import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.OperationStatus;
import com.boomi.connector.api.UpdateRequest;
import com.boomi.connector.util.PayloadUtil;
import com.boomi.util.IOUtil;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * "List Models": asks the API which models the key can reach. The input
 * document is ignored — send one to trigger the call — and each input gets
 * one output document shaped like {@code {"models":[{"id":...}]}}. Handy
 * for picking the model name for the chat operations without leaving Boomi.
 */
public class MuseListModelsOperation extends MuseBaseOperation {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(MuseListModelsOperation.class.getName());

    public MuseListModelsOperation(OperationContext context, MuseConnector connector) {
        super(context, connector);
    }

    @Override
    protected void executeUpdate(UpdateRequest request, OperationResponse response) {
        MuseConnection conn = connection();
        CloseableHttpClient http = httpClient(conn);

        for (ObjectData input : request) {
            try {
                List<MuseClient.ModelInfo> models = MuseClient.listModels(http, conn);
                ObjectNode out = MuseClient.MAPPER.createObjectNode();
                ArrayNode arr = out.putArray("models");
                for (MuseClient.ModelInfo m : models) {
                    ObjectNode node = arr.addObject();
                    node.put("id", m.id);
                    if (m.ownedBy != null) {
                        node.put("ownedBy", m.ownedBy);
                    } else {
                        node.putNull("ownedBy");
                    }
                }
                // Explicit charset: the platform reads result payloads via
                // writeTo(), and the charset pins the encoding it sees.
                response.addResult(input, OperationStatus.SUCCESS, "200", "OK",
                        PayloadUtil.toPayload(out.toString(), StandardCharsets.UTF_8));
            } catch (MuseApiException e) {
                if (e.isAuthFailure()) {
                    // The key is wrong: fail the execution, like the chat
                    // operations do, instead of stamping every document.
                    throw new ConnectorException(
                            "Muse authentication failed (HTTP " + e.getStatusCode()
                                    + "). Check the API key in the connection.",
                            e);
                }
                response.addResult(input, OperationStatus.APPLICATION_ERROR,
                        String.valueOf(e.getStatusCode()), e.getMessage(), null);
            } catch (Exception e) {
                // Never let one document's surprise kill the batch.
                LOG.log(Level.SEVERE, "List Models failed", e);
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                response.addResult(input, OperationStatus.FAILURE, "-1", message, null);
            } finally {
                IOUtil.closeQuietly(input.getData());
            }
        }
    }
}
