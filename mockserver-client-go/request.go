package mockserver

// HttpRequest represents an HTTP request matcher for MockServer.
type HttpRequest struct {
	Method             string              `json:"method,omitempty"`
	Path               string              `json:"path,omitempty"`
	QueryStringParams  map[string][]string `json:"queryStringParameters,omitempty"`
	Headers            map[string][]string `json:"headers,omitempty"`
	Cookies            map[string]string   `json:"cookies,omitempty"`
	Body               interface{}         `json:"body,omitempty"`
	Secure             *bool               `json:"secure,omitempty"`
	KeepAlive          *bool               `json:"keepAlive,omitempty"`
	SocketAddress      *SocketAddress      `json:"socketAddress,omitempty"`
	PathParametersList map[string][]string `json:"pathParameters,omitempty"`
	JWT                *Jwt                `json:"jwt,omitempty"`
	// Not negates the whole request matcher (match everything except this).
	Not *bool `json:"not,omitempty"`
	// Protocol constrains the matcher to a wire protocol: "HTTP_1_1",
	// "HTTP_2" or "HTTP_3".
	Protocol string `json:"protocol,omitempty"`
	// RespondBeforeBody makes MockServer respond before consuming the request
	// body (requires no body matcher and a RESPONSE or ERROR action).
	RespondBeforeBody *bool `json:"respondBeforeBody,omitempty"`

	// --- DNS request definition (RequestDefinition: DnsRequestDefinition) ---
	// When set, this expectation matches a DNS query rather than an HTTP
	// request. DnsName is the queried name; DnsType (e.g. "A", "AAAA", "MX")
	// and DnsClass (e.g. "IN") are optional constraints.
	DnsName  string `json:"dnsName,omitempty"`
	DnsType  string `json:"dnsType,omitempty"`
	DnsClass string `json:"dnsClass,omitempty"`

	// --- OpenAPI request definition (RequestDefinition: OpenAPIDefinition) ---
	// When set, the request is matched against an OpenAPI/Swagger operation.
	SpecUrlOrPayload  interface{} `json:"specUrlOrPayload,omitempty"`
	OperationId       string      `json:"operationId,omitempty"`
	ContextPathPrefix string      `json:"contextPathPrefix,omitempty"`
}

// SocketAddress represents a socket address constraint.
type SocketAddress struct {
	Host   string `json:"host,omitempty"`
	Port   int    `json:"port,omitempty"`
	Scheme string `json:"scheme,omitempty"`
}

// RequestBuilder provides a fluent API for building HttpRequest matchers.
type RequestBuilder struct {
	request HttpRequest
}

// Request creates a new RequestBuilder.
func Request() *RequestBuilder {
	return &RequestBuilder{}
}

// Method sets the HTTP method matcher.
func (b *RequestBuilder) Method(method string) *RequestBuilder {
	b.request.Method = method
	return b
}

// Path sets the path matcher.
func (b *RequestBuilder) Path(path string) *RequestBuilder {
	b.request.Path = path
	return b
}

// QueryStringParameter adds a query string parameter matcher.
func (b *RequestBuilder) QueryStringParameter(name string, values ...string) *RequestBuilder {
	if b.request.QueryStringParams == nil {
		b.request.QueryStringParams = make(map[string][]string)
	}
	b.request.QueryStringParams[name] = values
	return b
}

// Header adds a header matcher.
func (b *RequestBuilder) Header(name string, values ...string) *RequestBuilder {
	if b.request.Headers == nil {
		b.request.Headers = make(map[string][]string)
	}
	b.request.Headers[name] = values
	return b
}

// Cookie adds a cookie matcher.
func (b *RequestBuilder) Cookie(name, value string) *RequestBuilder {
	if b.request.Cookies == nil {
		b.request.Cookies = make(map[string]string)
	}
	b.request.Cookies[name] = value
	return b
}

// Body sets the request body matcher as a plain string.
func (b *RequestBuilder) Body(body string) *RequestBuilder {
	b.request.Body = body
	return b
}

// JSONBody sets the request body matcher as a JSON body type.
func (b *RequestBuilder) JSONBody(json string) *RequestBuilder {
	b.request.Body = &TypedBody{Type: "JSON", JSON: json}
	return b
}

// AllOfBody sets the request body matcher to a composite ALL_OF matcher that
// matches only when every supplied body matcher matches. Each element may be any
// body matcher value (a *TypedBody, a plain string, or a nested *AllOfBody).
func (b *RequestBuilder) AllOfBody(bodies ...interface{}) *RequestBuilder {
	b.request.Body = AllOf(bodies...)
	return b
}

// Jwt sets a JWT (JSON Web Token) request matcher. MockServer decodes the bearer
// token and matches the supplied claims/issuer/audience/algorithm.
func (b *RequestBuilder) Jwt(jwt *Jwt) *RequestBuilder {
	b.request.JWT = jwt
	return b
}

// PathParameter adds a path parameter matcher.
func (b *RequestBuilder) PathParameter(name string, values ...string) *RequestBuilder {
	if b.request.PathParametersList == nil {
		b.request.PathParametersList = make(map[string][]string)
	}
	b.request.PathParametersList[name] = values
	return b
}

// Secure sets whether the request must be secure (HTTPS).
func (b *RequestBuilder) Secure(secure bool) *RequestBuilder {
	b.request.Secure = &secure
	return b
}

// KeepAlive sets whether the request must be keep-alive.
func (b *RequestBuilder) KeepAlive(keepAlive bool) *RequestBuilder {
	b.request.KeepAlive = &keepAlive
	return b
}

// Build returns the constructed HttpRequest.
func (b *RequestBuilder) Build() HttpRequest {
	return b.request
}

// BuildPtr returns a pointer to the constructed HttpRequest. It is a convenience
// for object forward-callback handlers (see Client.MockWithForwardCallback) which
// return a *HttpRequest.
func (b *RequestBuilder) BuildPtr() *HttpRequest {
	req := b.request
	return &req
}

// TypedBody represents a typed body matcher. A single struct carries every
// MockServer body-matcher variant (selected by Type); only the fields relevant
// to that Type are populated. Type is one of: STRING, JSON, JSON_SCHEMA,
// JSON_PATH, XML, XML_SCHEMA, XPATH, REGEX, PARAMETERS, BINARY, MULTIPART,
// GRAPHQL, JSON_RPC or WASM (see the MockServer Body schema). Not negates the
// matcher and Optional makes it optional.
type TypedBody struct {
	Type     string `json:"type"`
	Not      *bool  `json:"not,omitempty"`
	Optional *bool  `json:"optional,omitempty"`

	// STRING
	String    string `json:"string,omitempty"`
	SubString *bool  `json:"subString,omitempty"`

	// JSON
	JSON                  string `json:"json,omitempty"`
	MatchType             string `json:"matchType,omitempty"`
	MatchNumbersAsStrings *bool  `json:"matchNumbersAsStrings,omitempty"`

	// JSON_SCHEMA — the schema may be a JSON string or an embedded object.
	JSONSchema interface{} `json:"jsonSchema,omitempty"`

	// JSON_PATH
	JSONPath string `json:"jsonPath,omitempty"`

	// XML
	XML string `json:"xml,omitempty"`

	// XML_SCHEMA
	XMLSchema string `json:"xmlSchema,omitempty"`

	// XPATH
	XPath string `json:"xpath,omitempty"`

	// REGEX
	Regex string `json:"regex,omitempty"`

	// PARAMETERS
	Parameters map[string][]string `json:"parameters,omitempty"`

	// BINARY
	Base64Bytes string `json:"base64Bytes,omitempty"`

	// MULTIPART
	Fields           map[string][]string `json:"fields,omitempty"`
	Filenames        map[string][]string `json:"filenames,omitempty"`
	PartContentTypes map[string][]string `json:"partContentTypes,omitempty"`

	// GRAPHQL. Note: the GraphQL selection-set field list also serializes to the
	// JSON key "fields", which the MULTIPART Fields matcher above already owns.
	// A body is only ever one Type, but a single Go struct cannot expose the same
	// JSON key twice, so the (advanced, rarely client-set) GraphQL field list is
	// not a typed field here — it still round-trips losslessly via the generic
	// HttpRequest.Body interface. SelectionSetMatchType and Schema do not collide.
	Query                 string `json:"query,omitempty"`
	OperationName         string `json:"operationName,omitempty"`
	VariablesSchema       string `json:"variablesSchema,omitempty"`
	SelectionSetMatchType string `json:"selectionSetMatchType,omitempty"`
	Schema                string `json:"schema,omitempty"`

	// JSON_RPC
	Method       string `json:"method,omitempty"`
	ParamsSchema string `json:"paramsSchema,omitempty"`

	// WASM
	ModuleName string `json:"moduleName,omitempty"`

	// ContentType applies to the STRING, JSON, XML and BINARY variants.
	ContentType string `json:"contentType,omitempty"`
}

// StringBody builds a STRING body matcher (exact-string match) for the given value.
func StringBody(value string) *TypedBody {
	return &TypedBody{Type: "STRING", String: value}
}

// SubStringBody builds a STRING body matcher that matches when the request body
// contains the given value as a substring.
func SubStringBody(value string) *TypedBody {
	subString := true
	return &TypedBody{Type: "STRING", String: value, SubString: &subString}
}

// JSONMatchBody builds a JSON body matcher. matchType selects the comparison
// strictness: "STRICT" or "ONLY_MATCHING_FIELDS" (empty defaults to the server
// default). Use RequestBuilder.JSONBody for the simple case.
func JSONMatchBody(json, matchType string) *TypedBody {
	b := &TypedBody{Type: "JSON", JSON: json}
	if matchType != "" {
		b.MatchType = matchType
	}
	return b
}

// JSONSchemaBody builds a JSON_SCHEMA body matcher validating the request body
// against the given JSON schema (a schema string or a map/struct).
func JSONSchemaBody(schema interface{}) *TypedBody {
	return &TypedBody{Type: "JSON_SCHEMA", JSONSchema: schema}
}

// JSONPathBody builds a JSON_PATH body matcher that matches when the given
// JSONPath expression selects a value in the request body.
func JSONPathBody(jsonPath string) *TypedBody {
	return &TypedBody{Type: "JSON_PATH", JSONPath: jsonPath}
}

// XMLBody builds an XML body matcher (canonical XML comparison).
func XMLBody(xml string) *TypedBody {
	return &TypedBody{Type: "XML", XML: xml}
}

// XMLSchemaBody builds an XML_SCHEMA (XSD) body matcher.
func XMLSchemaBody(xmlSchema string) *TypedBody {
	return &TypedBody{Type: "XML_SCHEMA", XMLSchema: xmlSchema}
}

// XPathBody builds an XPATH body matcher that matches when the given XPath
// expression selects a node in the request body.
func XPathBody(xpath string) *TypedBody {
	return &TypedBody{Type: "XPATH", XPath: xpath}
}

// RegexBody builds a REGEX body matcher that matches when the request body
// matches the given regular expression.
func RegexBody(regex string) *TypedBody {
	return &TypedBody{Type: "REGEX", Regex: regex}
}

// ParameterBody builds a PARAMETERS body matcher over form-url-encoded body
// parameters.
func ParameterBody(parameters map[string][]string) *TypedBody {
	return &TypedBody{Type: "PARAMETERS", Parameters: parameters}
}

// BinaryBody builds a BINARY body matcher for the given base64-encoded bytes.
func BinaryBody(base64Bytes string) *TypedBody {
	return &TypedBody{Type: "BINARY", Base64Bytes: base64Bytes}
}

// MultipartBody builds a MULTIPART (form-data) body matcher. Any of fields,
// filenames or partContentTypes may be nil.
func MultipartBody(fields, filenames, partContentTypes map[string][]string) *TypedBody {
	return &TypedBody{Type: "MULTIPART", Fields: fields, Filenames: filenames, PartContentTypes: partContentTypes}
}

// GraphQLBody builds a GRAPHQL body matcher for the given query (operationName
// optional).
func GraphQLBody(query, operationName string) *TypedBody {
	return &TypedBody{Type: "GRAPHQL", Query: query, OperationName: operationName}
}

// WasmBody builds a WASM body matcher delegating to the named WASM module.
func WasmBody(moduleName string) *TypedBody {
	return &TypedBody{Type: "WASM", ModuleName: moduleName}
}

// AllOfBody is a composite body matcher (wire type "ALL_OF") that matches only
// when every nested body matcher matches.
type AllOfBody struct {
	Type      string        `json:"type"`
	BodyAllOf []interface{} `json:"bodyAllOf"`
}

// AllOf builds a composite body matcher that matches only when every supplied
// body matcher matches. Each element may be any body matcher value (a *TypedBody,
// a plain string, or a nested *AllOfBody).
func AllOf(bodies ...interface{}) *AllOfBody {
	return &AllOfBody{Type: "ALL_OF", BodyAllOf: bodies}
}

// Jwt is a JWT (JSON Web Token) request matcher. MockServer decodes the bearer
// token and matches the supplied claims, issuer, audience, and algorithm. Claim
// and string values follow MockServer's string-matcher semantics: an exact
// value, a regular expression, or a "!"-prefixed negated match. Header and
// Scheme select where the token is read from (defaulting to the Authorization
// header with the "Bearer" scheme).
type Jwt struct {
	Claims    map[string]string `json:"claims,omitempty"`
	Issuer    string            `json:"issuer,omitempty"`
	Audience  string            `json:"audience,omitempty"`
	Algorithm string            `json:"algorithm,omitempty"`
	Header    string            `json:"header,omitempty"`
	Scheme    string            `json:"scheme,omitempty"`
}

// NewJwt creates an empty JWT matcher to be populated via its fluent methods.
func NewJwt() *Jwt { return &Jwt{} }

// Claim adds a claim matcher. The value matches exactly, as a regular
// expression, or — when prefixed with "!" — as a negated match.
func (j *Jwt) Claim(name, value string) *Jwt {
	if j.Claims == nil {
		j.Claims = make(map[string]string)
	}
	j.Claims[name] = value
	return j
}

// WithIssuer sets the "iss" (issuer) claim matcher.
func (j *Jwt) WithIssuer(issuer string) *Jwt {
	j.Issuer = issuer
	return j
}

// WithAudience sets the "aud" (audience) claim matcher.
func (j *Jwt) WithAudience(audience string) *Jwt {
	j.Audience = audience
	return j
}

// WithAlgorithm sets the JWT signing algorithm matcher (e.g. "HS256", "RS256").
func (j *Jwt) WithAlgorithm(algorithm string) *Jwt {
	j.Algorithm = algorithm
	return j
}

// WithHeader sets the request header the token is read from (defaults to
// "Authorization").
func (j *Jwt) WithHeader(header string) *Jwt {
	j.Header = header
	return j
}

// WithScheme sets the authorization scheme prefix stripped from the header value
// before decoding the token (defaults to "Bearer").
func (j *Jwt) WithScheme(scheme string) *Jwt {
	j.Scheme = scheme
	return j
}
