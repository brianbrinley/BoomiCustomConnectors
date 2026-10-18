package com.boomi.connector.muse;

import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.impl.client.CloseableHttpClient;

import com.boomi.connector.api.BrowseContext;
import com.boomi.connector.api.Browser;
import com.boomi.connector.api.ConnectorContext;
import com.boomi.connector.api.Operation;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.util.BaseConnector;

/**
 * Entry point for the Muse connector. One instance is shared across
 * connections and threads, so everything cached here must be thread-safe and
 * must never hold account-specific state.
 *
 * The pooled HTTP client lives in the {@link ConnectorContext} cache rather
 * than on this class: one pool per connection, honoring that connection's
 * pool settings, each shared by every execution on this Atom. A pool is
 * rebuilt when its settings change and the old one is shut down.
 */
public class MuseConnector extends BaseConnector {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(MuseConnector.class.getName());

    private static final String CLIENT_CACHE_KEY = MuseConnector.class.getName() + ".httpClient";

    @Override
    protected Operation createExecuteOperation(OperationContext context) {
        // getCustomOperationType() is null when the descriptor omits it;
        // default to the single chat operation.
        if ("bulkChat".equals(context.getCustomOperationType())) {
            return new MuseBulkChatOperation(context, this);
        }
        if ("listModels".equals(context.getCustomOperationType())) {
            return new MuseListModelsOperation(context, this);
        }
        return new MuseExecuteOperation(context, this);
    }

    @Override
    public Browser createBrowser(BrowseContext context) {
        return new MuseBrowser(context);
    }

    /**
     * Returns the pooled HTTP client for this connection, building it on
     * first use. Thread-safe: at most one extra client is ever built under
     * contention, and the loser is shut down immediately.
     */
    CloseableHttpClient httpClientFor(ConnectorContext context, MuseConnection conn) {
        ConcurrentMap<Object, Object> cache = context.getConnectorCache();
        String signature = conn.poolSignature();

        PooledClient current = (PooledClient) cache.get(CLIENT_CACHE_KEY);
        if (current != null && current.signature.equals(signature)) {
            return current.client;
        }

        PooledClient fresh = new PooledClient(signature, newPooledClient(conn));
        Object previous = cache.putIfAbsent(CLIENT_CACHE_KEY, fresh);
        if (previous == null) {
            return fresh.client;
        }
        PooledClient existing = (PooledClient) previous;
        if (existing.signature.equals(signature)) {
            closeQuietly(fresh.client);
            return existing.client;
        }
        if (cache.replace(CLIENT_CACHE_KEY, existing, fresh)) {
            closeQuietly(existing.client);
            return fresh.client;
        }
        closeQuietly(fresh.client);
        return httpClientFor(context, conn);
    }

    /**
     * Builds the pooled client. Separated for testability; production code
     * always gets the standard pooled client.
     */
    CloseableHttpClient newPooledClient(MuseConnection conn) {
        return MuseClient.newPooledClient(conn);
    }

    private static void closeQuietly(CloseableHttpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error closing replaced HTTP client", e);
        }
    }

    private static final class PooledClient {
        final String signature;
        final CloseableHttpClient client;

        PooledClient(String signature, CloseableHttpClient client) {
            this.signature = signature;
            this.client = client;
        }
    }
}
