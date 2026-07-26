package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.file.FileBodyException;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.BodyWithContentType;
import org.mockserver.model.FileBody;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;
import org.mockserver.model.StringBody;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit tests for {@link FileBodyMaterialiser}, the single shared component that reads a {@link FileBody}
 * so its CONTENTS (not the filePath string) reach the wire / request matcher.
 *
 * @author jamesdbloom
 */
public class FileBodyMaterialiserTest {

    private static final String VERBATIM_XML = "org/mockserver/mock/action/verbatim_file_body.xml";
    private static final String VERBATIM_PNG = "org/mockserver/mock/action/verbatim_binary_body.png";
    private static final String MUSTACHE_JSON = "org/mockserver/templates/sample_mustache_body.json";
    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF, (byte) 0xFE, 0x01, (byte) 0x80, 0x7F, (byte) 0xC3, 0x28};

    private final FileBodyMaterialiser materialiser = new FileBodyMaterialiser(new MockServerLogger(), new Configuration());

    @Test
    public void shouldServeTextFileContentsVerbatimWhenNoTemplateType() {
        // given - a FILE body with a text content type and no templateType
        FileBody fileBody = new FileBody(VERBATIM_XML, MediaType.parse("application/xml"));

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, request().withPath("/somePath"));

        // then - the exact file contents are served as a StringBody, untemplated, not the path
        assertThat(body, is(instanceOf(StringBody.class)));
        assertThat(body.getValue(), is("<tag>hello{{ request.path }}</tag>"));
        assertThat(body.getContentType(), containsString("application/xml"));
        assertThat(String.valueOf(body.getValue()), not(containsString(VERBATIM_XML)));
    }

    @Test
    public void shouldServeBinaryFileContentsIntactWhenNoTemplateType() {
        // given - a FILE body referencing a binary file with a binary content type
        FileBody fileBody = new FileBody(VERBATIM_PNG, MediaType.parse("image/png"));

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, request().withPath("/image"));

        // then - raw bytes served intact (charset decoding would corrupt them)
        assertThat(body, is(instanceOf(BinaryBody.class)));
        assertArrayEquals(PNG_BYTES, body.getRawBytes());
        assertThat(body.getContentType(), containsString("image/png"));
    }

    @Test
    public void shouldServeBinaryWhenContentTypeAbsent() {
        // given - a FILE body with NO content type: served as raw bytes so nothing is charset-corrupted
        FileBody fileBody = new FileBody(VERBATIM_PNG);

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, null);

        // then
        assertThat(body, is(instanceOf(BinaryBody.class)));
        assertArrayEquals(PNG_BYTES, body.getRawBytes());
    }

    @Test
    public void shouldRenderMustacheTemplatedFileBodyAgainstRequest() {
        // given - a MUSTACHE-templated FILE body
        FileBody fileBody = new FileBody(MUSTACHE_JSON, MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE);

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, request().withMethod("PUT").withPath("/somePath"));

        // then - placeholders resolved from the request, content type preserved, no raw placeholder remains
        assertThat(body, is(instanceOf(StringBody.class)));
        assertThat(String.valueOf(body.getValue()), containsString("\"method\": \"PUT\""));
        assertThat(String.valueOf(body.getValue()), containsString("\"path\": \"/somePath\""));
        assertThat(String.valueOf(body.getValue()), not(containsString("{{")));
        assertThat(body.getContentType(), containsString("application/json"));
    }

    @Test
    public void shouldServeVerbatimWhenTemplatedButNoRequestAvailable() {
        // given - a MUSTACHE-templated FILE body but no request to template against
        FileBody fileBody = new FileBody(MUSTACHE_JSON, MediaType.APPLICATION_JSON, HttpTemplate.TemplateType.MUSTACHE);

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, null);

        // then - contents served verbatim (placeholder survives), NOT the path
        assertThat(body, is(instanceOf(StringBody.class)));
        assertThat(String.valueOf(body.getValue()), containsString("{{ request.method }}"));
    }

    @Test
    public void shouldServeVerbatimWhenTemplateTypeUnsupported() {
        // given - an UNSUPPORTED template type (JavaScript) for FILE templating, with a request present
        FileBody fileBody = new FileBody(VERBATIM_XML, MediaType.parse("application/xml"), HttpTemplate.TemplateType.JAVASCRIPT);

        // when
        BodyWithContentType body = materialiser.materialise(fileBody, request().withPath("/somePath"));

        // then - contents served verbatim, not the path
        assertThat(body, is(instanceOf(StringBody.class)));
        assertThat(body.getValue(), is("<tag>hello{{ request.path }}</tag>"));
    }

    @Test
    public void shouldThrowFileBodyExceptionWhenFileMissing() {
        // given - a FILE body pointing at a nonexistent file
        String missingPath = "org/mockserver/mock/action/does_not_exist_" + System.nanoTime() + ".xml";
        FileBody fileBody = new FileBody(missingPath, MediaType.parse("application/xml"));

        // when / then - a typed FileBodyException carrying the path is thrown (not swallowed, not path-as-body)
        FileBodyException exception = assertThrows(FileBodyException.class, () -> materialiser.materialise(fileBody, request().withPath("/missing")));
        assertThat(exception.getFilePath(), is(missingPath));
    }

    @Test
    public void shouldThrowFileBodyExceptionWhenBinaryFileMissing() {
        // given - a binary (no string content type) FILE body pointing at a nonexistent file
        String missingPath = "org/mockserver/mock/action/does_not_exist_" + System.nanoTime() + ".png";
        FileBody fileBody = new FileBody(missingPath, MediaType.parse("image/png"));

        // when / then
        FileBodyException exception = assertThrows(FileBodyException.class, () -> materialiser.materialise(fileBody, null));
        assertThat(exception.getFilePath(), is(missingPath));
    }
}
