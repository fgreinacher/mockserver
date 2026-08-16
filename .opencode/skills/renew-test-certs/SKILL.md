---
name: renew-test-certs
description: >
  Renews expiring TLS test certificates used by MockServer integration tests.
  Use when TLS tests fail with "Channel handler removed before valid response
  has been received", "Broken pipe", certificate expired errors, when the
  ":lock: certificate expiry guard" CI step fails or warns, or when a user says
  "renew certs", "certificates expired", "TLS tests failing".
---

# Renew Test TLS Certificates

MockServer's TLS/mTLS tests are pinned to committed CA-signed leaf certificates.
These have finite lifetimes and must be renewed before they expire. Expiry has
historically been discovered only by the build going red (2021-06, 2022-01,
2023-02, 2026-05) — the automated guard below now surfaces it early instead.

## Automated early-warning guard (run this first, and after every renewal)

`.buildkite/scripts/steps/check-certificate-expiry.sh` sweeps **every** committed
PEM, checks **every** certificate in each chain file, hard-fails under 30 days /
already-expired, warns under 180 days, asserts the intentional negative fixture is
still expired, and asserts `notAfter(leaf) <= notAfter(sibling ca.pem)`. It runs in
the Java CI pipeline. Run it locally any time:

```bash
.buildkite/scripts/steps/check-certificate-expiry.sh          # PASS/WARN/FAIL sweep
CERT_EXPIRY_HARD_FAIL_DAYS=40000 .buildkite/scripts/steps/check-certificate-expiry.sh   # self-test: should FAIL
```

## Longevity model (read this before you regenerate anything)

- **CAs are long-lived (10 years), leaves are shorter-lived (5 years).**
- **A leaf MUST expire strictly before the CA that signed it** —
  `notAfter(leaf) <= notAfter(ca.pem)`. This invariant is enforced by the guard.
  Historically the fixtures VIOLATED it (leaves ran to 2036 under CAs expiring
  2027/2028); pin `-days` on every re-issue so it can never happen again.
- **Prefer re-signing over regenerating keys.** The safe renewal below keeps every
  existing private key and only mints fresh certificates. Because the CA keeps its
  key, its Subject Key Identifier is unchanged, so every leaf's Authority Key
  Identifier still matches and existing signatures stay valid. This also sidesteps
  the PKCS#1 trap below entirely.

## The PKCS#1 vs PKCS#8 `-keyout` trap (do NOT regenerate keys casually)

`openssl req -newkey ... -keyout leaf-key.pem` under OpenSSL 3 writes the key in
**PKCS#8** (`-----BEGIN PRIVATE KEY-----`), even for RSA. Two fixtures depend on the
key encoding and will silently lose coverage if it flips:

- `authentication/mtls/leaf-key.pem` is **PKCS#1** (`-----BEGIN RSA PRIVATE KEY-----`)
  and is consumed by `PEMToFileTest` for traditional-RSA encoding coverage.
- The three netty `tls*/leaf-key.pem` files were **already destroyed this way in
  May 2026** — a `-keyout` regeneration rewrote them as PKCS#8, and they are now
  byte-identical to their `-pkcs8` siblings (the PKCS#1 variant coverage is gone).

**Therefore: do not regenerate keys as part of a routine renewal.** Re-sign from the
existing key (recipes below). If you ever must mint a new RSA key that has to stay
PKCS#1, convert it back explicitly: `openssl rsa -traditional -in k.pem -out k.pem`.

## Certificate Locations

Five certificate sets, each a CA plus the leaf it signs:

| Directory | Key Type | Leaf key encoding |
|-----------|----------|-------------------|
| `mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls/` | RSA 2048 | **PKCS#1** (keep) |
| `mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls/separateca/` | RSA 2048 | **PKCS#1** (keep) |
| `mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/` | RSA 2048 | PKCS#8 |
| `mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/ec/` | ECDSA P-256 | PKCS#8 |
| `mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/separateca/` | RSA 2048 | PKCS#8 |

Each directory contains:
- `ca.pem` / `ca-key.pem` / `ca-key-pkcs8.pem` — CA certificate + keys (10-year life)
- `leaf-cert.pem` / `leaf-key.pem` / `leaf-key-pkcs8.pem` — leaf certificate + keys (5-year life)
- `leaf-cert-chain.pem` — leaf + CA concatenated (rebuild whenever either changes)
- `csr.json` — reference config (cfssl-era; not used by the openssl recipes)

Subject DNs differ between sets and MUST be preserved:
- **mtls** CA and leaf subject: `C=UK, L=London, O=MockServer` (no CN).
- **netty** CA subject: `C=UK, L=London, O=MockServer`; leaf subject:
  `C=UK, L=London, O=MockServer, CN=www.mockserver.com`.

## Step 0: Set the repo root (used by every step; absolute paths avoid the `cd` trap)

The extension configs are written once at the repo root. Every recipe below `cd`s
into a cert directory, so it references those configs by **absolute** path — a
relative `.tmp/...` would resolve inside the cert dir, where it does not exist.

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
```

## Step 1: Check Certificate Expiry

```bash
for dir in \
  "$REPO_ROOT/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls" \
  "$REPO_ROOT/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls/separateca" \
  "$REPO_ROOT/mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls" \
  "$REPO_ROOT/mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/ec" \
  "$REPO_ROOT/mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/separateca"; do
  echo "=== $dir ==="
  echo -n "  CA:   "; openssl x509 -in "$dir/ca.pem" -noout -enddate
  echo -n "  Leaf: "; openssl x509 -in "$dir/leaf-cert.pem" -noout -enddate
done
```

## Step 2: Write the extension configs (at the repo root, referenced absolutely)

The leaf config MUST include `authorityKeyIdentifier` because the CA and leaf share
the same subject DN — without AKI, Java's trust manager treats the leaf as
self-signed.

```bash
mkdir -p "$REPO_ROOT/.tmp"

cat > "$REPO_ROOT/.tmp/ca-ext.cnf" << 'EOF'
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
EOF

cat > "$REPO_ROOT/.tmp/leaf-ext.cnf" << 'EOF'
[v3_leaf]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = DNS:example.com,DNS:www.example.com,DNS:localhost,IP:127.0.0.1,URI:https://www.example.com
EOF
```

## Step 3: Regenerate a CA in place (keeps the existing CA key → SKI unchanged)

Do this when a `ca.pem` is expiring. It re-self-signs the existing key with a fresh
10-year window; all leaves it previously signed still chain, because the key — and
therefore the SKI/AKI linkage — is unchanged. **Adjust `-subj` per the table above.**

```bash
# CERT_DIR is one of the five directories from Step 1.
CERT_DIR="$REPO_ROOT/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls"
cd "$CERT_DIR"

openssl req -x509 -new -key ca-key.pem -sha256 -days 3650 \
  -subj "/C=UK/L=London/O=MockServer" \
  -config "$REPO_ROOT/.tmp/ca-ext.cnf" -extensions v3_ca \
  -out ca.pem

# Rebuild every chain in this dir so its embedded CA copy matches.
cat leaf-cert.pem ca.pem > leaf-cert-chain.pem
```

## Step 4: Re-sign a leaf in place (keeps the existing leaf key → no PKCS#1 trap)

`-days 1825` (5 years) is strictly less than the CA's `-days 3650` (10 years), so the
leaf can never outlive its CA. **Adjust `-subj` per the table** (netty leaves carry
`CN=www.mockserver.com`; mtls leaves do not).

```bash
CERT_DIR="$REPO_ROOT/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls"
cd "$CERT_DIR"

openssl req -new -key leaf-key.pem \
  -subj "/C=UK/L=London/O=MockServer" \
  -out "$REPO_ROOT/.tmp/leaf.csr"

openssl x509 -req -in "$REPO_ROOT/.tmp/leaf.csr" \
  -CA ca.pem -CAkey ca-key.pem -CAcreateserial \
  -sha256 -days 1825 \
  -extfile "$REPO_ROOT/.tmp/leaf-ext.cnf" -extensions v3_leaf \
  -out leaf-cert.pem

cat leaf-cert.pem ca.pem > leaf-cert-chain.pem
rm -f ca.srl "$REPO_ROOT/.tmp/leaf.csr"
```

For the **netty ec** directory the leaf key is EC — the same `openssl req -new -key
leaf-key.pem ...` command works unchanged (the key type is read from the key file).

## Step 5: The intentional expired negative fixture

`authentication/mtls/expired-leaf-cert.pem` is a **deliberately expired** cert
(notAfter 2023-01-01) asserted by `MTLSAuthenticationHandlerTest`. It is signed by
`authentication/mtls/ca-key.pem`. Because Step 3 keeps that CA key, the expired
fixture keeps chaining to the regenerated `ca.pem` — **do not touch it**. The guard
asserts it stays expired; if you ever must re-mint it, give it a past `notAfter`
(e.g. `-days -1` semantics via an explicit `-not_after`), never a future one.

## Step 6: Verify

```bash
# openssl chain check (ignore time so an intentionally-expired fixture still shows OK)
openssl verify -no_check_time -CAfile ca.pem leaf-cert.pem
openssl x509 -in leaf-cert.pem -noout -dates
openssl x509 -in leaf-cert.pem -noout -text | grep -A1 "Authority Key Identifier"

# whole-tree guard — the authoritative check (must PASS)
"$REPO_ROOT/.buildkite/scripts/steps/check-certificate-expiry.sh"
```

Then run the affected tests.

### mtls (mockserver-core) — unit tests

```bash
cd "$REPO_ROOT/mockserver" && ./mvnw -pl mockserver-core test \
  -Dtest='MTLSAuthenticationHandlerTest,ChainedAuthenticationHandlerTest,KeyStoreFactoryTest,PEMToFileTest,JDKCertificateToMockServerX509CertificateTest' \
  -Djava.security.egd=file:/dev/urandom
```

### netty — integration tests

```bash
cd "$REPO_ROOT/mockserver" && ./mvnw -pl mockserver-netty -am verify \
  -Dtest=none -DfailIfNoTests=false \
  -Dit.test="CustomPrivateKeyAndCertificateWithECKeysMockingIntegrationTest,ClientAuthenticationCustomPrivateKeyAndCertificateMockingIntegrationTest,ClientAuthenticationAdditionalCertificateChainMockingIntegrationTest,ForwardWithCustomClientCertificateIntegrationTest" \
  -Djava.security.egd=file:/dev/urandom
```

## Step 7: Clean Up

```bash
rm -f "$REPO_ROOT/.tmp/ca-ext.cnf" "$REPO_ROOT/.tmp/leaf-ext.cnf"
```

## Affected Test Classes

### mtls (mockserver-core, RSA) — `authentication/mtls/` and `authentication/mtls/separateca/`
- `MTLSAuthenticationHandlerTest` (includes the expired-negative-fixture assertion)
- `ChainedAuthenticationHandlerTest`
- `KeyStoreFactoryTest`
- `PEMToFileTest` (depends on the PKCS#1 `leaf-key.pem` encoding)
- `JDKCertificateToMockServerX509CertificateTest`

### netty `tls/` (RSA)
- `ClientAuthenticationAdditionalCertificateChainMockingIntegrationTest`
- `ClientAuthenticationCustomPrivateKeyAndCertificateMockingIntegrationTest`
- `ClientAuthenticationCustomCertificateAuthorityMockingIntegrationTest`
- `CustomCertificateAuthorityMockingIntegrationTest`
- `ForwardWithCustomTrustManagerWithCustomCAMockingIntegrationTest`
- `ForwardViaHttpsProxyWithCustomTrustManagerWithCustomCAMockingIntegrationTest`
- `ForwardWithCustomClientCertificateIntegrationTest`
- `AuthenticatedControlPlaneUsingMTLSClientMockingIntegrationTest`
- `AuthenticatedControlPlaneUsingMTLSClientNotAuthenticatedIntegrationTest`

### netty `tls/ec/` (ECDSA)
- `CustomPrivateKeyAndCertificateWithECKeysMockingIntegrationTest`

### netty `tls/separateca/` (RSA, separate CA)
- `AbstractForwardViaHttpsProxyMockingIntegrationTest` (and subclasses)
- `ForwardWithCustomClientCertificateIntegrationTest`
- `AuthenticatedControlPlaneUsingMTLSClientNotAuthenticatedIntegrationTest`

## Note: cfssl includes no AKI

`cfssl gencert` omits `authorityKeyIdentifier`. Because CA and leaf share a subject
DN, Java's `X509TrustManager` then cannot chain the leaf to the CA and treats it as
self-signed. Always use the `openssl` recipes above (they set AKI via
`.tmp/leaf-ext.cnf`). `cfssl genkey -initca` may still be used to mint a brand-new
CA key, but the in-place Step 3 re-sign is preferred because it preserves the key.
