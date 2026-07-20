# gRPC test fixtures

Each `.dsc` here is a compiled protobuf `FileDescriptorSet`, committed alongside the `.proto` it
was generated from. The tests load the `.dsc`; the `.proto` exists so the fixture can be read and
regenerated deterministically.

## Regenerating

Run from this directory. `protoc` must match the `protobuf.version` property in the root
`mockserver/pom.xml` — currently **4.35.1** (`protoc --version` reports `libprotoc 35.1`), so a
descriptor set regenerated with a matching toolchain is byte-identical to what is committed.

| Fixture | Command |
|---|---|
| `greeting.dsc` | `protoc --descriptor_set_out=greeting.dsc -I. greeting.proto` |
| `catalog.dsc` | `protoc --descriptor_set_out=catalog.dsc -I. catalog.proto` |
| `orders.dsc` | `protoc --include_imports --descriptor_set_out=orders.dsc -I. -I$(brew --prefix)/include orders.proto` |

`orders.proto` is the only fixture with imports, so it is the only one needing
`--include_imports` (which bundles the transitive dependencies — `orders_common.proto` and the
`google/protobuf/*` well-known types — into the set) and a second `-I` pointing at protoc's
bundled well-known `.proto` files. On a non-Homebrew install, substitute the include directory
that contains `google/protobuf/timestamp.proto`.

Verify a regeneration did not drift:

```bash
protoc --descriptor_set_out=/tmp/greeting.dsc -I. greeting.proto && cmp greeting.dsc /tmp/greeting.dsc
```

## What each fixture covers

| Fixture | Covers |
|---|---|
| `greeting.proto` | The four RPC shapes — unary, server-streaming, client-streaming, bidi. The default fixture for most gRPC tests. |
| `catalog.proto` | A second, unrelated service, for multi-service and service-removal cases. |
| `orders.proto` | A **local import** (`orders_common.proto`), which exercises `GrpcProtoDescriptorStore`'s dependency-resolution recursion; and the **well-known types** (`Timestamp`, `Duration`, and the `StringValue`/`BoolValue`/`Int32Value` wrappers), which exercise `GrpcExampleSynthesizer.wellKnownValue`. Neither path is reachable from `greeting.proto` or `catalog.proto`, as both are import-free and use only scalar fields. |
