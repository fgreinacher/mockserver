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

// TypedBody represents a typed body matcher (e.g., JSON, XML, JSON_PATH, REGEX).
type TypedBody struct {
	Type     string `json:"type"`
	JSON     string `json:"json,omitempty"`
	XML      string `json:"xml,omitempty"`
	JSONPath string `json:"jsonPath,omitempty"`
	Regex    string `json:"regex,omitempty"`
}

// JSONPathBody builds a JSON_PATH body matcher that matches when the given
// JSONPath expression selects a value in the request body.
func JSONPathBody(jsonPath string) *TypedBody {
	return &TypedBody{Type: "JSON_PATH", JSONPath: jsonPath}
}

// RegexBody builds a REGEX body matcher that matches when the request body
// matches the given regular expression.
func RegexBody(regex string) *TypedBody {
	return &TypedBody{Type: "REGEX", Regex: regex}
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
