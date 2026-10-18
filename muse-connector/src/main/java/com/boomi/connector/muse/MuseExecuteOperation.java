package com.boomi.connector.muse;

import java.util.logging.Logger;

import org.apache.http.impl.client.CloseableHttpClient;

import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.UpdateRequest;

/**
 * "Chat Completion": one input document, one Muse API call, one output
 * document. Documents are processed sequentially in arrival order.
 */
public class MuseExecuteOperation extends MuseBaseOperation {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(MuseExecuteOperation.class.getName());

    public MuseExecuteOperation(OperationContext context, MuseConnector connector) {
        super(context, connector);
    }

    @Override
    protected void executeUpdate(UpdateRequest request, OperationResponse response) {
        MuseConnection conn = connection();
        CloseableHttpClient http = httpClient(conn);
        BudgetTracker budget = new BudgetTracker(conn);

        for (ObjectData input : request) {
            // runOne never throws for per-document problems; only an auth
            // failure escapes, via recordOutcome, and fails the execution.
            recordOutcome(input, runOne(input, http, conn, budget), response);
        }

        LOG.fine("Muse chat execution used " + budget.summary());
    }
}
