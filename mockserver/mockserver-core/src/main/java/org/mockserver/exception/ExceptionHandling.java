package org.mockserver.exception;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.NotSslRecordException;
import io.netty.util.internal.PlatformDependent;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.SniHandler;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.security.SignatureException;
import java.security.cert.CertPathValidatorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.slf4j.event.Level.WARN;

public class ExceptionHandling {

    static MockServerLogger mockServerLogger = new MockServerLogger();

    private static final Pattern IGNORABLE_CLASS_IN_STACK = Pattern.compile("^.*(?:Socket|Datagram|Sctp|Udt)Channel.*$");
    private static final Pattern IGNORABLE_ERROR_MESSAGE = Pattern.compile("^.*(?:connection.*(?:reset|closed|abort|broken)|broken.*pipe).*$", Pattern.CASE_INSENSITIVE);


    public static <T> T handleThrowable(CompletableFuture<T> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
            throw new RuntimeException(throwable);
        }
    }

    public static <T> T handleThrowable(Callable<T> callable) {
        try {
            return callable.call();
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
            throw new RuntimeException(throwable);
        }
    }

    public static void swallowThrowable(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (MockServerLogger.isEnabled(WARN) && mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> extends Consumer<T> {

        @Override
        default void accept(final T elem) {
            try {
                acceptThrows(elem);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        void acceptThrows(T elem) throws Exception;
    }

    /**
     * Returns a log suffix identifying the SNI hostname of the failed connection, formatted as
     * {@code " (SNI: <host>)"}, using the first non-null SNI hostname found across the given channels,
     * or {@code ""} when none of the channels carried an SNI hostname. Several channels can be passed
     * (e.g. relay handlers hold both an upstream and a downstream channel) so the SNI is found
     * whichever channel recorded it. Null-safe — a {@code null} array, or {@code null} elements within
     * it, are skipped.
     *
     * @param channels the channels to search for a recorded SNI hostname
     * @return {@code " (SNI: <host>)"} for the first hostname found, otherwise {@code ""}
     */
    public static String sniDescription(Channel... channels) {
        if (channels != null) {
            for (Channel channel : channels) {
                String sniHostname = SniHandler.getSniHostname(channel);
                if (sniHostname != null) {
                    return " (SNI: " + sniHostname + ")";
                }
            }
        }
        return "";
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    public static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * returns true is the exception was caused by the connection being closed
     */
    public static boolean connectionClosedException(Throwable throwable) {
        String message = String.valueOf(throwable.getMessage()).toLowerCase();

        // is ssl exception
        if (throwable.getCause() instanceof SSLException || throwable instanceof DecoderException || throwable instanceof NotSslRecordException) {
            return false;
        }

        // first try to match connection reset / broke peer based on the regex.
        // This is the fastest way but may fail on different jdk impls or OS's
        if (IGNORABLE_ERROR_MESSAGE.matcher(message).matches()) {
            return false;
        }

        // Inspect the StackTraceElements to see if it was a connection reset / broken pipe or not
        StackTraceElement[] elements = throwable.getStackTrace();
        for (StackTraceElement element : elements) {
            String classname = element.getClassName();
            String methodname = element.getMethodName();

            // skip all classes that belong to the io.netty package
            if (classname.startsWith("io.netty.")) {
                continue;
            }

            // check if the method name is read if not skip it
            if (!"read".equals(methodname)) {
                continue;
            }

            // This will also match against SocketInputStream which is used by openjdk 7 and maybe
            // also others
            if (IGNORABLE_CLASS_IN_STACK.matcher(classname).matches()) {
                return false;
            }

            try {
                // No match by now. Try to load the class via classloader and inspect it.
                // This is mainly done as other JDK implementations may differ in name of
                // the impl.
                Class<?> clazz = PlatformDependent.getClassLoader(ExceptionHandling.class).loadClass(classname);

                if (SocketChannel.class.isAssignableFrom(clazz)
                    || DatagramChannel.class.isAssignableFrom(clazz)) {
                    return false;
                }

                // also match against SctpChannel via String matching as it may not present.
                if (PlatformDependent.javaVersion() >= 7
                    && "com.sun.nio.sctp.SctpChannel".equals(clazz.getSuperclass().getName())) {
                    return false;
                }
            } catch (ClassNotFoundException e) {
                // This should not happen just ignore
            }
        }
        return true;
    }

    /**
     * returns true if the exception is a genuine SSL or decoder fault, i.e. the SSL/decoder subset
     * that {@link #connectionClosedException(Throwable)} returns false for at lines 123-124 (an
     * {@link SSLException} cause, a {@link DecoderException}, or a {@link NotSslRecordException}).
     * Note that {@code connectionClosedException} also returns false for benign connection resets
     * via its message-regex and stack-trace inspection; those are NOT matched here. Callers can use
     * this to surface real SSL/decoder faults at WARN rather than dropping them silently alongside
     * benign connection closes.
     */
    public static boolean isSslOrDecoderFault(Throwable throwable) {
        // mirrors the SSL/decoder predicate at connectionClosedException line 123. The
        // NotSslRecordException check is NOT redundant: it extends SSLException (not
        // DecoderException), and the first check inspects getCause() rather than the throwable
        // itself, so a directly-thrown NotSslRecordException (null/non-SSL cause) is only caught here.
        return throwable.getCause() instanceof SSLException
            || throwable instanceof DecoderException
            || throwable instanceof NotSslRecordException;
    }

    private static final List<Class<? extends Exception>> SSL_HANDSHAKE_FAILURE_CLASSES = Arrays.asList(SSLException.class, SSLHandshakeException.class, CertPathValidatorException.class, SignatureException.class);
    public static boolean sslHandshakeException(Throwable throwable) {
        for (Class<? extends Throwable> cause : getCauses(throwable)) {
            if (SSL_HANDSHAKE_FAILURE_CLASSES.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    private static final List<Class<? extends Exception>> CONNECTION_EXCEPTION_CLASSES = Arrays.asList(SocketConnectionException.class, ConnectException.class);
    public static boolean connectionException(Throwable throwable) {
        for (Class<? extends Throwable> cause : getCauses(throwable)) {
            if (CONNECTION_EXCEPTION_CLASSES.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<? extends Throwable>> getCauses(final Throwable throwable) {
        if (throwable.getCause() != null) {
            if (throwable.getClass().equals(throwable.getCause().getClass())) {
                return new ArrayList<>(Collections.singletonList(throwable.getClass()));
            } else {
                final List<Class<? extends Throwable>> causes = getCauses(throwable.getCause());
                causes.add(throwable.getClass());
                return causes;
            }
        } else {
            return new ArrayList<>(Collections.singletonList(throwable.getClass()));
        }
    }

}
