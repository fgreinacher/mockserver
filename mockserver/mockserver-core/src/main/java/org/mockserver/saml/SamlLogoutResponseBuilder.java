package org.mockserver.saml;

import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.uuid.UUIDService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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

/**
 * Builds and enveloped-signs a SAML 2.0 {@code <LogoutResponse>} for the Single-Logout (SLO)
 * HTTP-POST profile, using only the JDK XML APIs and the JDK XML Digital Signature API (no
 * OpenSAML dependency), mirroring {@link SamlResponseBuilder}.
 *
 * <p>The {@code <LogoutResponse>} envelope itself is enveloped-signed (the {@code <ds:Signature>}
 * is inserted immediately after its {@code <Issuer>}, where the SAML schema requires it), with the
 * signing certificate embedded in {@code <ds:KeyInfo>/<ds:X509Data>}, so the SP can validate the
 * signature against the certificate published in the IdP metadata.
 */
public class SamlLogoutResponseBuilder {

    static final String NS_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";

    /**
     * Builds a signed SAML LogoutResponse.
     *
     * @param provider     the mock IdP provider state (supplies issuer, SLO destination, credential)
     * @param inResponseTo the {@code ID} of the SP's {@code LogoutRequest} to echo, or {@code null}
     * @return the serialized, signed {@code <LogoutResponse>} XML
     */
    public String buildSignedLogoutResponse(SamlAssertionStore.Provider provider, String inResponseTo) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();

            String now = DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().with(java.time.temporal.ChronoField.NANO_OF_SECOND, 0));
            String responseId = "_" + UUIDService.getNonSecureUUID();

            Element logoutResponse = doc.createElementNS(NS_PROTOCOL, "samlp:LogoutResponse");
            logoutResponse.setAttribute("ID", responseId);
            logoutResponse.setAttribute("Version", "2.0");
            logoutResponse.setAttribute("IssueInstant", now);
            logoutResponse.setAttribute("Destination", provider.spSingleLogoutServiceUrl);
            if (inResponseTo != null && !inResponseTo.isEmpty()) {
                logoutResponse.setAttribute("InResponseTo", inResponseTo);
            }
            doc.appendChild(logoutResponse);

            // <saml:Issuer>
            Element issuer = doc.createElementNS(NS_ASSERTION, "saml:Issuer");
            issuer.setTextContent(provider.idpEntityId);
            logoutResponse.appendChild(issuer);

            // <samlp:Status><samlp:StatusCode Value="...:Success"/></samlp:Status>
            Element status = doc.createElementNS(NS_PROTOCOL, "samlp:Status");
            Element statusCode = doc.createElementNS(NS_PROTOCOL, "samlp:StatusCode");
            statusCode.setAttribute("Value", STATUS_SUCCESS);
            status.appendChild(statusCode);
            logoutResponse.appendChild(status);

            // Round-trip the unsigned document before signing so the sign-time DOM is structurally
            // identical to the DOM a relying party reconstructs when it re-parses the response (see
            // the rationale in SamlResponseBuilder).
            Document reparsed = reparse(serialize(doc));
            Element reparsedResponse = reparsed.getDocumentElement();
            reparsedResponse.setIdAttribute("ID", true);

            signEnvelope(reparsedResponse, provider.signingPrivateKey, provider.signingCertificate,
                provider.signingAlgorithm);

            return serialize(reparsed);
        } catch (Exception e) {
            throw new RuntimeException("Exception building signed SAML logout response", e);
        }
    }

    /**
     * Enveloped-signs the LogoutResponse in place, inserting the {@code <ds:Signature>} immediately
     * after {@code <saml:Issuer>} (the first child), as the SAML schema requires.
     */
    private void signEnvelope(Element root, PrivateKey privateKey, X509Certificate certificate,
                              AsymmetricKeyPairAlgorithm algorithm) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        String id = root.getAttribute("ID");

        AsymmetricKeyPairAlgorithm effectiveAlgorithm =
            algorithm != null ? algorithm : AsymmetricKeyPairAlgorithm.RSA2048_SHA256;

        Reference reference = fac.newReference(
            "#" + id,
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

        DOMSignContext signContext = new DOMSignContext(privateKey, root);
        // insert after <saml:Issuer> (the first child)
        if (root.getFirstChild() != null && root.getFirstChild().getNextSibling() != null) {
            signContext.setNextSibling(root.getFirstChild().getNextSibling());
        }
        signContext.setDefaultNamespacePrefix("ds");

        XMLSignature signature = fac.newXMLSignature(signedInfo, keyInfo);
        signature.sign(signContext);
    }

    private Document reparse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
