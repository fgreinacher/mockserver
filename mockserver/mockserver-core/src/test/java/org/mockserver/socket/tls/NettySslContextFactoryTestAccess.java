package org.mockserver.socket.tls;

import io.netty.handler.ssl.SslContext;

/**
 * Lets tests in other packages (e.g. {@code bouncycastle}, which owns the renewal clock) observe the
 * package-private cached-context check.
 */
public final class NettySslContextFactoryTestAccess {

    private NettySslContextFactoryTestAccess() {
    }

    public static SslContext cachedServerSslContext(NettySslContextFactory nettySslContextFactory) {
        return nettySslContextFactory.cachedServerSslContext();
    }
}
