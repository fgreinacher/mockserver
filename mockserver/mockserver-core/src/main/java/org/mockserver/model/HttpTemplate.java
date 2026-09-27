package org.mockserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.mockserver.file.TemplateFileContentCache;

import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @author jamesdbloom
 */
public class HttpTemplate extends Action<HttpTemplate> {
    private int hashCode;
    private final TemplateType templateType;
    private String template;
    private String templateFile;
    private Type actionType;
    private HttpResponse responseOverride;
    private HttpResponseModifier responseModifier;

    public HttpTemplate(TemplateType type) {
        this.templateType = type;
    }

    /**
     * Static builder to create an template for responding or forwarding requests.
     */
    public static HttpTemplate template(TemplateType type) {
        return new HttpTemplate(type);
    }

    /**
     * Static builder to create an template for responding or forwarding requests.
     *
     * @param template the template for the response or request
     */
    public static HttpTemplate template(TemplateType type, String template) {
        return new HttpTemplate(type).withTemplate(template);
    }

    public TemplateType getTemplateType() {
        return templateType;
    }

    public HttpTemplate withTemplate(String template) {
        this.template = template;
        this.hashCode = 0;
        return this;
    }

    public String getTemplate() {
        return template;
    }

    /**
     * Load the template from a file (resolved from the classpath or the filesystem) instead of
     * providing it inline. The inline template, when present, always takes precedence over the file.
     *
     * @param templateFile path to the file containing the template
     */
    public HttpTemplate withTemplateFile(String templateFile) {
        this.templateFile = templateFile;
        this.hashCode = 0;
        return this;
    }

    public String getTemplateFile() {
        return templateFile;
    }

    /**
     * Resolves the template content used by the template engines: the inline template when set,
     * otherwise the contents of {@link #getTemplateFile()} read from the classpath or filesystem.
     * The file content is cached per canonical path and revalidated by a cheap metadata check, so an
     * edited template file still takes effect on the next request without re-reading it every time.
     */
    @JsonIgnore
    public String getTemplateContent() {
        if (isNotBlank(template)) {
            return template;
        }
        if (isNotBlank(templateFile)) {
            return TemplateFileContentCache.readFileFromClassPathOrPath(templateFile);
        }
        return template;
    }

    public HttpResponse getResponseOverride() {
        return responseOverride;
    }

    public HttpTemplate withResponseOverride(HttpResponse responseOverride) {
        this.responseOverride = responseOverride;
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifier getResponseModifier() {
        return responseModifier;
    }

    public HttpTemplate withResponseModifier(HttpResponseModifier responseModifier) {
        this.responseModifier = responseModifier;
        this.hashCode = 0;
        return this;
    }

    public void withActionType(Type actionType) {
        this.actionType = actionType;
        this.hashCode = 0;
    }

    @Override
    @JsonIgnore
    public Type getType() {
        return actionType;
    }

    public enum TemplateType {
        JAVASCRIPT,
        VELOCITY,
        MUSTACHE
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        HttpTemplate that = (HttpTemplate) o;
        return templateType == that.templateType &&
            Objects.equals(template, that.template) &&
            Objects.equals(templateFile, that.templateFile) &&
            actionType == that.actionType &&
            Objects.equals(responseOverride, that.responseOverride) &&
            Objects.equals(responseModifier, that.responseModifier);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(super.hashCode(), templateType, template, templateFile, actionType, responseOverride, responseModifier);
        }
        return hashCode;
    }
}

