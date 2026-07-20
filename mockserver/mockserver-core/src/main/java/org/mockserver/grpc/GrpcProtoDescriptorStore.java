package org.mockserver.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.TypeRegistry;
import org.mockserver.logging.MockServerLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GrpcProtoDescriptorStore {

    private final MockServerLogger mockServerLogger;
    private final ConcurrentHashMap<String, Descriptors.ServiceDescriptor> services = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Descriptors.FileDescriptor> fileDescriptors = new ConcurrentHashMap<>();
    private final GrpcExampleSynthesizer exampleSynthesizer = new GrpcExampleSynthesizer();
    private volatile GrpcJsonMessageConverter converter;

    public GrpcProtoDescriptorStore(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
        rebuildConverter();
    }

    public void loadDescriptorSet(byte[] descriptorSetBytes) {
        try {
            DescriptorProtos.FileDescriptorSet fileDescriptorSet =
                DescriptorProtos.FileDescriptorSet.parseFrom(descriptorSetBytes);
            loadFileDescriptorSet(fileDescriptorSet);
        } catch (Exception e) {
            throw new GrpcException("Failed to load proto descriptor set", e);
        }
    }

    public void loadDescriptorSetFromPath(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            DescriptorProtos.FileDescriptorSet fileDescriptorSet =
                DescriptorProtos.FileDescriptorSet.parseFrom(is);
            loadFileDescriptorSet(fileDescriptorSet);
        } catch (Exception e) {
            throw new GrpcException("Failed to load proto descriptor set from " + path, e);
        }
    }

    public void loadDescriptorDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{dsc,desc}")) {
            for (Path entry : stream) {
                try {
                    loadDescriptorSetFromPath(entry);
                } catch (Exception e) {
                    mockServerLogger.logEvent(
                        new org.mockserver.log.model.LogEntry()
                            .setType(org.mockserver.log.model.LogEntry.LogMessageType.WARN)
                            // setType alone does NOT raise the level: LogEntry.logLevel defaults to
                            // INFO and MockServerLogger gates on getLogLevel(), so without this the
                            // WARN is silent at global log level WARN/ERROR — exactly when the
                            // fail-safe most needs to be diagnosable.
                            .setLogLevel(org.slf4j.event.Level.WARN)
                            .setMessageFormat("failed to load gRPC descriptor from {}:{}")
                            .setArguments(entry, e.getMessage())
                    );
                }
            }
        } catch (IOException e) {
            throw new GrpcException("Failed to scan descriptor directory " + directory, e);
        }
    }

    private synchronized void loadFileDescriptorSet(DescriptorProtos.FileDescriptorSet fileDescriptorSet) {
        Map<String, DescriptorProtos.FileDescriptorProto> protoByName = new LinkedHashMap<>();
        for (DescriptorProtos.FileDescriptorProto proto : fileDescriptorSet.getFileList()) {
            protoByName.put(proto.getName(), proto);
        }

        for (DescriptorProtos.FileDescriptorProto proto : fileDescriptorSet.getFileList()) {
            resolveAndRegister(proto, protoByName);
        }

        rebuildConverter();
    }

    private Descriptors.FileDescriptor resolveAndRegister(
        DescriptorProtos.FileDescriptorProto proto,
        Map<String, DescriptorProtos.FileDescriptorProto> protoByName
    ) {
        String name = proto.getName();
        Descriptors.FileDescriptor existing = fileDescriptors.get(name);
        if (existing != null) {
            return existing;
        }

        List<Descriptors.FileDescriptor> dependencies = new ArrayList<>();
        for (String depName : proto.getDependencyList()) {
            DescriptorProtos.FileDescriptorProto depProto = protoByName.get(depName);
            if (depProto != null) {
                dependencies.add(resolveAndRegister(depProto, protoByName));
            }
        }

        try {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                proto, dependencies.toArray(new Descriptors.FileDescriptor[0])
            );
            fileDescriptors.put(name, fileDescriptor);

            for (Descriptors.ServiceDescriptor service : fileDescriptor.getServices()) {
                services.put(service.getFullName(), service);
            }

            return fileDescriptor;
        } catch (Descriptors.DescriptorValidationException e) {
            throw new GrpcException("Failed to resolve proto file descriptor: " + name, e);
        }
    }

    private void rebuildConverter() {
        TypeRegistry.Builder registryBuilder = TypeRegistry.newBuilder();
        for (Descriptors.FileDescriptor fd : fileDescriptors.values()) {
            registryBuilder.add(fd.getMessageTypes());
        }
        converter = new GrpcJsonMessageConverter(registryBuilder.build());
    }

    public Descriptors.ServiceDescriptor getService(String fullName) {
        return services.get(fullName);
    }

    public Descriptors.MethodDescriptor getMethod(String serviceName, String methodName) {
        Descriptors.ServiceDescriptor service = services.get(serviceName);
        if (service == null) {
            return null;
        }
        return service.findMethodByName(methodName);
    }

    public Map<String, Descriptors.ServiceDescriptor> getAllServices() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /**
     * The logger this store was constructed with, so the stateless translators that take a store
     * (for example {@link GrpcForwardTranslator}) can report a fail-safe fallback instead of
     * swallowing it silently.
     */
    public MockServerLogger getMockServerLogger() {
        return mockServerLogger;
    }

    public GrpcJsonMessageConverter getConverter() {
        return converter;
    }

    public GrpcExampleSynthesizer getExampleSynthesizer() {
        return exampleSynthesizer;
    }

    /**
     * Synthesize a schema-valid protobuf-JSON example for the output (response) message of
     * the supplied service/method, using the loaded descriptors. Returns {@code null} when
     * the method is unknown, so the caller can fall back to existing behaviour.
     *
     * <p>This backs the "no hand-authored body" path: a matched gRPC expectation whose
     * response has no explicit body can return this synthesized message instead of an empty
     * response.
     */
    public String synthesizeResponseJson(String serviceName, String methodName) {
        Descriptors.MethodDescriptor method = getMethod(serviceName, methodName);
        if (method == null) {
            return null;
        }
        return exampleSynthesizer.synthesizeJson(method.getOutputType(), converter);
    }

    public boolean hasServices() {
        return !services.isEmpty();
    }

    public void removeService(String fullName) {
        services.remove(fullName);
    }

    public synchronized void reset() {
        services.clear();
        fileDescriptors.clear();
        rebuildConverter();
    }
}
