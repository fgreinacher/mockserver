package org.mockserver.serialization.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.slf4j.event.Level.ERROR;

/**
 * @author jamesdbloom
 */
public abstract class BodyDTO extends NotDTO implements DTO<Body<?>> {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(BodyDTO.class);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private final Body.Type type;
    private Boolean optional;

    public BodyDTO(Body.Type type, Boolean not) {
        super(not);
        this.type = type;
    }

    public static BodyDTO createDTO(Body<?> body) {
        BodyDTO result = null;

        if (body instanceof BinaryBody binaryBody) {
            result = new BinaryBodyDTO(binaryBody, binaryBody.getNot());
        } else if (body instanceof JsonBody jsonBody) {
            result = new JsonBodyDTO(jsonBody, jsonBody.getNot());
        } else if (body instanceof JsonSchemaBody jsonSchemaBody) {
            result = new JsonSchemaBodyDTO(jsonSchemaBody, jsonSchemaBody.getNot());
        } else if (body instanceof JsonPathBody jsonPathBody) {
            result = new JsonPathBodyDTO(jsonPathBody, jsonPathBody.getNot());
        } else if (body instanceof ParameterBody parameterBody) {
            result = new ParameterBodyDTO(parameterBody, parameterBody.getNot());
        } else if (body instanceof MultipartBody multipartBody) {
            result = new MultipartBodyDTO(multipartBody, multipartBody.getNot());
        } else if (body instanceof RegexBody regexBody) {
            result = new RegexBodyDTO(regexBody, regexBody.getNot());
        } else if (body instanceof FuzzyBody fuzzyBody) {
            result = new FuzzyBodyDTO(fuzzyBody, fuzzyBody.getNot());
        } else if (body instanceof StringBody stringBody) {
            result = new StringBodyDTO(stringBody, stringBody.getNot());
        } else if (body instanceof XmlBody xmlBody) {
            result = new XmlBodyDTO(xmlBody, xmlBody.getNot());
        } else if (body instanceof XmlSchemaBody xmlSchemaBody) {
            result = new XmlSchemaBodyDTO(xmlSchemaBody, xmlSchemaBody.getNot());
        } else if (body instanceof XPathBody xPathBody) {
            result = new XPathBodyDTO(xPathBody, xPathBody.getNot());
        } else if (body instanceof JsonRpcBody jsonRpcBody) {
            result = new JsonRpcBodyDTO(jsonRpcBody, jsonRpcBody.getNot());
        } else if (body instanceof GraphQLBody graphQLBody) {
            result = new GraphQLBodyDTO(graphQLBody, graphQLBody.getNot());
        } else if (body instanceof FileBody fileBody) {
            result = new FileBodyDTO(fileBody, fileBody.getNot());
        } else if (body instanceof WasmBody wasmBody) {
            result = new WasmBodyDTO(wasmBody, wasmBody.getNot());
        } else if (body instanceof AllOfBody allOfBody) {
            result = new AllOfBodyDTO(allOfBody, allOfBody.getNot());
        }

        if (result != null) {
            result.withOptional(body.getOptional());
        }

        return result;
    }

    public static String toString(BodyDTO body) {
        if (body instanceof BinaryBodyDTO binaryBodyDTO) {
            return Base64.encodeBase64String(binaryBodyDTO.getBase64Bytes());
        } else if (body instanceof JsonBodyDTO jsonBodyDTO) {
            return jsonBodyDTO.getJson();
        } else if (body instanceof JsonSchemaBodyDTO jsonSchemaBodyDTO) {
            return jsonSchemaBodyDTO.getJson();
        } else if (body instanceof JsonPathBodyDTO jsonPathBodyDTO) {
            return jsonPathBodyDTO.getJsonPath();
        } else if (body instanceof ParameterBodyDTO parameterBodyDTO) {
            try {
                return OBJECT_MAPPER.writeValueAsString(parameterBodyDTO.getParameters().getMultimap().asMap());
            } catch (Throwable throwable) {
                MOCK_SERVER_LOGGER
                    .logEvent(
                        new LogEntry()
                            .setLogLevel(ERROR)
                            .setMessageFormat("serialising parameter body into json string for javascript template " + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""))
                            .setThrowable(throwable)
                    );
                return "";
            }
        } else if (body instanceof MultipartBodyDTO multipartBodyDTO) {
            try {
                return OBJECT_MAPPER.writeValueAsString(multipartBodyDTO.getFields().getMultimap().asMap());
            } catch (Throwable throwable) {
                MOCK_SERVER_LOGGER
                    .logEvent(
                        new LogEntry()
                            .setLogLevel(ERROR)
                            .setMessageFormat("serialising multipart body into json string for javascript template " + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""))
                            .setThrowable(throwable)
                    );
                return "";
            }
        } else if (body instanceof RegexBodyDTO regexBodyDTO) {
            return regexBodyDTO.getRegex();
        } else if (body instanceof FuzzyBodyDTO fuzzyBodyDTO) {
            return fuzzyBodyDTO.getFuzzy();
        } else if (body instanceof StringBodyDTO stringBodyDTO) {
            return stringBodyDTO.getString();
        } else if (body instanceof XmlBodyDTO xmlBodyDTO) {
            return xmlBodyDTO.getXml();
        } else if (body instanceof XmlSchemaBodyDTO xmlSchemaBodyDTO) {
            return xmlSchemaBodyDTO.getXml();
        } else if (body instanceof XPathBodyDTO xPathBodyDTO) {
            return xPathBodyDTO.getXPath();
        } else if (body instanceof JsonRpcBodyDTO jsonRpcBodyDTO) {
            return jsonRpcBodyDTO.getMethod();
        } else if (body instanceof GraphQLBodyDTO graphQLBodyDTO) {
            return graphQLBodyDTO.getQuery();
        } else if (body instanceof FileBodyDTO fileBodyDTO) {
            return fileBodyDTO.getFilePath();
        } else if (body instanceof WasmBodyDTO wasmBodyDTO) {
            return wasmBodyDTO.getModuleName();
        }

        return "";
    }

    public Body.Type getType() {
        return type;
    }

    public Boolean getOptional() {
        return optional;
    }

    public BodyDTO withOptional(Boolean optional) {
        this.optional = optional;
        return this;
    }

    public abstract Body<?> buildObject();

}
