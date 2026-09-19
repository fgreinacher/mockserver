package org.mockserver.saml;

import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.uuid.UUIDService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;

/**
 * Builds and enveloped-signs a SAML 2.0 {@code <Response>} document using only the JDK XML APIs
 * ({@code javax.xml} DOM) and the JDK XML Digital Signature API ({@code javax.xml.crypto.dsig.*}) —
 * no OpenSAML/Shibboleth dependency.
 *
 * <p>The {@code <Assertion>} is enveloped-signed (the signature element is inserted into the
 * Assertion immediately after its {@code <Issuer>}, as required by the SAML schema), with an
 * exclusive-canonicalised {@code Reference} to the Assertion's {@code ID} and the signing
 * certificate embedded in {@code <ds:KeyInfo>/<ds:X509Data>}. Signing the Assertion (rather than the
 * Response envelope) is the most widely interoperable choice for the Web-Browser-SSO POST profile.
 */
public class SamlResponseBuilder {

    static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";

    // Constructing DocumentBuilderFactory / TransformerFactory / XMLSignatureFactory is expensive
    // (service-loader lookups + provider instantiation), so the FACTORIES are created once and shared.
    // CRITICAL: only the factories are shared — DocumentBuilderFactory, TransformerFactory and the
    // DocumentBuilder/Transformer they produce are NOT thread-safe, so every call still creates a
    // fresh DocumentBuilder/Transformer via newDocumentBuilder()/newTransformer(). The exact factory
    // configuration applied per-request before (only setNamespaceAware(true); no other security
    // feature was ever set) is replicated here verbatim so behaviour — including XML namespace
    // handling — is identical. XMLSignatureFactory.getInstance("DOM") is reusable/thread-safe.
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = newDocumentBuilderFactory();
    private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();
    private static final XMLSignatureFactory XML_SIGNATURE_FACTORY = XMLSignatureFactory.getInstance("DOM");

    private static DocumentBuilderFactory newDocumentBuilderFactory() {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf;
    }

    /**
     * Builds a signed SAML Response and returns its serialized XML string.
     *
     * @param provider   the mock IdP provider state
     * @param relayState the SP RelayState (echoed by the caller, not embedded in the assertion)
     * @return the serialized, signed {@code <Response>} XML
     */
    public String buildSignedResponse(SamlAssertionStore.Provider provider, String inResponseTo) {
        try {
            DocumentBuilder builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
            Document doc = builder.newDocument();

            String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0));
            // expiredAssertion negative-test flag: place NotOnOrAfter in the past so a conformant SP
            // rejects the assertion as expired. Otherwise it sits sessionDuration seconds in the future.
            Instant validityEnd = provider.expiredAssertion
                ? Instant.now().minusSeconds(3600)
                : Instant.now().plusSeconds(provider.sessionDurationSeconds);
            String notOnOrAfter = DateTimeFormatter.ISO_INSTANT.format(
                validityEnd.with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0)
            );
            String responseId = "_" + UUIDService.getNonSecureUUID();
            String assertionId = "_" + UUIDService.getNonSecureUUID();

            // <samlp:Response>
            Element response = doc.createElementNS(NS_PROTOCOL, "samlp:Response");
            // namespace declarations are emitted automatically from createElementNS — declaring them
            // manually via setAttribute creates a plain attribute (null namespace) whose canonical
            // form differs from a proper namespace node after a serialize/re-parse round-trip, which
            // would break the enveloped XML signature's digest. So do not add xmlns:* by hand.
            response.setAttribute("ID", responseId);
            response.setAttribute("Version", "2.0");
            response.setAttribute("IssueInstant", now);
            response.setAttribute("Destination", provider.assertionConsumerServiceUrl);
            if (inResponseTo != null && !inResponseTo.isEmpty()) {
                response.setAttribute("InResponseTo", inResponseTo);
            }
            doc.appendChild(response);

            // <saml:Issuer>
            response.appendChild(issuer(doc, provider.idpEntityId));

            // <samlp:Status><samlp:StatusCode Value="...:Success"/></samlp:Status>
            Element status = doc.createElementNS(NS_PROTOCOL, "samlp:Status");
            Element statusCode = doc.createElementNS(NS_PROTOCOL, "samlp:StatusCode");
            statusCode.setAttribute("Value", "urn:oasis:names:tc:SAML:2.0:status:Success");
            status.appendChild(statusCode);
            response.appendChild(status);

            // <saml:Assertion>
            Element assertion = doc.createElementNS(NS_ASSERTION, "saml:Assertion");
            assertion.setAttribute("ID", assertionId);
            assertion.setAttribute("Version", "2.0");
            assertion.setAttribute("IssueInstant", now);
            response.appendChild(assertion);

            // <saml:Issuer> (inside assertion)
            assertion.appendChild(issuer(doc, provider.idpEntityId));

            // <saml:Subject>
            Element subject = doc.createElementNS(NS_ASSERTION, "saml:Subject");
            Element nameId = doc.createElementNS(NS_ASSERTION, "saml:NameID");
            if (provider.nameIdFormat != null && !provider.nameIdFormat.isEmpty()) {
                nameId.setAttribute("Format", provider.nameIdFormat);
            }
            nameId.setTextContent(provider.subjectNameId);
            subject.appendChild(nameId);
            Element subjectConfirmation = doc.createElementNS(NS_ASSERTION, "saml:SubjectConfirmation");
            subjectConfirmation.setAttribute("Method", "urn:oasis:names:tc:SAML:2.0:cm:bearer");
            Element subjectConfirmationData = doc.createElementNS(NS_ASSERTION, "saml:SubjectConfirmationData");
            subjectConfirmationData.setAttribute("NotOnOrAfter", notOnOrAfter);
            subjectConfirmationData.setAttribute("Recipient", provider.assertionConsumerServiceUrl);
            if (inResponseTo != null && !inResponseTo.isEmpty()) {
                subjectConfirmationData.setAttribute("InResponseTo", inResponseTo);
            }
            subjectConfirmation.appendChild(subjectConfirmationData);
            subject.appendChild(subjectConfirmation);
            assertion.appendChild(subject);

            // <saml:Conditions> with <saml:AudienceRestriction><saml:Audience>spEntityId
            Element conditions = doc.createElementNS(NS_ASSERTION, "saml:Conditions");
            conditions.setAttribute("NotBefore", now);
            conditions.setAttribute("NotOnOrAfter", notOnOrAfter);
            Element audienceRestriction = doc.createElementNS(NS_ASSERTION, "saml:AudienceRestriction");
            Element audience = doc.createElementNS(NS_ASSERTION, "saml:Audience");
            // wrongAudience negative-test flag: emit an Audience that does NOT match the SP entityId so
            // a conformant SP rejects the assertion for failing the audience restriction.
            audience.setTextContent(provider.wrongAudience
                ? provider.spEntityId + "/wrong-audience"
                : provider.spEntityId);
            audienceRestriction.appendChild(audience);
            conditions.appendChild(audienceRestriction);
            assertion.appendChild(conditions);

            // <saml:AuthnStatement>
            Element authnStatement = doc.createElementNS(NS_ASSERTION, "saml:AuthnStatement");
            authnStatement.setAttribute("AuthnInstant", now);
            authnStatement.setAttribute("SessionNotOnOrAfter", notOnOrAfter);
            authnStatement.setAttribute("SessionIndex", "_" + UUIDService.getNonSecureUUID());
            Element authnContext = doc.createElementNS(NS_ASSERTION, "saml:AuthnContext");
            Element authnContextClassRef = doc.createElementNS(NS_ASSERTION, "saml:AuthnContextClassRef");
            authnContextClassRef.setTextContent("urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport");
            authnContext.appendChild(authnContextClassRef);
            authnStatement.appendChild(authnContext);
            assertion.appendChild(authnStatement);

            // <saml:AttributeStatement>
            if (provider.attributes != null && !provider.attributes.isEmpty()) {
                Element attributeStatement = doc.createElementNS(NS_ASSERTION, "saml:AttributeStatement");
                for (Map.Entry<String, String> entry : provider.attributes.entrySet()) {
                    Element attribute = doc.createElementNS(NS_ASSERTION, "saml:Attribute");
                    attribute.setAttribute("Name", entry.getKey());
                    attribute.setAttribute("NameFormat", "urn:oasis:names:tc:SAML:2.0:attrname-format:basic");
                    Element attributeValue = doc.createElementNS(NS_ASSERTION, "saml:AttributeValue");
                    attributeValue.setTextContent(entry.getValue());
                    attribute.appendChild(attributeValue);
                    attributeStatement.appendChild(attribute);
                }
                assertion.appendChild(attributeStatement);
            }

            // Round-trip the unsigned document through serialize + re-parse before signing so the
            // sign-time DOM is structurally identical to the DOM a relying party reconstructs when it
            // re-parses the response. Without this the enveloped-signature Reference digest computed
            // at sign time can differ from the digest recomputed after a parse, failing validation.
            Document reparsed = reparse(serialize(doc));
            Element reparsedAssertion = (Element) reparsed
                .getElementsByTagNameNS(NS_ASSERTION, "Assertion").item(0);
            reparsedAssertion.setIdAttribute("ID", true);

            // enveloped-sign the Assertion
            signAssertion(reparsedAssertion, provider.signingPrivateKey, provider.signingCertificate,
                provider.signingAlgorithm);

            // tamperedSignature negative-test flag: corrupt the computed SignatureValue so signature
            // verification against the IdP certificate fails (the SP must reject the assertion).
            if (provider.tamperedSignature) {
                corruptSignatureValue(reparsed);
            }

            return serialize(reparsed);
        } catch (Exception e) {
            throw new RuntimeException("Exception building signed SAML response", e);
        }
    }

    private Element issuer(Document doc, String idpEntityId) {
        Element issuer = doc.createElementNS(NS_ASSERTION, "saml:Issuer");
        issuer.setTextContent(idpEntityId);
        return issuer;
    }

    /**
     * Enveloped-signs the assertion in place. The {@code <ds:Signature>} is inserted as the second
     * child of the Assertion (immediately after {@code <saml:Issuer>}), which is where the SAML
     * schema requires it.
     */
    private void signAssertion(Element assertion, PrivateKey privateKey, X509Certificate certificate,
                               AsymmetricKeyPairAlgorithm algorithm) throws Exception {
        XMLSignatureFactory fac = XML_SIGNATURE_FACTORY;
        String assertionId = assertion.getAttribute("ID");

        // The SignatureMethod + DigestMethod are derived from the configured signing algorithm
        // (default RSA-SHA256), keeping them consistent with the key type published in the metadata.
        AsymmetricKeyPairAlgorithm effectiveAlgorithm =
            algorithm != null ? algorithm : AsymmetricKeyPairAlgorithm.RSA2048_SHA256;

        Reference reference = fac.newReference(
            "#" + assertionId,
            fac.newDigestMethod(effectiveAlgorithm.getXmlDigestMethod(), null),
            java.util.Arrays.asList(
                fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)
            ),
            null, null
        );

        SignedInfo signedInfo = fac.newSignedInfo(
            fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
            fac.newSignatureMethod(effectiveAlgorithm.getXmlSignatureMethod(), null),
            Collections.singletonList(reference)
        );

        KeyInfoFactory kif = fac.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(Collections.singletonList(certificate));
        KeyInfo keyInfo = kif.newKeyInfo(Collections.singletonList(x509Data));

        // Insert the signature after <saml:Issuer> (the first child of the assertion).
        DOMSignContext signContext = new DOMSignContext(privateKey, assertion);
        if (assertion.getFirstChild() != null && assertion.getFirstChild().getNextSibling() != null) {
            signContext.setNextSibling(assertion.getFirstChild().getNextSibling());
        }
        signContext.setDefaultNamespacePrefix("ds");

        XMLSignature signature = fac.newXMLSignature(signedInfo, keyInfo);
        signature.sign(signContext);
    }

    /**
     * Corrupts the {@code <ds:SignatureValue>} of the signed assertion in place by flipping its first
     * base64 character. The XML stays well-formed and the certificate is unchanged, but the signature
     * no longer verifies — exercising an SP's signature-rejection path. No-op if no SignatureValue is
     * present.
     */
    private void corruptSignatureValue(Document doc) {
        NodeList signatureValues = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "SignatureValue");
        if (signatureValues.getLength() == 0) {
            return;
        }
        Node signatureValue = signatureValues.item(0);
        String value = signatureValue.getTextContent();
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        char first = value.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        signatureValue.setTextContent(replacement + value.substring(1));
    }

    private Document reparse(String xml) throws Exception {
        return DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().parse(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String serialize(Document doc) throws Exception {
        Transformer transformer = TRANSFORMER_FACTORY.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
