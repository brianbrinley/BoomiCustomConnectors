package com.boomi.connector.muse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.apache.http.impl.client.CloseableHttpClient;

import com.boomi.connector.api.ConnectorException;
import com.boomi.connector.api.ObjectData;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.api.OperationResponse;
import com.boomi.connector.api.UpdateRequest;

/**
 * "Bulk Chat Completion": every input document gets its own Muse API call,
 * sent with bounded parallelism. Results come back in document order — one
 * result per input, never a silent skip — and a failed document never takes
 * down its neighbors.
 */
public class MuseBulkChatOperation extends MuseBaseOperation {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Logger.getLogger(MuseBulkChatOperation.class.getName());

    public MuseBulkChatOperation(OperationContext context, MuseConnector connector) {
        super(context, connector);
    }

    @Override
    protected void executeUpdate(UpdateRequest request, OperationResponse response) {
        MuseConnection conn = connection();
        final CloseableHttpClient http = httpClient(conn);
        final BudgetTracker budget = new BudgetTracker(conn);

        List<ObjectData> inputs = new ArrayList<>();
        for (ObjectData input : request) {
            inputs.add(input);
        }

        int threads = Math.max(1, Math.min(conn.getBulkMaxConcurrency(), inputs.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private final AtomicInteger _n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "muse-bulk-" + _n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        List<Future<DocOutcome>> futures = new ArrayList<>(inputs.size());
        try {
            for (final ObjectData input : inputs) {
                Callable<DocOutcome> task = new Callable<DocOutcome>() {
                    @Override
                    public DocOutcome call() {
                        return runOne(input, http, conn, budget);
                    }
                };
                futures.add(pool.submit(task));
            }

            for (int i = 0; i < inputs.size(); i++) {
                final DocOutcome outcome;
                try {
                    outcome = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAll(futures);
                    throw new ConnectorException("Bulk execution was interrupted.", e);
                } catch (ExecutionException e) {
                    // runOne already converts everything to DocOutcome; this
                    // is unreachable in practice, but never let a document vanish.
                    cancelAll(futures);
                    throw new ConnectorException("Bulk execution failed unexpectedly.", e.getCause());
                }
                if (outcome.kind == DocOutcome.Kind.AUTH_FATAL) {
                    // The key is wrong: every document would fail the same
                    // way, so stop here instead of burning the rest.
                    cancelAll(futures);
                    recordOutcome(inputs.get(i), outcome, response);
                    return;
                }
                recordOutcome(inputs.get(i), outcome, response);
            }
        } finally {
            pool.shutdownNow();
        }

        LOG.fine("Muse bulk execution used " + budget.summary());
    }

    private static void cancelAll(List<Future<DocOutcome>> futures) {
        for (Future<DocOutcome> f : futures) {
            f.cancel(true);
        }
    }
}
