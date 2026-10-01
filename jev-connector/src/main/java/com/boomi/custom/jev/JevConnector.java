package com.boomi.custom.jev;

import com.boomi.connector.api.BrowseContext;
import com.boomi.connector.api.Browser;
import com.boomi.connector.api.Operation;
import com.boomi.connector.api.OperationContext;
import com.boomi.connector.util.BaseConnector;

/**
 * Entry point named in {@code connector-config.xml}.
 */
public class JevConnector extends BaseConnector {

    @Override
    public Browser createBrowser(BrowseContext context) {
        return new JevBrowser(new JevConnection<>(context));
    }

    @Override
    protected Operation createExecuteOperation(OperationContext context) {
        return new JevReviewOperation(new JevConnection<>(context));
    }
}
