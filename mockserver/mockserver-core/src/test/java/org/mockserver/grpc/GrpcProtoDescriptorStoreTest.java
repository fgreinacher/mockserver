package org.mockserver.grpc;

import com.google.protobuf.Descriptors;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrpcProtoDescriptorStoreTest {

    private GrpcProtoDescriptorStore store;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
    }

    @Test
    public void shouldLoadDescriptorFromPath() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);

        assertThat(store.hasServices(), is(true));
        assertThat(store.getService("com.example.grpc.GreetingService"), is(notNullValue()));
    }

    @Test
    public void shouldResolveServiceMethods() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);

        Descriptors.MethodDescriptor greeting = store.getMethod("com.example.grpc.GreetingService", "Greeting");
        assertThat(greeting, is(notNullValue()));
        assertThat(greeting.getName(), is("Greeting"));
        assertThat(greeting.getInputType().getName(), is("HelloRequest"));
        assertThat(greeting.getOutputType().getName(), is("HelloResponse"));
        assertThat(greeting.isClientStreaming(), is(false));
        assertThat(greeting.isServerStreaming(), is(false));
    }

    @Test
    public void shouldResolveStreamingMethods() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);

        Descriptors.MethodDescriptor serverStreaming = store.getMethod("com.example.grpc.GreetingService", "ListGreetings");
        assertThat(serverStreaming, is(notNullValue()));
        assertThat(serverStreaming.isServerStreaming(), is(true));
        assertThat(serverStreaming.isClientStreaming(), is(false));

        Descriptors.MethodDescriptor clientStreaming = store.getMethod("com.example.grpc.GreetingService", "CollectGreetings");
        assertThat(clientStreaming, is(notNullValue()));
        assertThat(clientStreaming.isClientStreaming(), is(true));
        assertThat(clientStreaming.isServerStreaming(), is(false));

        Descriptors.MethodDescriptor bidi = store.getMethod("com.example.grpc.GreetingService", "Chat");
        assertThat(bidi, is(notNullValue()));
        assertThat(bidi.isClientStreaming(), is(true));
        assertThat(bidi.isServerStreaming(), is(true));
    }

    @Test
    public void shouldListAllServices() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);

        Map<String, Descriptors.ServiceDescriptor> allServices = store.getAllServices();
        assertThat(allServices.size(), is(1));
        assertThat(allServices.containsKey("com.example.grpc.GreetingService"), is(true));
    }

    @Test
    public void shouldReturnNullForUnknownService() {
        assertThat(store.getService("com.example.Unknown"), is(nullValue()));
        assertThat(store.getMethod("com.example.Unknown", "foo"), is(nullValue()));
    }

    @Test
    public void shouldReset() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);
        assertThat(store.hasServices(), is(true));

        store.reset();
        assertThat(store.hasServices(), is(false));
    }

    @Test
    public void shouldProvideJsonConverter() {
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);

        GrpcJsonMessageConverter converter = store.getConverter();
        assertThat(converter, is(notNullValue()));
    }

    @Test
    public void shouldLoadDescriptorDirectory() {
        Path directory = Paths.get("src/test/resources/grpc");
        store.loadDescriptorDirectory(directory);

        assertThat(store.hasServices(), is(true));
        assertThat(store.getService("com.example.grpc.GreetingService"), is(notNullValue()));
    }

    @Test
    public void shouldHandleNullDirectory() {
        store.loadDescriptorDirectory(null);
        assertThat(store.hasServices(), is(false));
    }

    // --- dependency resolution ---

    /**
     * {@code greeting.proto} and {@code catalog.proto} have no imports, so
     * {@code resolveAndRegister}'s recursion over {@code getDependencyList()} was never entered by
     * any test -- a descriptor set whose files depend on one another would have failed to build
     * with nothing catching it. {@code orders.proto} imports a local file AND three well-known
     * type files, so loading it walks the dependency graph.
     */
    @Test
    public void shouldResolveDescriptorsWithImportedDependencies() {
        store.loadDescriptorSetFromPath(Paths.get("src/test/resources/grpc/orders.dsc"));

        assertThat(store.hasServices(), is(true));
        Descriptors.MethodDescriptor getOrder = store.getMethod("com.example.orders.OrderService", "GetOrder");
        assertThat(getOrder, is(notNullValue()));

        // a field whose type lives in the IMPORTED file must resolve -- this is what fails if the
        // dependency was not registered before the dependent file was built
        Descriptors.FieldDescriptor total = getOrder.getOutputType().findFieldByName("total");
        assertThat(total, is(notNullValue()));
        assertThat(total.getMessageType().getFullName(), is("com.example.orders.common.Money"));
        assertThat("the imported file must itself be registered",
            total.getMessageType().getFile().getName(), is("orders_common.proto"));

        // and a well-known type imported from google/protobuf must resolve too
        Descriptors.FieldDescriptor placedAt = getOrder.getOutputType().findFieldByName("placed_at");
        assertThat(placedAt.getMessageType().getFullName(), is("google.protobuf.Timestamp"));
    }

    /**
     * Loading the whole directory must resolve the dependent set as well as the flat ones,
     * regardless of the order the files happen to be streamed in.
     */
    @Test
    public void shouldResolveImportedDependenciesWhenLoadingTheDirectory() {
        store.loadDescriptorDirectory(Paths.get("src/test/resources/grpc"));

        assertThat(store.getService("com.example.orders.OrderService"), is(notNullValue()));
        assertThat(store.getService("com.example.grpc.GreetingService"), is(notNullValue()));
    }
}
