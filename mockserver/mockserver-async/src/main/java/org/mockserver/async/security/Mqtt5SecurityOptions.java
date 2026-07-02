package org.mockserver.async.security;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Utility for building an MQTT v5 {@link MqttConnectionOptions} from
 * {@link MqttSecurity} configuration — the v5 counterpart of
 * {@link MqttSecurityOptions}.
 */
public final class Mqtt5SecurityOptions {

    private Mqtt5SecurityOptions() {
        // utility class
    }

    /**
     * Build v5 connection options with security settings applied.
     * <p>
     * When security is null or empty, returns {@code null} so the caller can use
     * the no-arg {@code MqttClient.connect()} (preserving backward-compatible
     * behaviour with Paho defaults).
     *
     * @param security the MQTT security configuration (may be null)
     * @return configured connection options, or {@code null} when no security is needed
     */
    public static MqttConnectionOptions buildConnectOptions(MqttSecurity security) {
        if (security == null || security.isEmpty()) {
            return null;
        }

        MqttConnectionOptions options = new MqttConnectionOptions();

        if (security.getUsername() != null && !security.getUsername().isBlank()) {
            options.setUserName(security.getUsername());
        }
        if (security.getPassword() != null && !security.getPassword().isBlank()) {
            options.setPassword(security.getPassword().getBytes(StandardCharsets.UTF_8));
        }
        if (!security.getSslProperties().isEmpty()) {
            Properties sslProps = new Properties();
            sslProps.putAll(security.getSslProperties());
            options.setSSLProperties(sslProps);
        }

        return options;
    }
}
