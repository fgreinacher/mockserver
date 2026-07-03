package mockserver

import (
	"crypto/rand"
	"fmt"
)

// LLM mocking builder API for the MockServer Go client.
//
// This is an idiomatic Go port of the Java client LLM builders
// (org.mockserver.client.Llm / LlmMockBuilder / LlmConversationBuilder /
// LlmFailoverBuilder / TurnBuilder) and their model classes (Completion,
// ToolUse, Usage, StreamingPhysics, EmbeddingResponse). The builders produce
// exactly the same expectation wire JSON that the Java/Node/Python clients
// emit, so a mock scripted from Go is wire-equivalent to one scripted from
// any other client.
//
// The expectation action is carried in the HttpLlmResponse field of an
// Expectation (sibling of HttpRequest, ScenarioName, ScenarioState,
// NewScenarioState). Nil fields are omitted from the JSON (omitempty),
// matching the server's NON_NULL serialization.

// Provider names (UPPERCASE, matching org.mockserver.model.Provider).
const (
	ProviderAnthropic       = "ANTHROPIC"
	ProviderOpenAI          = "OPENAI"
	ProviderOpenAIResponses = "OPENAI_RESPONSES"
	ProviderGemini          = "GEMINI"
	ProviderBedrock         = "BEDROCK"
	ProviderAzureOpenAI     = "AZURE_OPENAI"
	ProviderOllama          = "OLLAMA"
)

// Roles (UPPERCASE, matching org.mockserver.llm.ParsedMessage.Role).
const (
	RoleUser      = "USER"
	RoleAssistant = "ASSISTANT"
	RoleTool      = "TOOL"
	RoleSystem    = "SYSTEM"
)

// ---------------------------------------------------------------------------
// Model types
// ---------------------------------------------------------------------------

// ToolUse is a single tool/function call emitted by the assistant.
// Arguments is a JSON string.
type ToolUse struct {
	ID        string `json:"id,omitempty"`
	Name      string `json:"name,omitempty"`
	Arguments string `json:"arguments,omitempty"`
}

// NewToolUse creates a ToolUse with the given name.
func NewToolUse(name string) *ToolUse {
	return &ToolUse{Name: name}
}

// WithID sets the tool-call id.
func (t *ToolUse) WithID(id string) *ToolUse {
	t.ID = id
	return t
}

// WithName sets the tool name.
func (t *ToolUse) WithName(name string) *ToolUse {
	t.Name = name
	return t
}

// WithArguments sets the tool-call arguments (a JSON string).
func (t *ToolUse) WithArguments(arguments string) *ToolUse {
	t.Arguments = arguments
	return t
}

// Usage holds token usage counts for a completion. CachedInputTokens,
// CacheCreationTokens and ReasoningTokens are optional provider-specific counts
// (prompt-cache read/write and reasoning tokens) mirrored from the server's
// httpLlmResponse.completion.usage schema; a meaningful 0 round-trips via the
// pointer types.
type Usage struct {
	InputTokens         *int `json:"inputTokens,omitempty"`
	OutputTokens        *int `json:"outputTokens,omitempty"`
	CachedInputTokens   *int `json:"cachedInputTokens,omitempty"`
	CacheCreationTokens *int `json:"cacheCreationTokens,omitempty"`
	ReasoningTokens     *int `json:"reasoningTokens,omitempty"`
}

// NewUsage creates an empty Usage.
func NewUsage() *Usage {
	return &Usage{}
}

// WithInputTokens sets the input-token count (must be >= 0).
func (u *Usage) WithInputTokens(n int) *Usage {
	if n < 0 {
		panic("mockserver: inputTokens must be >= 0")
	}
	u.InputTokens = &n
	return u
}

// WithOutputTokens sets the output-token count (must be >= 0).
func (u *Usage) WithOutputTokens(n int) *Usage {
	if n < 0 {
		panic("mockserver: outputTokens must be >= 0")
	}
	u.OutputTokens = &n
	return u
}

// StreamingPhysics controls the timing physics of a streamed (SSE) completion.
// TimeToFirstToken serializes as a Delay: {timeUnit, value}.
type StreamingPhysics struct {
	TimeToFirstToken *Delay   `json:"timeToFirstToken,omitempty"`
	TokensPerSecond  *int     `json:"tokensPerSecond,omitempty"`
	Jitter           *float64 `json:"jitter,omitempty"`
	Seed             *int64   `json:"seed,omitempty"`
	// SubwordStreaming splits streamed tokens at sub-word boundaries (server
	// default true); the pointer preserves an explicit false.
	SubwordStreaming *bool `json:"subwordStreaming,omitempty"`
}

// NewStreamingPhysics creates an empty StreamingPhysics.
func NewStreamingPhysics() *StreamingPhysics {
	return &StreamingPhysics{}
}

// WithTimeToFirstToken sets the time-to-first-token delay.
func (s *StreamingPhysics) WithTimeToFirstToken(timeUnit string, value int) *StreamingPhysics {
	s.TimeToFirstToken = &Delay{TimeUnit: timeUnit, Value: value}
	return s
}

// WithTokensPerSecond sets the streaming token rate (must be in [1, 10000]).
func (s *StreamingPhysics) WithTokensPerSecond(n int) *StreamingPhysics {
	if n < 1 || n > 10000 {
		panic("mockserver: tokensPerSecond must be between 1 and 10000")
	}
	s.TokensPerSecond = &n
	return s
}

// WithJitter sets the timing jitter (must be in [0.0, 1.0]).
func (s *StreamingPhysics) WithJitter(j float64) *StreamingPhysics {
	if j < 0.0 || j > 1.0 {
		panic("mockserver: jitter must be between 0.0 and 1.0")
	}
	s.Jitter = &j
	return s
}

// WithSeed sets the deterministic seed.
func (s *StreamingPhysics) WithSeed(seed int64) *StreamingPhysics {
	s.Seed = &seed
	return s
}

// Completion is a mocked LLM chat/completion response, provider-agnostic.
type Completion struct {
	Text             string            `json:"text,omitempty"`
	ToolCalls        []*ToolUse        `json:"toolCalls,omitempty"`
	StopReason       string            `json:"stopReason,omitempty"`
	Usage            *Usage            `json:"usage,omitempty"`
	Streaming        *bool             `json:"streaming,omitempty"`
	StreamingPhysics *StreamingPhysics `json:"streamingPhysics,omitempty"`
	OutputSchema     string            `json:"outputSchema,omitempty"`
	// EnforceOutputSchema makes the server validate the generated text against
	// OutputSchema (the pointer preserves an explicit false).
	EnforceOutputSchema *bool `json:"enforceOutputSchema,omitempty"`
	// ToolChoice constrains tool selection (e.g. "auto", "none", "required").
	ToolChoice string `json:"toolChoice,omitempty"`
	// ReasoningText / ReasoningSignature carry mocked reasoning content and its
	// provider signature (e.g. Anthropic extended thinking).
	ReasoningText      string `json:"reasoningText,omitempty"`
	ReasoningSignature string `json:"reasoningSignature,omitempty"`
	Model              string `json:"model,omitempty"`
}

// NewCompletion creates an empty Completion.
func NewCompletion() *Completion {
	return &Completion{}
}

// WithText sets the completion text.
func (c *Completion) WithText(text string) *Completion {
	c.Text = text
	return c
}

// WithToolCall appends a single tool call.
func (c *Completion) WithToolCall(toolCall *ToolUse) *Completion {
	c.ToolCalls = append(c.ToolCalls, toolCall)
	return c
}

// WithToolCalls sets the tool calls.
func (c *Completion) WithToolCalls(toolCalls ...*ToolUse) *Completion {
	c.ToolCalls = toolCalls
	return c
}

// WithStopReason sets the stop reason.
func (c *Completion) WithStopReason(stopReason string) *Completion {
	c.StopReason = stopReason
	return c
}

// WithUsage sets the token usage.
func (c *Completion) WithUsage(usage *Usage) *Completion {
	c.Usage = usage
	return c
}

// WithStreaming sets whether the response is streamed.
func (c *Completion) WithStreaming(streaming bool) *Completion {
	c.Streaming = &streaming
	return c
}

// WithStreamingOn enables streaming (shorthand for WithStreaming(true)).
func (c *Completion) WithStreamingOn() *Completion {
	return c.WithStreaming(true)
}

// WithStreamingPhysics sets the streaming physics. It does NOT enable
// streaming on its own — call WithStreamingOn()/WithStreaming(true) explicitly.
func (c *Completion) WithStreamingPhysics(physics *StreamingPhysics) *Completion {
	c.StreamingPhysics = physics
	return c
}

// WithOutputSchema sets the structured-output JSON schema (a JSON string).
func (c *Completion) WithOutputSchema(outputSchema string) *Completion {
	c.OutputSchema = outputSchema
	return c
}

// WithModel sets the model name reported in the completion.
func (c *Completion) WithModel(model string) *Completion {
	c.Model = model
	return c
}

// EmbeddingResponse is a mocked embedding response (vector shape, determinism).
type EmbeddingResponse struct {
	Dimensions             *int   `json:"dimensions,omitempty"`
	DeterministicFromInput *bool  `json:"deterministicFromInput,omitempty"`
	Seed                   *int64 `json:"seed,omitempty"`
}

// NewEmbedding creates an empty EmbeddingResponse.
func NewEmbedding() *EmbeddingResponse {
	return &EmbeddingResponse{}
}

// WithDimensions sets the embedding dimensionality.
func (e *EmbeddingResponse) WithDimensions(dimensions int) *EmbeddingResponse {
	e.Dimensions = &dimensions
	return e
}

// WithDeterministicFromInput sets whether the embedding is deterministic from input.
func (e *EmbeddingResponse) WithDeterministicFromInput(deterministic bool) *EmbeddingResponse {
	e.DeterministicFromInput = &deterministic
	return e
}

// WithSeed sets the deterministic seed.
func (e *EmbeddingResponse) WithSeed(seed int64) *EmbeddingResponse {
	e.Seed = &seed
	return e
}

// NormalizationOptions configures opt-in prompt normalization applied before
// text predicates.
type NormalizationOptions struct {
	CollapseWhitespace        *bool    `json:"collapseWhitespace,omitempty"`
	Lowercase                 *bool    `json:"lowercase,omitempty"`
	SortJSONKeys              *bool    `json:"sortJsonKeys,omitempty"`
	DropBuiltInVolatileFields *bool    `json:"dropBuiltInVolatileFields,omitempty"`
	DropVolatileFields        []string `json:"dropVolatileFields,omitempty"`
}

// ConversationPredicates carries the per-turn matching predicates serialized
// into the conversationPredicates field of an LLM response.
type ConversationPredicates struct {
	TurnIndex             *int                  `json:"turnIndex,omitempty"`
	LatestMessageContains string                `json:"latestMessageContains,omitempty"`
	LatestMessageMatches  string                `json:"latestMessageMatches,omitempty"`
	LatestMessageRole     string                `json:"latestMessageRole,omitempty"`
	ContainsToolResultFor string                `json:"containsToolResultFor,omitempty"`
	SemanticMatchAgainst  string                `json:"semanticMatchAgainst,omitempty"`
	Normalization         *NormalizationOptions `json:"normalization,omitempty"`
}

// RerankResponse is a mocked reranking response (shape, determinism), a sibling
// of Embedding in httpLlmResponse.rerank.
type RerankResponse struct {
	TopN                   *int   `json:"topN,omitempty"`
	DeterministicFromInput *bool  `json:"deterministicFromInput,omitempty"`
	Seed                   *int64 `json:"seed,omitempty"`
}

// ModerationResponse is a mocked content-moderation verdict
// (httpLlmResponse.moderation): the flagged categories and the reporting model.
type ModerationResponse struct {
	FlaggedCategories []string `json:"flaggedCategories,omitempty"`
	Model             string   `json:"model,omitempty"`
}

// ContentFilterResponse is a mocked Azure-style content-filter verdict
// (httpLlmResponse.contentFilter); each field is a per-category severity string
// (e.g. "safe", "low", "medium", "high").
type ContentFilterResponse struct {
	Hate     string `json:"hate,omitempty"`
	Sexual   string `json:"sexual,omitempty"`
	Violence string `json:"violence,omitempty"`
	SelfHarm string `json:"selfHarm,omitempty"`
}

// HttpLlmResponse is the httpLlmResponse action payload of an LLM expectation.
type HttpLlmResponse struct {
	Provider               string                  `json:"provider,omitempty"`
	Model                  string                  `json:"model,omitempty"`
	Completion             *Completion             `json:"completion,omitempty"`
	Embedding              *EmbeddingResponse      `json:"embedding,omitempty"`
	Rerank                 *RerankResponse         `json:"rerank,omitempty"`
	Moderation             *ModerationResponse     `json:"moderation,omitempty"`
	ContentFilter          *ContentFilterResponse  `json:"contentFilter,omitempty"`
	ConversationPredicates *ConversationPredicates `json:"conversationPredicates,omitempty"`
	Chaos                  interface{}             `json:"chaos,omitempty"`
	Delay                  *Delay                  `json:"delay,omitempty"`
	// Primary marks this response as the primary action when multiple are present.
	Primary *bool `json:"primary,omitempty"`
}

// ---------------------------------------------------------------------------
// IsolationSource — encodes as "<kind>:<name>" (e.g. "header:x-session-id").
// ---------------------------------------------------------------------------

// IsolationSource identifies where a per-session isolation key is read from an
// inbound request.
type IsolationSource struct {
	Kind string
	Name string
}

// Encode returns the "<kind>:<name>" wire encoding.
func (s IsolationSource) encode() string {
	return s.Kind + ":" + s.Name
}

// IsolateByHeader isolates conversations by the named request header.
func IsolateByHeader(name string) IsolationSource {
	return IsolationSource{Kind: "header", Name: name}
}

// IsolateByQueryParameter isolates conversations by the named query parameter.
func IsolateByQueryParameter(name string) IsolationSource {
	return IsolationSource{Kind: "query_parameter", Name: name}
}

// IsolateByCookie isolates conversations by the named cookie.
func IsolateByCookie(name string) IsolationSource {
	return IsolationSource{Kind: "cookie", Name: name}
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func postMatcher(path string) *HttpRequest {
	return &HttpRequest{Method: "POST", Path: path}
}

// uuidv4 returns a random RFC4122 v4 UUID using crypto/rand.
func uuidv4() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic("mockserver: failed to generate uuid: " + err.Error())
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// ---------------------------------------------------------------------------
// LlmMockBuilder — a single completion or embedding mock.
// ---------------------------------------------------------------------------

// LlmMockBuilder builds a single LLM mock expectation.
type LlmMockBuilder struct {
	path       string
	provider   string
	model      string
	completion *Completion
	embedding  *EmbeddingResponse
}

// LlmMock starts building a single LLM completion/embedding mock at path.
func LlmMock(path string) *LlmMockBuilder {
	return &LlmMockBuilder{path: path}
}

// WithProvider sets the provider (use the Provider* constants).
func (b *LlmMockBuilder) WithProvider(provider string) *LlmMockBuilder {
	b.provider = provider
	return b
}

// WithModel sets the model name.
func (b *LlmMockBuilder) WithModel(model string) *LlmMockBuilder {
	b.model = model
	return b
}

// RespondingWithCompletion responds with the given completion (clears embedding).
func (b *LlmMockBuilder) RespondingWithCompletion(completion *Completion) *LlmMockBuilder {
	b.completion = completion
	b.embedding = nil
	return b
}

// RespondingWithEmbedding responds with the given embedding (clears completion).
func (b *LlmMockBuilder) RespondingWithEmbedding(embedding *EmbeddingResponse) *LlmMockBuilder {
	b.embedding = embedding
	b.completion = nil
	return b
}

// Build returns the single expectation for this mock.
func (b *LlmMockBuilder) Build() []Expectation {
	return []Expectation{{
		HttpRequest: postMatcher(b.path),
		HttpLlmResponse: &HttpLlmResponse{
			Provider:   b.provider,
			Model:      b.model,
			Completion: b.completion,
			Embedding:  b.embedding,
		},
	}}
}

// ApplyTo registers the built expectation with the client via Upsert.
func (b *LlmMockBuilder) ApplyTo(c *Client) ([]Expectation, error) {
	return c.Upsert(b.Build()...)
}

// ---------------------------------------------------------------------------
// TurnBuilder + LlmConversationBuilder
// ---------------------------------------------------------------------------

const (
	scenarioPrefix  = "__llm_conv_"
	isolationMarker = "__iso="
	doneState       = "__done"
)

// TurnBuilder configures one turn of a conversation mock.
type TurnBuilder struct {
	parent                *LlmConversationBuilder
	turnIndex             *int
	latestMessageContains string
	latestMessageMatches  string
	latestMessageRole     string
	containsToolResultFor string
	semanticMatchAgainst  string
	normalization         *NormalizationOptions
	chaos                 interface{}
	completion            *Completion
}

// WhenTurnIndex matches only when the request is at the given (0-based) turn index.
func (t *TurnBuilder) WhenTurnIndex(n int) *TurnBuilder {
	t.turnIndex = &n
	return t
}

// WhenLatestMessageContains matches when the latest message contains text.
func (t *TurnBuilder) WhenLatestMessageContains(text string) *TurnBuilder {
	t.latestMessageContains = text
	return t
}

// WhenLatestMessageMatches matches when the latest message matches the regex.
func (t *TurnBuilder) WhenLatestMessageMatches(regex string) *TurnBuilder {
	t.latestMessageMatches = regex
	return t
}

// WhenLatestMessageRole matches on the role of the latest message.
func (t *TurnBuilder) WhenLatestMessageRole(role string) *TurnBuilder {
	t.latestMessageRole = role
	return t
}

// WhenContainsToolResultFor matches when a tool result for toolName is present.
func (t *TurnBuilder) WhenContainsToolResultFor(toolName string) *TurnBuilder {
	t.containsToolResultFor = toolName
	return t
}

// WhenSemanticMatch matches semantically against the expected meaning.
func (t *TurnBuilder) WhenSemanticMatch(expectedMeaning string) *TurnBuilder {
	t.semanticMatchAgainst = expectedMeaning
	return t
}

// WithNormalization applies normalization options before matching. Not counted
// as a predicate on its own.
func (t *TurnBuilder) WithNormalization(normalization *NormalizationOptions) *TurnBuilder {
	t.normalization = normalization
	return t
}

// WithChaos applies a chaos profile to this turn's response.
func (t *TurnBuilder) WithChaos(chaos interface{}) *TurnBuilder {
	t.chaos = chaos
	return t
}

// RespondingWith sets the completion returned for this turn.
func (t *TurnBuilder) RespondingWith(completion *Completion) *TurnBuilder {
	t.completion = completion
	return t
}

// Turn starts a new turn on the parent conversation builder.
func (t *TurnBuilder) Turn() *TurnBuilder {
	return t.parent.Turn()
}

// AndThen returns the parent conversation builder.
func (t *TurnBuilder) AndThen() *LlmConversationBuilder {
	return t.parent
}

// Build builds the parent conversation's expectations.
func (t *TurnBuilder) Build() []Expectation {
	return t.parent.Build()
}

// ApplyTo registers the parent conversation's expectations with the client.
func (t *TurnBuilder) ApplyTo(c *Client) ([]Expectation, error) {
	return t.parent.ApplyTo(c)
}

// hasAnyPredicate intentionally excludes normalization (a modifier, not a predicate).
func (t *TurnBuilder) hasAnyPredicate() bool {
	return t.turnIndex != nil ||
		t.latestMessageContains != "" ||
		t.latestMessageMatches != "" ||
		t.latestMessageRole != "" ||
		t.containsToolResultFor != "" ||
		t.semanticMatchAgainst != ""
}

func (t *TurnBuilder) predicates() *ConversationPredicates {
	return &ConversationPredicates{
		TurnIndex:             t.turnIndex,
		LatestMessageContains: t.latestMessageContains,
		LatestMessageMatches:  t.latestMessageMatches,
		LatestMessageRole:     t.latestMessageRole,
		ContainsToolResultFor: t.containsToolResultFor,
		SemanticMatchAgainst:  t.semanticMatchAgainst,
		Normalization:         t.normalization,
	}
}

// LlmConversationBuilder builds a multi-turn LLM conversation mock using
// MockServer scenario state advancement.
type LlmConversationBuilder struct {
	path            string
	provider        string
	model           string
	isolationSource *IsolationSource
	turns           []*TurnBuilder
}

// Conversation starts building a multi-turn LLM conversation mock.
func Conversation() *LlmConversationBuilder {
	return &LlmConversationBuilder{}
}

// WithPath sets the request path for all turns.
func (b *LlmConversationBuilder) WithPath(path string) *LlmConversationBuilder {
	b.path = path
	return b
}

// WithProvider sets the provider (use the Provider* constants).
func (b *LlmConversationBuilder) WithProvider(provider string) *LlmConversationBuilder {
	b.provider = provider
	return b
}

// WithModel sets the model name.
func (b *LlmConversationBuilder) WithModel(model string) *LlmConversationBuilder {
	b.model = model
	return b
}

// IsolateBy isolates conversation state by the given request attribute.
func (b *LlmConversationBuilder) IsolateBy(source IsolationSource) *LlmConversationBuilder {
	b.isolationSource = &source
	return b
}

// Turn appends a new turn and returns its builder.
func (b *LlmConversationBuilder) Turn() *TurnBuilder {
	turn := &TurnBuilder{parent: b}
	b.turns = append(b.turns, turn)
	return turn
}

// Build returns the ordered list of expectations for the conversation.
func (b *LlmConversationBuilder) Build() []Expectation {
	if len(b.turns) == 0 {
		panic("mockserver: at least one turn must be defined")
	}
	if b.path == "" {
		panic("mockserver: path must be set")
	}
	if b.provider == "" {
		panic("mockserver: provider must be set")
	}

	scenarioName := scenarioPrefix + uuidv4()
	if b.isolationSource != nil {
		scenarioName = scenarioName + isolationMarker + b.isolationSource.encode()
	}

	n := len(b.turns)
	expectations := make([]Expectation, 0, n)
	for i, turn := range b.turns {
		nextState := doneState
		if i < n-1 {
			nextState = fmt.Sprintf("turn_%d", i+1)
		}
		state := "Started"
		if i != 0 {
			state = fmt.Sprintf("turn_%d", i)
		}

		action := &HttpLlmResponse{
			Provider:   b.provider,
			Model:      b.model,
			Completion: turn.completion,
			Chaos:      turn.chaos,
		}
		if turn.hasAnyPredicate() {
			action.ConversationPredicates = turn.predicates()
		}

		expectations = append(expectations, Expectation{
			HttpRequest:      postMatcher(b.path),
			ScenarioName:     scenarioName,
			ScenarioState:    state,
			NewScenarioState: nextState,
			HttpLlmResponse:  action,
		})
	}
	return expectations
}

// ApplyTo registers the conversation expectations with the client via Upsert.
func (b *LlmConversationBuilder) ApplyTo(c *Client) ([]Expectation, error) {
	return c.Upsert(b.Build()...)
}

// ---------------------------------------------------------------------------
// LlmFailoverBuilder — N failures then a success completion.
// ---------------------------------------------------------------------------

func defaultErrorBody(statusCode int) string {
	var errType, message string
	switch statusCode {
	case 429:
		errType = "rate_limit_error"
		message = "Rate limit exceeded. Please retry after a brief wait."
	case 500:
		errType = "internal_server_error"
		message = "An internal error occurred. Please retry your request."
	case 502:
		errType = "bad_gateway"
		message = "Bad gateway. The upstream server returned an invalid response."
	case 503:
		errType = "service_unavailable"
		message = "The service is temporarily overloaded. Please retry later."
	default:
		errType = "error"
		message = fmt.Sprintf("Request failed with status %d", statusCode)
	}
	return `{"error":{"type":"` + errType + `","message":"` + message + `"}}`
}

type failureSpec struct {
	statusCode int
	errorBody  string // empty string means "use default error body"
	hasBody    bool
}

// LlmFailoverBuilder builds a provider failover/retry scenario: an ordered list
// of failure expectations (limited times) followed by a single success
// expectation (unlimited times). Consecutive identical failures are coalesced.
type LlmFailoverBuilder struct {
	path              string
	provider          string
	model             string
	failures          []failureSpec
	successCompletion *Completion
}

// LlmFailover starts building an LLM failover scenario.
func LlmFailover() *LlmFailoverBuilder {
	return &LlmFailoverBuilder{}
}

// WithPath sets the request path.
func (b *LlmFailoverBuilder) WithPath(path string) *LlmFailoverBuilder {
	b.path = path
	return b
}

// WithProvider sets the provider (use the Provider* constants).
func (b *LlmFailoverBuilder) WithProvider(provider string) *LlmFailoverBuilder {
	b.provider = provider
	return b
}

// WithModel sets the model name.
func (b *LlmFailoverBuilder) WithModel(model string) *LlmFailoverBuilder {
	b.model = model
	return b
}

func validateStatusCode(statusCode int) {
	if statusCode < 100 || statusCode > 599 {
		panic(fmt.Sprintf("mockserver: statusCode must be between 100 and 599, got %d", statusCode))
	}
}

// FailWith adds one failure attempt with the given status and the default
// error body for that status.
func (b *LlmFailoverBuilder) FailWith(statusCode int) *LlmFailoverBuilder {
	validateStatusCode(statusCode)
	b.failures = append(b.failures, failureSpec{statusCode: statusCode})
	return b
}

// FailWithBody adds one failure attempt with the given status and a custom
// error body.
func (b *LlmFailoverBuilder) FailWithBody(statusCode int, errorBody string) *LlmFailoverBuilder {
	validateStatusCode(statusCode)
	b.failures = append(b.failures, failureSpec{statusCode: statusCode, errorBody: errorBody, hasBody: true})
	return b
}

// FailWithCount adds count failure attempts with the given status and default
// error bodies.
func (b *LlmFailoverBuilder) FailWithCount(statusCode, count int) *LlmFailoverBuilder {
	validateStatusCode(statusCode)
	if count < 1 {
		panic(fmt.Sprintf("mockserver: count must be >= 1, got %d", count))
	}
	for i := 0; i < count; i++ {
		b.failures = append(b.failures, failureSpec{statusCode: statusCode})
	}
	return b
}

// ThenRespondWith sets the success completion served after all failures.
func (b *LlmFailoverBuilder) ThenRespondWith(completion *Completion) *LlmFailoverBuilder {
	b.successCompletion = completion
	return b
}

// FailureCount returns the number of configured failure attempts.
func (b *LlmFailoverBuilder) FailureCount() int {
	return len(b.failures)
}

type coalescedFailure struct {
	statusCode int
	errorBody  string
	hasBody    bool
	count      int
}

func (b *LlmFailoverBuilder) coalesceFailures() []coalescedFailure {
	var result []coalescedFailure
	for _, spec := range b.failures {
		if len(result) > 0 {
			last := &result[len(result)-1]
			if last.statusCode == spec.statusCode && last.hasBody == spec.hasBody && last.errorBody == spec.errorBody {
				last.count++
				continue
			}
		}
		result = append(result, coalescedFailure{
			statusCode: spec.statusCode,
			errorBody:  spec.errorBody,
			hasBody:    spec.hasBody,
			count:      1,
		})
	}
	return result
}

// Build returns the ordered list of failover expectations.
func (b *LlmFailoverBuilder) Build() []Expectation {
	if b.path == "" {
		panic("mockserver: path must be set")
	}
	if b.provider == "" {
		panic("mockserver: provider must be set")
	}
	if len(b.failures) == 0 {
		panic("mockserver: at least one failure must be defined")
	}
	if b.successCompletion == nil {
		panic("mockserver: success completion must be set via ThenRespondWith()")
	}

	coalesced := b.coalesceFailures()
	expectations := make([]Expectation, 0, len(coalesced)+1)

	for _, cf := range coalesced {
		body := cf.errorBody
		if !cf.hasBody {
			body = defaultErrorBody(cf.statusCode)
		}
		expectations = append(expectations, Expectation{
			HttpRequest: postMatcher(b.path),
			Times:       &Times{RemainingTimes: cf.count, Unlimited: false},
			TimeToLive:  &TimeToLive{Unlimited: true},
			HttpResponse: &HttpResponse{
				StatusCode: cf.statusCode,
				Headers:    map[string][]string{"Content-Type": {"application/json"}},
				Body:       body,
			},
		})
	}

	expectations = append(expectations, Expectation{
		HttpRequest: postMatcher(b.path),
		Times:       &Times{Unlimited: true},
		TimeToLive:  &TimeToLive{Unlimited: true},
		HttpLlmResponse: &HttpLlmResponse{
			Provider:   b.provider,
			Model:      b.model,
			Completion: b.successCompletion,
		},
	})

	return expectations
}

// ApplyTo registers the failover expectations with the client via Upsert.
func (b *LlmFailoverBuilder) ApplyTo(c *Client) ([]Expectation, error) {
	return c.Upsert(b.Build()...)
}
