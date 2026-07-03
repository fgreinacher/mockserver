"""Idiomatic Python LLM-mocking API for MockServer.

This module mirrors the Java client LLM builders
(``org.mockserver.client.Llm``, ``LlmMockBuilder``, ``LlmConversationBuilder``,
``TurnBuilder``, ``LlmFailoverBuilder``) and the underlying server-side model
classes (``Completion``, ``ToolUse``, ``Usage``, ``StreamingPhysics``,
``EmbeddingResponse``, ``ConversationPredicates``, ``HttpLlmResponse``).

The wire JSON produced here is byte-for-byte equivalent (modulo key ordering,
which the MockServer JSON API ignores) to what the Java client emits:

* The top-level expectation action key is ``httpLlmResponse``.
* ``httpLlmResponse`` carries ``provider`` (an upper-case enum string),
  ``model``, ``completion``, ``embedding``, ``conversationPredicates``,
  ``chaos`` and ``delay``.
* ``completion`` carries ``text``, ``toolCalls`` (list of ``{id,name,arguments}``),
  ``stopReason``, ``usage`` (``{inputTokens,outputTokens}``), ``streaming``,
  ``streamingPhysics`` (``{timeToFirstToken:{timeUnit,value}, tokensPerSecond,
  jitter, seed}``), ``outputSchema`` and ``model``.
* ``embedding`` carries ``dimensions``, ``deterministicFromInput`` and ``seed``.
* Conversation turns are encoded with MockServer scenario state advancement —
  ``scenarioName`` (an auto-generated ``__llm_conv_<uuid>`` string, with an
  optional ``__iso=<kind>:<name>`` isolation suffix), ``scenarioState`` and
  ``newScenarioState``.

Field names were sourced from the server-side serializers under
``mockserver-core`` (``HttpLlmResponseDTOSerializer``) and the model classes,
cross-checked against ``ExpectationLlmRoundTripTest``.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from typing import Any

from mockserver.models import Delay, Expectation, HttpRequest, _strip_none

# ---------------------------------------------------------------------------
# Provider (mirrors org.mockserver.model.Provider — serialized as the enum NAME)
# ---------------------------------------------------------------------------


class Provider:
    """LLM provider names. Serialized on the wire as the upper-case enum name."""

    ANTHROPIC = "ANTHROPIC"
    OPENAI = "OPENAI"
    OPENAI_RESPONSES = "OPENAI_RESPONSES"
    GEMINI = "GEMINI"
    BEDROCK = "BEDROCK"
    AZURE_OPENAI = "AZURE_OPENAI"
    OLLAMA = "OLLAMA"


class Role:
    """Parsed-message roles (mirrors org.mockserver.llm.ParsedMessage.Role)."""

    USER = "USER"
    ASSISTANT = "ASSISTANT"
    TOOL = "TOOL"
    SYSTEM = "SYSTEM"


# ---------------------------------------------------------------------------
# ToolUse (mirrors org.mockserver.model.ToolUse)
# ---------------------------------------------------------------------------


@dataclass
class ToolUse:
    """A single tool/function call emitted by the assistant."""

    name: str = ""
    id: str | None = None
    arguments: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "id": self.id,
            "name": self.name,
            "arguments": self.arguments,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> ToolUse | None:
        if data is None:
            return None
        return cls(
            name=data.get("name", ""),
            id=data.get("id"),
            arguments=data.get("arguments"),
        )

    def with_id(self, id: str) -> ToolUse:
        self.id = id
        return self

    def with_arguments(self, arguments: str) -> ToolUse:
        self.arguments = arguments
        return self


def tool_use(name: str, arguments: str | None = None, id: str | None = None) -> ToolUse:
    """Factory mirroring ``ToolUse.toolUse(name)`` plus optional args/id."""
    return ToolUse(name=name, arguments=arguments, id=id)


# ---------------------------------------------------------------------------
# Usage (mirrors org.mockserver.model.Usage)
# ---------------------------------------------------------------------------


@dataclass
class Usage:
    """Token usage counts for a completion."""

    input_tokens: int | None = None
    output_tokens: int | None = None
    # Extended token accounting: prompt-cache read/write and reasoning tokens.
    cached_input_tokens: int | None = None
    cache_creation_tokens: int | None = None
    reasoning_tokens: int | None = None

    def __post_init__(self) -> None:
        if self.input_tokens is not None and self.input_tokens < 0:
            raise ValueError("input_tokens must be >= 0")
        if self.output_tokens is not None and self.output_tokens < 0:
            raise ValueError("output_tokens must be >= 0")
        for name in ("cached_input_tokens", "cache_creation_tokens", "reasoning_tokens"):
            value = getattr(self, name)
            if value is not None and value < 0:
                raise ValueError(f"{name} must be >= 0")

    def to_dict(self) -> dict:
        return _strip_none({
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "cachedInputTokens": self.cached_input_tokens,
            "cacheCreationTokens": self.cache_creation_tokens,
            "reasoningTokens": self.reasoning_tokens,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> Usage | None:
        if data is None:
            return None
        return cls(
            input_tokens=data.get("inputTokens"),
            output_tokens=data.get("outputTokens"),
            cached_input_tokens=data.get("cachedInputTokens"),
            cache_creation_tokens=data.get("cacheCreationTokens"),
            reasoning_tokens=data.get("reasoningTokens"),
        )

    def with_input_tokens(self, input_tokens: int) -> Usage:
        self.input_tokens = input_tokens
        self.__post_init__()
        return self

    def with_output_tokens(self, output_tokens: int) -> Usage:
        self.output_tokens = output_tokens
        self.__post_init__()
        return self


def usage(input_tokens: int | None = None, output_tokens: int | None = None) -> Usage:
    """Factory mirroring ``Usage.usage()`` / ``inputTokens`` / ``outputTokens``."""
    return Usage(input_tokens=input_tokens, output_tokens=output_tokens)


# ---------------------------------------------------------------------------
# StreamingPhysics (mirrors org.mockserver.model.StreamingPhysics)
# ---------------------------------------------------------------------------


@dataclass
class StreamingPhysics:
    """Controls the timing physics of a streamed (SSE) completion."""

    time_to_first_token: Delay | None = None
    tokens_per_second: int | None = None
    jitter: float | None = None
    seed: int | None = None
    # Stream at sub-word (character/BPE-fragment) granularity rather than whole tokens.
    subword_streaming: bool | None = None

    def __post_init__(self) -> None:
        if self.tokens_per_second is not None and not (1 <= self.tokens_per_second <= 10000):
            raise ValueError("tokens_per_second must be between 1 and 10000")
        if self.jitter is not None and not (0.0 <= self.jitter <= 1.0):
            raise ValueError("jitter must be between 0.0 and 1.0")

    def to_dict(self) -> dict:
        return _strip_none({
            "timeToFirstToken": self.time_to_first_token.to_dict() if self.time_to_first_token else None,
            "tokensPerSecond": self.tokens_per_second,
            "jitter": self.jitter,
            "seed": self.seed,
            "subwordStreaming": self.subword_streaming,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> StreamingPhysics | None:
        if data is None:
            return None
        return cls(
            time_to_first_token=Delay.from_dict(data.get("timeToFirstToken")),
            tokens_per_second=data.get("tokensPerSecond"),
            jitter=data.get("jitter"),
            seed=data.get("seed"),
            subword_streaming=data.get("subwordStreaming"),
        )

    def with_time_to_first_token(self, delay: Delay) -> StreamingPhysics:
        self.time_to_first_token = delay
        return self

    def with_tokens_per_second(self, tokens_per_second: int) -> StreamingPhysics:
        self.tokens_per_second = tokens_per_second
        self.__post_init__()
        return self

    def with_jitter(self, jitter: float) -> StreamingPhysics:
        self.jitter = jitter
        self.__post_init__()
        return self

    def with_seed(self, seed: int) -> StreamingPhysics:
        self.seed = seed
        return self


def streaming_physics(
    tokens_per_second: int | None = None,
    jitter: float | None = None,
    time_to_first_token: Delay | None = None,
    seed: int | None = None,
) -> StreamingPhysics:
    """Factory mirroring ``StreamingPhysics.streamingPhysics()``."""
    return StreamingPhysics(
        time_to_first_token=time_to_first_token,
        tokens_per_second=tokens_per_second,
        jitter=jitter,
        seed=seed,
    )


def time_to_first_token(value: int, time_unit: str = "MILLISECONDS") -> Delay:
    """Factory mirroring ``Llm.timeToFirstToken(value, timeUnit)`` — a Delay."""
    return Delay(time_unit=time_unit, value=value)


# ---------------------------------------------------------------------------
# EmbeddingResponse (mirrors org.mockserver.model.EmbeddingResponse)
# ---------------------------------------------------------------------------


@dataclass
class EmbeddingResponse:
    """A mocked embedding response (vector shape, determinism)."""

    dimensions: int | None = None
    deterministic_from_input: bool | None = None
    seed: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "dimensions": self.dimensions,
            "deterministicFromInput": self.deterministic_from_input,
            "seed": self.seed,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> EmbeddingResponse | None:
        if data is None:
            return None
        return cls(
            dimensions=data.get("dimensions"),
            deterministic_from_input=data.get("deterministicFromInput"),
            seed=data.get("seed"),
        )

    def with_dimensions(self, dimensions: int) -> EmbeddingResponse:
        self.dimensions = dimensions
        return self

    def with_deterministic_from_input(self, deterministic: bool) -> EmbeddingResponse:
        self.deterministic_from_input = deterministic
        return self

    def with_seed(self, seed: int) -> EmbeddingResponse:
        self.seed = seed
        return self


def embedding(
    dimensions: int | None = None,
    deterministic_from_input: bool | None = None,
    seed: int | None = None,
) -> EmbeddingResponse:
    """Factory mirroring ``EmbeddingResponse.embedding()``."""
    return EmbeddingResponse(
        dimensions=dimensions,
        deterministic_from_input=deterministic_from_input,
        seed=seed,
    )


# ---------------------------------------------------------------------------
# NormalizationOptions (mirrors org.mockserver.model.NormalizationOptions)
# ---------------------------------------------------------------------------


@dataclass
class NormalizationOptions:
    """Opt-in prompt normalisation applied before text predicates."""

    collapse_whitespace: bool | None = None
    lowercase: bool | None = None
    sort_json_keys: bool | None = None
    drop_built_in_volatile_fields: bool | None = None
    drop_volatile_fields: list[str] | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "collapseWhitespace": self.collapse_whitespace,
            "lowercase": self.lowercase,
            "sortJsonKeys": self.sort_json_keys,
            "dropBuiltInVolatileFields": self.drop_built_in_volatile_fields,
            "dropVolatileFields": self.drop_volatile_fields,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> NormalizationOptions | None:
        if data is None:
            return None
        return cls(
            collapse_whitespace=data.get("collapseWhitespace"),
            lowercase=data.get("lowercase"),
            sort_json_keys=data.get("sortJsonKeys"),
            drop_built_in_volatile_fields=data.get("dropBuiltInVolatileFields"),
            drop_volatile_fields=data.get("dropVolatileFields"),
        )


# ---------------------------------------------------------------------------
# LlmChaosProfile (mirrors org.mockserver.model.LlmChaosProfile)
# ---------------------------------------------------------------------------


@dataclass
class LlmChaosProfile:
    """Fault/chaos profile applied to an LLM response (errors, truncation)."""

    error_status: int | None = None
    retry_after: str | None = None
    error_probability: float | None = None
    truncate_mode: str | None = None
    truncate_at_fraction: float | None = None
    malformed_sse: bool | None = None
    seed: int | None = None
    quota_name: str | None = None
    quota_limit: int | None = None
    quota_window_millis: int | None = None
    quota_error_status: int | None = None
    token_quota_limit: int | None = None
    token_quota_window_millis: int | None = None
    # Probability [0,1] of blocking a response as a simulated content-filter hit.
    content_filter_block_probability: float | None = None
    # Provider-specific error flavour to synthesize (e.g. "overloaded").
    error_kind: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "errorStatus": self.error_status,
            "retryAfter": self.retry_after,
            "errorProbability": self.error_probability,
            "truncateMode": self.truncate_mode,
            "truncateAtFraction": self.truncate_at_fraction,
            "malformedSse": self.malformed_sse,
            "seed": self.seed,
            "quotaName": self.quota_name,
            "quotaLimit": self.quota_limit,
            "quotaWindowMillis": self.quota_window_millis,
            "quotaErrorStatus": self.quota_error_status,
            "tokenQuotaLimit": self.token_quota_limit,
            "tokenQuotaWindowMillis": self.token_quota_window_millis,
            "contentFilterBlockProbability": self.content_filter_block_probability,
            "errorKind": self.error_kind,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> LlmChaosProfile | None:
        if data is None:
            return None
        return cls(
            error_status=data.get("errorStatus"),
            retry_after=data.get("retryAfter"),
            error_probability=data.get("errorProbability"),
            truncate_mode=data.get("truncateMode"),
            truncate_at_fraction=data.get("truncateAtFraction"),
            malformed_sse=data.get("malformedSse"),
            seed=data.get("seed"),
            quota_name=data.get("quotaName"),
            quota_limit=data.get("quotaLimit"),
            quota_window_millis=data.get("quotaWindowMillis"),
            quota_error_status=data.get("quotaErrorStatus"),
            token_quota_limit=data.get("tokenQuotaLimit"),
            token_quota_window_millis=data.get("tokenQuotaWindowMillis"),
            content_filter_block_probability=data.get("contentFilterBlockProbability"),
            error_kind=data.get("errorKind"),
        )


# ---------------------------------------------------------------------------
# Completion (mirrors org.mockserver.model.Completion)
# ---------------------------------------------------------------------------


@dataclass
class Completion:
    """A mocked LLM chat/completion response, provider-agnostic.

    MockServer re-encodes this into the wire shape of the configured provider
    (OpenAI / Anthropic / Gemini / Bedrock / ...) when the request is served.
    """

    text: str | None = None
    tool_calls: list[ToolUse] | None = None
    stop_reason: str | None = None
    usage: Usage | None = None
    streaming: bool | None = None
    streaming_physics: StreamingPhysics | None = None
    output_schema: str | None = None
    model: str | None = None
    # Reject a re-encoded response that violates ``output_schema`` instead of returning it.
    enforce_output_schema: bool | None = None
    # Force tool-calling behaviour (e.g. "auto" / "none" / "required" / a tool name).
    tool_choice: str | None = None
    # Extended-thinking / reasoning trace text and its provider signature.
    reasoning_text: str | None = None
    reasoning_signature: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "text": self.text,
            "toolCalls": [t.to_dict() for t in self.tool_calls] if self.tool_calls else None,
            "stopReason": self.stop_reason,
            "usage": self.usage.to_dict() if self.usage else None,
            "streaming": self.streaming,
            "streamingPhysics": self.streaming_physics.to_dict() if self.streaming_physics else None,
            "outputSchema": self.output_schema,
            "enforceOutputSchema": self.enforce_output_schema,
            "toolChoice": self.tool_choice,
            "reasoningText": self.reasoning_text,
            "reasoningSignature": self.reasoning_signature,
            "model": self.model,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> Completion | None:
        if data is None:
            return None
        tool_calls_data = data.get("toolCalls")
        return cls(
            text=data.get("text"),
            tool_calls=[ToolUse.from_dict(t) for t in tool_calls_data] if tool_calls_data else None,
            stop_reason=data.get("stopReason"),
            usage=Usage.from_dict(data.get("usage")),
            streaming=data.get("streaming"),
            streaming_physics=StreamingPhysics.from_dict(data.get("streamingPhysics")),
            output_schema=data.get("outputSchema"),
            enforce_output_schema=data.get("enforceOutputSchema"),
            tool_choice=data.get("toolChoice"),
            reasoning_text=data.get("reasoningText"),
            reasoning_signature=data.get("reasoningSignature"),
            model=data.get("model"),
        )

    # Fluent setters (mirror the Java withX methods) -----------------------

    def with_text(self, text: str) -> Completion:
        self.text = text
        return self

    def with_tool_call(self, tool_call: ToolUse) -> Completion:
        if self.tool_calls is None:
            self.tool_calls = []
        self.tool_calls.append(tool_call)
        return self

    def with_tool_calls(self, *tool_calls: ToolUse) -> Completion:
        self.tool_calls = list(tool_calls)
        return self

    def with_stop_reason(self, stop_reason: str) -> Completion:
        self.stop_reason = stop_reason
        return self

    def with_usage(self, usage: Usage) -> Completion:
        self.usage = usage
        return self

    def with_streaming(self, streaming: bool = True) -> Completion:
        self.streaming = streaming
        return self

    def streaming_on(self) -> Completion:
        """Mirror of Java ``completion().streaming()`` — enables streaming."""
        return self.with_streaming(True)

    def with_streaming_physics(self, physics: StreamingPhysics) -> Completion:
        self.streaming_physics = physics
        # Mirrors Java Completion.withStreamingPhysics: does NOT touch `streaming`.
        # Callers enable streaming explicitly via with_streaming()/streaming_on().
        return self

    def with_output_schema(self, output_schema: str) -> Completion:
        self.output_schema = output_schema
        return self

    def with_model(self, model: str) -> Completion:
        self.model = model
        return self


def completion(
    text: str | None = None,
    stop_reason: str | None = None,
    usage: Usage | None = None,
    tool_calls: list[ToolUse] | None = None,
    streaming: bool | None = None,
    streaming_physics: StreamingPhysics | None = None,
    output_schema: str | None = None,
    model: str | None = None,
) -> Completion:
    """Factory mirroring ``Completion.completion()`` with keyword shortcuts."""
    return Completion(
        text=text,
        tool_calls=tool_calls,
        stop_reason=stop_reason,
        usage=usage,
        streaming=streaming,
        streaming_physics=streaming_physics,
        output_schema=output_schema,
        model=model,
    )


# ---------------------------------------------------------------------------
# ConversationPredicates (mirrors org.mockserver.model.ConversationPredicates)
# ---------------------------------------------------------------------------


@dataclass
class ConversationPredicates:
    """Serialisable predicate descriptors for LLM conversation matching."""

    turn_index: int | None = None
    latest_message_contains: str | None = None
    latest_message_matches: str | None = None
    latest_message_role: str | None = None
    contains_tool_result_for: str | None = None
    semantic_match_against: str | None = None
    normalization: NormalizationOptions | None = None

    def has_any_predicate(self) -> bool:
        """True if at least one predicate (not normalization) is set."""
        return any(
            v is not None
            for v in (
                self.turn_index,
                self.latest_message_contains,
                self.latest_message_matches,
                self.latest_message_role,
                self.contains_tool_result_for,
                self.semantic_match_against,
            )
        )

    def to_dict(self) -> dict:
        return _strip_none({
            "turnIndex": self.turn_index,
            "latestMessageContains": self.latest_message_contains,
            "latestMessageMatches": self.latest_message_matches,
            "latestMessageRole": self.latest_message_role,
            "containsToolResultFor": self.contains_tool_result_for,
            "semanticMatchAgainst": self.semantic_match_against,
            "normalization": self.normalization.to_dict() if self.normalization else None,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> ConversationPredicates | None:
        if data is None:
            return None
        return cls(
            turn_index=data.get("turnIndex"),
            latest_message_contains=data.get("latestMessageContains"),
            latest_message_matches=data.get("latestMessageMatches"),
            latest_message_role=data.get("latestMessageRole"),
            contains_tool_result_for=data.get("containsToolResultFor"),
            semantic_match_against=data.get("semanticMatchAgainst"),
            normalization=NormalizationOptions.from_dict(data.get("normalization")),
        )


# ---------------------------------------------------------------------------
# ModerationResponse (mirrors org.mockserver.model.ModerationResponse)
# ---------------------------------------------------------------------------


@dataclass
class ModerationResponse:
    """A mocked moderation/classification response (flagged categories)."""

    flagged_categories: list[str] | None = None
    model: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "flaggedCategories": self.flagged_categories,
            "model": self.model,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> ModerationResponse | None:
        if data is None:
            return None
        return cls(
            flagged_categories=data.get("flaggedCategories"),
            model=data.get("model"),
        )


def moderation(
    flagged_categories: list[str] | None = None,
    model: str | None = None,
) -> ModerationResponse:
    """Factory mirroring ``ModerationResponse.moderation()``."""
    return ModerationResponse(flagged_categories=flagged_categories, model=model)


# ---------------------------------------------------------------------------
# RerankResponse (mirrors org.mockserver.model.RerankResponse)
# ---------------------------------------------------------------------------


@dataclass
class RerankResponse:
    """A mocked rerank response (top-N document reordering, determinism)."""

    top_n: int | None = None
    deterministic_from_input: bool | None = None
    seed: int | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "topN": self.top_n,
            "deterministicFromInput": self.deterministic_from_input,
            "seed": self.seed,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> RerankResponse | None:
        if data is None:
            return None
        return cls(
            top_n=data.get("topN"),
            deterministic_from_input=data.get("deterministicFromInput"),
            seed=data.get("seed"),
        )


def rerank(
    top_n: int | None = None,
    deterministic_from_input: bool | None = None,
    seed: int | None = None,
) -> RerankResponse:
    """Factory mirroring ``RerankResponse.rerank()``."""
    return RerankResponse(
        top_n=top_n,
        deterministic_from_input=deterministic_from_input,
        seed=seed,
    )


# ---------------------------------------------------------------------------
# ContentFilter (mirrors org.mockserver.model.ContentFilter)
# ---------------------------------------------------------------------------


@dataclass
class ContentFilter:
    """Per-category content-filter severity annotations attached to a response."""

    hate: str | None = None
    sexual: str | None = None
    violence: str | None = None
    self_harm: str | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "hate": self.hate,
            "sexual": self.sexual,
            "violence": self.violence,
            "selfHarm": self.self_harm,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> ContentFilter | None:
        if data is None:
            return None
        return cls(
            hate=data.get("hate"),
            sexual=data.get("sexual"),
            violence=data.get("violence"),
            self_harm=data.get("selfHarm"),
        )


def content_filter(
    hate: str | None = None,
    sexual: str | None = None,
    violence: str | None = None,
    self_harm: str | None = None,
) -> ContentFilter:
    """Factory mirroring ``ContentFilter.contentFilter()``."""
    return ContentFilter(hate=hate, sexual=sexual, violence=violence, self_harm=self_harm)


# ---------------------------------------------------------------------------
# HttpLlmResponse (mirrors org.mockserver.model.HttpLlmResponse)
# ---------------------------------------------------------------------------


@dataclass
class HttpLlmResponse:
    """The ``httpLlmResponse`` action payload of an LLM expectation."""

    provider: str | None = None
    model: str | None = None
    completion: Completion | None = None
    embedding: EmbeddingResponse | None = None
    rerank: RerankResponse | None = None
    moderation: ModerationResponse | None = None
    content_filter: ContentFilter | None = None
    conversation_predicates: ConversationPredicates | None = None
    chaos: LlmChaosProfile | None = None
    delay: Delay | None = None
    primary: bool | None = None

    def to_dict(self) -> dict:
        return _strip_none({
            "provider": self.provider,
            "model": self.model,
            "completion": self.completion.to_dict() if self.completion else None,
            "embedding": self.embedding.to_dict() if self.embedding else None,
            "rerank": self.rerank.to_dict() if self.rerank else None,
            "moderation": self.moderation.to_dict() if self.moderation else None,
            "contentFilter": self.content_filter.to_dict() if self.content_filter else None,
            "conversationPredicates": self.conversation_predicates.to_dict() if self.conversation_predicates else None,
            "chaos": self.chaos.to_dict() if self.chaos else None,
            "delay": self.delay.to_dict() if self.delay else None,
            "primary": self.primary,
        })

    @classmethod
    def from_dict(cls, data: dict | None) -> HttpLlmResponse | None:
        if data is None:
            return None
        return cls(
            provider=data.get("provider"),
            model=data.get("model"),
            completion=Completion.from_dict(data.get("completion")),
            embedding=EmbeddingResponse.from_dict(data.get("embedding")),
            rerank=RerankResponse.from_dict(data.get("rerank")),
            moderation=ModerationResponse.from_dict(data.get("moderation")),
            content_filter=ContentFilter.from_dict(data.get("contentFilter")),
            conversation_predicates=ConversationPredicates.from_dict(data.get("conversationPredicates")),
            chaos=LlmChaosProfile.from_dict(data.get("chaos")),
            delay=Delay.from_dict(data.get("delay")),
            primary=data.get("primary"),
        )

    def with_provider(self, provider: str) -> HttpLlmResponse:
        self.provider = provider
        return self

    def with_model(self, model: str) -> HttpLlmResponse:
        self.model = model
        return self

    def with_completion(self, completion: Completion) -> HttpLlmResponse:
        self.completion = completion
        self.embedding = None
        return self

    def with_embedding(self, embedding: EmbeddingResponse) -> HttpLlmResponse:
        self.embedding = embedding
        self.completion = None
        return self


def llm_response() -> HttpLlmResponse:
    """Factory mirroring ``HttpLlmResponse.llmResponse()``."""
    return HttpLlmResponse()


# ---------------------------------------------------------------------------
# IsolationSource (mirrors org.mockserver.llm.IsolationSource)
# ---------------------------------------------------------------------------

_ISOLATION_MARKER = "__iso="


@dataclass
class IsolationSource:
    """Where to read the per-session isolation key from an inbound request."""

    kind: str = ""  # "header" | "query_parameter" | "cookie"
    name: str = ""

    def encode(self) -> str:
        return f"{self.kind}:{self.name}"


def header(name: str) -> IsolationSource:
    """Factory mirroring ``IsolationSource.header(name)``."""
    return IsolationSource(kind="header", name=name)


def query_parameter(name: str) -> IsolationSource:
    """Factory mirroring ``IsolationSource.queryParameter(name)``."""
    return IsolationSource(kind="query_parameter", name=name)


def cookie(name: str) -> IsolationSource:
    """Factory mirroring ``IsolationSource.cookie(name)``."""
    return IsolationSource(kind="cookie", name=name)


# ---------------------------------------------------------------------------
# LlmMockBuilder (mirrors org.mockserver.client.LlmMockBuilder)
# ---------------------------------------------------------------------------


class LlmMockBuilder:
    """Fluent builder for a single LLM mock expectation."""

    def __init__(self, path: str) -> None:
        self._path = path
        self._provider: str | None = None
        self._model: str | None = None
        self._completion: Completion | None = None
        self._embedding: EmbeddingResponse | None = None

    def with_provider(self, provider: str) -> LlmMockBuilder:
        self._provider = provider
        return self

    def with_model(self, model: str) -> LlmMockBuilder:
        self._model = model
        return self

    def responding_with(self, response: Completion | EmbeddingResponse) -> LlmMockBuilder:
        if isinstance(response, Completion):
            self._completion = response
            self._embedding = None
        elif isinstance(response, EmbeddingResponse):
            self._embedding = response
            self._completion = None
        else:
            raise TypeError(
                f"Expected Completion or EmbeddingResponse, got {type(response).__name__}"
            )
        return self

    def build(self) -> Expectation:
        action = HttpLlmResponse(provider=self._provider, model=self._model)
        if self._completion is not None:
            action.completion = self._completion
        if self._embedding is not None:
            action.embedding = self._embedding
        return Expectation(
            http_request=HttpRequest(method="POST", path=self._path),
            http_llm_response=action,
        )

    def apply_to(self, client) -> list:
        """Build the expectation and register it via ``client.upsert``."""
        return client.upsert(self.build())


def llm_mock(path: str) -> LlmMockBuilder:
    """Entry point mirroring ``LlmMockBuilder.llmMock(path)``."""
    return LlmMockBuilder(path)


# ---------------------------------------------------------------------------
# TurnBuilder + LlmConversationBuilder
# (mirror org.mockserver.client.TurnBuilder / LlmConversationBuilder)
# ---------------------------------------------------------------------------

_SCENARIO_PREFIX = "__llm_conv_"
_DONE_STATE = "__done"


class TurnBuilder:
    """Sub-builder configuring one turn of a conversation mock."""

    def __init__(self, parent: LlmConversationBuilder) -> None:
        self._parent = parent
        self.turn_index: int | None = None
        self.latest_message_contains: str | None = None
        self.latest_message_matches: str | None = None
        self.latest_message_role: str | None = None
        self.contains_tool_result_for: str | None = None
        self.semantic_match_against: str | None = None
        self.normalization: NormalizationOptions | None = None
        self.chaos: LlmChaosProfile | None = None
        self.completion: Completion | None = None

    def when_turn_index(self, n: int) -> TurnBuilder:
        self.turn_index = n
        return self

    def when_latest_message_contains(self, text: str) -> TurnBuilder:
        self.latest_message_contains = text
        return self

    def when_latest_message_matches(self, regex: str) -> TurnBuilder:
        if regex is None:
            raise ValueError("regex must not be None")
        self.latest_message_matches = regex
        return self

    def when_latest_message_role(self, role: str) -> TurnBuilder:
        self.latest_message_role = role
        return self

    def when_contains_tool_result_for(self, tool_name: str) -> TurnBuilder:
        self.contains_tool_result_for = tool_name
        return self

    def when_semantic_match(self, expected_meaning: str) -> TurnBuilder:
        self.semantic_match_against = expected_meaning
        return self

    def with_normalization(self, normalization: NormalizationOptions) -> TurnBuilder:
        self.normalization = normalization
        return self

    def with_chaos(self, chaos: LlmChaosProfile) -> TurnBuilder:
        self.chaos = chaos
        return self

    def responding_with(self, completion: Completion) -> TurnBuilder:
        self.completion = completion
        return self

    def and_then(self) -> LlmConversationBuilder:
        return self._parent

    def build(self) -> list[Expectation]:
        return self._parent.build()

    def apply_to(self, client) -> list:
        return self._parent.apply_to(client)


class LlmConversationBuilder:
    """Builder for multi-turn LLM conversation mocks with scenario advancement."""

    def __init__(self) -> None:
        self._path: str | None = None
        self._provider: str | None = None
        self._model: str | None = None
        self._isolation_source: IsolationSource | None = None
        self._turns: list[TurnBuilder] = []

    def with_path(self, path: str) -> LlmConversationBuilder:
        self._path = path
        return self

    def with_provider(self, provider: str) -> LlmConversationBuilder:
        self._provider = provider
        return self

    def with_model(self, model: str) -> LlmConversationBuilder:
        self._model = model
        return self

    def isolate_by(self, source: IsolationSource) -> LlmConversationBuilder:
        self._isolation_source = source
        return self

    def turn(self) -> TurnBuilder:
        turn_builder = TurnBuilder(self)
        self._turns.append(turn_builder)
        return turn_builder

    def build(self) -> list[Expectation]:
        if not self._turns:
            raise ValueError("At least one turn must be defined")
        if not self._path:
            raise ValueError("Path must be set")
        if self._provider is None:
            raise ValueError("Provider must be set")

        conversation_id = _SCENARIO_PREFIX + str(uuid.uuid4())
        scenario_name = conversation_id
        if self._isolation_source is not None:
            scenario_name = conversation_id + _ISOLATION_MARKER + self._isolation_source.encode()

        expectations: list[Expectation] = []
        n = len(self._turns)
        for i, tb in enumerate(self._turns):
            turn_state = f"turn_{i}"
            next_state = f"turn_{i + 1}" if i < n - 1 else _DONE_STATE

            predicates = ConversationPredicates(
                turn_index=tb.turn_index,
                latest_message_contains=tb.latest_message_contains,
                latest_message_matches=tb.latest_message_matches,
                latest_message_role=tb.latest_message_role,
                contains_tool_result_for=tb.contains_tool_result_for,
                semantic_match_against=tb.semantic_match_against,
                normalization=tb.normalization,
            )

            action = HttpLlmResponse(provider=self._provider, model=self._model)
            if tb.completion is not None:
                action.completion = tb.completion
            if tb.chaos is not None:
                action.chaos = tb.chaos
            if predicates.has_any_predicate():
                action.conversation_predicates = predicates

            expectations.append(
                Expectation(
                    http_request=HttpRequest(method="POST", path=self._path),
                    http_llm_response=action,
                    scenario_name=scenario_name,
                    scenario_state="Started" if i == 0 else turn_state,
                    new_scenario_state=next_state,
                )
            )
        return expectations

    def apply_to(self, client) -> list:
        return client.upsert(*self.build())


def conversation() -> LlmConversationBuilder:
    """Entry point mirroring ``LlmConversationBuilder.conversation()``."""
    return LlmConversationBuilder()


# ---------------------------------------------------------------------------
# LlmFailoverBuilder (mirrors org.mockserver.client.LlmFailoverBuilder)
# ---------------------------------------------------------------------------


def _default_error_body(status_code: int) -> str:
    mapping = {
        429: ("rate_limit_error", "Rate limit exceeded. Please retry after a brief wait."),
        500: ("internal_server_error", "An internal error occurred. Please retry your request."),
        502: ("bad_gateway", "Bad gateway. The upstream server returned an invalid response."),
        503: ("service_unavailable", "The service is temporarily overloaded. Please retry later."),
    }
    type_, message = mapping.get(status_code, ("error", f"Request failed with status {status_code}"))
    return '{"error":{"type":"' + type_ + '","message":"' + message + '"}}'


@dataclass
class _FailureSpec:
    status_code: int
    error_body: str | None


class LlmFailoverBuilder:
    """Builder for provider failover/retry scenarios.

    Produces an ordered list of expectations: failure expectations (limited
    ``times``) first — consumed before — a single success expectation with
    unlimited ``times``. Consecutive identical failures are coalesced into one
    expectation with ``times.exactly(count)``, matching the Java builder.
    """

    def __init__(self) -> None:
        self._path: str | None = None
        self._provider: str | None = None
        self._model: str | None = None
        self._failures: list[_FailureSpec] = []
        self._success_completion: Completion | None = None

    def with_path(self, path: str) -> LlmFailoverBuilder:
        self._path = path
        return self

    def with_provider(self, provider: str) -> LlmFailoverBuilder:
        self._provider = provider
        return self

    def with_model(self, model: str) -> LlmFailoverBuilder:
        self._model = model
        return self

    def fail_with(
        self,
        status_code: int,
        error_body: str | None = None,
        count: int = 1,
    ) -> LlmFailoverBuilder:
        """Add one (or ``count``) failure attempt(s) with the given status.

        Mirrors the three Java overloads: ``failWith(status)``,
        ``failWith(status, body)`` and ``failWith(status, count)``. A non-int
        ``error_body`` adds a custom body; ``count`` adds repeated failures.
        """
        if not (100 <= status_code <= 599):
            raise ValueError(f"status_code must be between 100 and 599, got {status_code}")
        if count < 1:
            raise ValueError(f"count must be >= 1, got {count}")
        for _ in range(count):
            self._failures.append(_FailureSpec(status_code, error_body))
        return self

    def then_respond_with(self, completion: Completion) -> LlmFailoverBuilder:
        self._success_completion = completion
        return self

    def get_failure_count(self) -> int:
        return len(self._failures)

    def _coalesce(self) -> list[tuple[int, str | None, int]]:
        result: list[list] = []
        for spec in self._failures:
            if result and result[-1][0] == spec.status_code and result[-1][1] == spec.error_body:
                result[-1][2] += 1
            else:
                result.append([spec.status_code, spec.error_body, 1])
        return [(s, b, c) for s, b, c in result]

    def build(self) -> list[Expectation]:
        if not self._path:
            raise ValueError("Path must be set")
        if self._provider is None:
            raise ValueError("Provider must be set")
        if not self._failures:
            raise ValueError("At least one failure must be defined")
        if self._success_completion is None:
            raise ValueError("Success completion must be set via then_respond_with()")

        from mockserver.models import HttpResponse, KeyToMultiValue, TimeToLive, Times

        expectations: list[Expectation] = []
        for status_code, error_body, count in self._coalesce():
            body = error_body if error_body is not None else _default_error_body(status_code)
            error_response = HttpResponse(
                status_code=status_code,
                headers=[KeyToMultiValue(name="Content-Type", values=["application/json"])],
                body=body,
            )
            expectations.append(
                Expectation(
                    http_request=HttpRequest(method="POST", path=self._path),
                    http_response=error_response,
                    times=Times.exactly(count),
                    time_to_live=TimeToLive.unlimited(),
                )
            )

        success_action = HttpLlmResponse(
            provider=self._provider,
            model=self._model,
            completion=self._success_completion,
        )
        expectations.append(
            Expectation(
                http_request=HttpRequest(method="POST", path=self._path),
                http_llm_response=success_action,
                times=Times.unlimited(),
                time_to_live=TimeToLive.unlimited(),
            )
        )
        return expectations

    def apply_to(self, client) -> list:
        return client.upsert(*self.build())


def llm_failover() -> LlmFailoverBuilder:
    """Entry point mirroring ``LlmFailoverBuilder.llmFailover()``."""
    return LlmFailoverBuilder()
