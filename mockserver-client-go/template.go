package mockserver

// HttpTemplate represents a template action for MockServer (response or forward).
type HttpTemplate struct {
	TemplateType string `json:"templateType,omitempty"`
	Template     string `json:"template,omitempty"`
	TemplateFile string `json:"templateFile,omitempty"`
	Delay        *Delay `json:"delay,omitempty"`
	// ResponseOverride is merged into the template's rendered response.
	ResponseOverride *HttpResponse `json:"responseOverride,omitempty"`
	// ResponseModifier rewrites headers/cookies of the rendered response.
	ResponseModifier *ResponseModifier `json:"responseModifier,omitempty"`
}

// TemplateBuilder provides a fluent API for building HttpTemplate actions.
type TemplateBuilder struct {
	template HttpTemplate
}

// ResponseTemplate creates a new TemplateBuilder for a response template with the given type.
// templateType should be "VELOCITY", "JAVASCRIPT", or "MUSTACHE".
func ResponseTemplate(templateType string) *TemplateBuilder {
	return &TemplateBuilder{template: HttpTemplate{TemplateType: templateType}}
}

// ForwardTemplate creates a new TemplateBuilder for a forward template with the given type.
// templateType should be "VELOCITY", "JAVASCRIPT", or "MUSTACHE".
func ForwardTemplate(templateType string) *TemplateBuilder {
	return &TemplateBuilder{template: HttpTemplate{TemplateType: templateType}}
}

// Template sets the inline template string.
func (b *TemplateBuilder) Template(template string) *TemplateBuilder {
	b.template.Template = template
	return b
}

// TemplateFile sets the path to a template file.
func (b *TemplateBuilder) TemplateFile(filePath string) *TemplateBuilder {
	b.template.TemplateFile = filePath
	return b
}

// WithDelay sets the template action delay.
func (b *TemplateBuilder) WithDelay(timeUnit string, value int) *TemplateBuilder {
	b.template.Delay = &Delay{TimeUnit: timeUnit, Value: value}
	return b
}

// Build returns the constructed HttpTemplate.
func (b *TemplateBuilder) Build() HttpTemplate {
	return b.template
}
