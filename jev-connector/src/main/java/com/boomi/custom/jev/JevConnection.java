package com.boomi.custom.jev;

import com.boomi.connector.api.BrowseContext;
import com.boomi.connector.util.BaseConnection;
import com.boomi.custom.jev.client.JevClient;
import com.boomi.custom.jev.client.JevSettings;

/**
 * Reads the connection fields and creates JEV clients.
 */
public class JevConnection<C extends BrowseContext> extends BaseConnection<C> {

    public JevConnection(C context) {
        super(context);
    }

    public JevSettings getSettings() {
        return JevSettings.from(getContext().getConnectionProperties());
    }

    public JevClient createClient() {
        return new JevClient(getSettings());
    }
}
