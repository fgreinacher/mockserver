"""Tests for the LLM mocking builder API (mockserver.llm).

These assert the produced expectation JSON matches the on-the-wire shape the
Java client emits — sourced from the server-side serializers in
``mockserver-core`` (``HttpLlmResponseDTOSerializer``, ``ExpectationDTO``) and
the model classes, cross-checked against ``ExpectationLlmRoundTripTest``.
"""
from __future__ import annotations

import pytest

from mockserver.llm import (
    Completion,
    ContentFilter,
    ConversationPredicates,
    EmbeddingResponse,
    HttpLlmResponse,
    LlmChaosProfile,
    ModerationResponse,
    NormalizationOptions,
    Provider,
    RerankResponse,
    Role,
    StreamingPhysics,
    ToolUse,
    Usage,
    completion,
    content_filter,
    conversation,
    cookie,
    embedding,
    header,
    llm_failover,
    llm_mock,
    llm_response,
    moderation,
    query_parameter,
    rerank,
    streaming_physics,
    time_to_first_token,
    tool_use,
    usage,
)
from mockserver.models import Delay, Expectation, Times, TimeToLive


# ---------------------------------------------------------------------------
# Basic completion mock
# ---------------------------------------------------------------------------


class TestBasicCompletion:
    def test_basic_completion_wire_shape(self):
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude-sonnet-4-20250514")
            .responding_with(
                completion()
                .with_text("Hello, world!")
                .with_stop_reason("end_turn")
                .with_usage(usage(input_tokens=10, output_tokens=25))
            )
            .build()
        )
        assert e.to_dict() == {
            "httpRequest": {"method": "POST", "path": "/v1/messages"},
            "httpLlmResponse": {
                "provider": "ANTHROPIC",
                "model": "claude-sonnet-4-20250514",
                "completion": {
                    "text": "Hello, world!",
                    "stopReason": "end_turn",
                    "usage": {"inputTokens": 10, "outputTokens": 25},
                },
            },
        }

    def test_completion_with_output_schema(self):
        schema = '{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}'
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude-sonnet-4-20250514")
            .responding_with(completion(text='{"name":"Ada"}').with_output_schema(schema))
            .build()
        )
        comp = e.to_dict()["httpLlmResponse"]["completion"]
        assert comp["text"] == '{"name":"Ada"}'
        assert comp["outputSchema"] == schema

    def test_request_is_post(self):
        e = llm_mock("/v1/messages").with_provider(Provider.OPENAI).responding_with(completion(text="hi")).build()
        assert e.to_dict()["httpRequest"] == {"method": "POST", "path": "/v1/messages"}

    def test_usage_rejects_negative(self):
        with pytest.raises(ValueError):
            usage(input_tokens=-1)


# ---------------------------------------------------------------------------
# Tool-use response
# ---------------------------------------------------------------------------


class TestToolUse:
    def test_tool_use_wire_shape(self):
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude-sonnet-4-20250514")
            .responding_with(
                completion()
                .with_tool_call(tool_use("get_weather", arguments='{"city":"London"}'))
                .with_stop_reason("tool_use")
            )
            .build()
        )
        assert e.to_dict()["httpLlmResponse"] == {
            "provider": "ANTHROPIC",
            "model": "claude-sonnet-4-20250514",
            "completion": {
                "toolCalls": [{"name": "get_weather", "arguments": '{"city":"London"}'}],
                "stopReason": "tool_use",
            },
        }

    def test_tool_use_with_id(self):
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .responding_with(
                completion().with_tool_call(
                    tool_use("search").with_id("toolu_01A").with_arguments('{"q":"x"}')
                )
            )
            .build()
        )
        tc = e.to_dict()["httpLlmResponse"]["completion"]["toolCalls"][0]
        assert tc == {"id": "toolu_01A", "name": "search", "arguments": '{"q":"x"}'}

    def test_multiple_tool_calls(self):
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.OPENAI)
            .responding_with(
                completion().with_tool_calls(
                    tool_use("a", arguments="{}"), tool_use("b", arguments="{}")
                )
            )
            .build()
        )
        tcs = e.to_dict()["httpLlmResponse"]["completion"]["toolCalls"]
        assert [t["name"] for t in tcs] == ["a", "b"]


# ---------------------------------------------------------------------------
# Streaming response
# ---------------------------------------------------------------------------


class TestStreaming:
    def test_streaming_physics_wire_shape(self):
        e = (
            llm_mock("/v1/chat/completions")
            .with_provider(Provider.OPENAI)
            .with_model("gpt-4o")
            .responding_with(
                completion(text="streamed text").with_streaming_physics(
                    streaming_physics(
                        tokens_per_second=50,
                        jitter=0.2,
                        time_to_first_token=time_to_first_token(300),
                    )
                )
            )
            .build()
        )
        # Mirrors Java: with_streaming_physics() does NOT auto-enable `streaming`.
        # A streaming-physics-only completion must NOT emit `streaming`.
        assert e.to_dict()["httpLlmResponse"]["completion"] == {
            "text": "streamed text",
            "streamingPhysics": {
                "timeToFirstToken": {"timeUnit": "MILLISECONDS", "value": 300},
                "tokensPerSecond": 50,
                "jitter": 0.2,
            },
        }

    def test_streaming_physics_with_explicit_streaming(self):
        e = (
            llm_mock("/v1/chat/completions")
            .with_provider(Provider.OPENAI)
            .with_model("gpt-4o")
            .responding_with(
                completion(text="streamed text")
                .streaming_on()
                .with_streaming_physics(
                    streaming_physics(
                        tokens_per_second=50,
                        jitter=0.2,
                        time_to_first_token=time_to_first_token(300),
                    )
                )
            )
            .build()
        )
        assert e.to_dict()["httpLlmResponse"]["completion"] == {
            "text": "streamed text",
            "streaming": True,
            "streamingPhysics": {
                "timeToFirstToken": {"timeUnit": "MILLISECONDS", "value": 300},
                "tokensPerSecond": 50,
                "jitter": 0.2,
            },
        }

    def test_streaming_flag_only(self):
        e = (
            llm_mock("/v1/chat/completions")
            .with_provider(Provider.OPENAI)
            .responding_with(completion(text="hi").streaming_on())
            .build()
        )
        assert e.to_dict()["httpLlmResponse"]["completion"]["streaming"] is True

    def test_time_to_first_token_custom_unit(self):
        d = time_to_first_token(2, time_unit="SECONDS")
        assert d.to_dict() == {"timeUnit": "SECONDS", "value": 2}

    def test_streaming_physics_validation(self):
        with pytest.raises(ValueError):
            streaming_physics(tokens_per_second=0)
        with pytest.raises(ValueError):
            streaming_physics(jitter=2.0)


# ---------------------------------------------------------------------------
# Embeddings
# ---------------------------------------------------------------------------


class TestEmbedding:
    def test_embedding_wire_shape(self):
        e = (
            llm_mock("/v1/embeddings")
            .with_provider(Provider.OPENAI)
            .with_model("text-embedding-3-small")
            .responding_with(embedding(dimensions=1536, deterministic_from_input=True))
            .build()
        )
        assert e.to_dict()["httpLlmResponse"] == {
            "provider": "OPENAI",
            "model": "text-embedding-3-small",
            "embedding": {"dimensions": 1536, "deterministicFromInput": True},
        }

    def test_responding_with_embedding_clears_completion(self):
        builder = llm_mock("/v1/embeddings").with_provider(Provider.OPENAI)
        builder.responding_with(completion(text="x"))
        builder.responding_with(embedding(dimensions=8))
        d = builder.build().to_dict()["httpLlmResponse"]
        assert "completion" not in d
        assert d["embedding"] == {"dimensions": 8}


# ---------------------------------------------------------------------------
# Multi-turn conversation
# ---------------------------------------------------------------------------


class TestConversation:
    def _build(self):
        return (
            conversation()
            .with_path("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude-sonnet-4")
            .turn()
            .when_turn_index(0)
            .responding_with(completion().with_tool_call(tool_use("search", arguments="{}")))
            .and_then()
            .turn()
            .when_contains_tool_result_for("search")
            .responding_with(completion().with_text("The answer is 42."))
            .build()
        )

    def test_conversation_produces_one_expectation_per_turn(self):
        exps = self._build()
        assert len(exps) == 2
        assert all(isinstance(e, Expectation) for e in exps)

    def test_conversation_scenario_advancement(self):
        exps = self._build()
        d0 = exps[0].to_dict()
        d1 = exps[1].to_dict()
        # shared, auto-generated scenario name with the reserved prefix
        name = d0["scenarioName"]
        assert name.startswith("__llm_conv_")
        assert d1["scenarioName"] == name
        # state machine: Started -> turn_1 -> __done
        assert d0["scenarioState"] == "Started"
        assert d0["newScenarioState"] == "turn_1"
        assert d1["scenarioState"] == "turn_1"
        assert d1["newScenarioState"] == "__done"

    def test_conversation_turn_predicates_and_responses(self):
        exps = self._build()
        a0 = exps[0].to_dict()["httpLlmResponse"]
        a1 = exps[1].to_dict()["httpLlmResponse"]
        assert a0["provider"] == "ANTHROPIC"
        assert a0["model"] == "claude-sonnet-4"
        assert a0["conversationPredicates"] == {"turnIndex": 0}
        assert a0["completion"]["toolCalls"][0]["name"] == "search"
        assert a1["conversationPredicates"] == {"containsToolResultFor": "search"}
        assert a1["completion"]["text"] == "The answer is 42."

    def test_conversation_each_request_is_post(self):
        for e in self._build():
            assert e.to_dict()["httpRequest"] == {"method": "POST", "path": "/v1/messages"}

    def test_conversation_isolation_marker_in_scenario_name(self):
        exps = (
            conversation()
            .with_path("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .isolate_by(header("x-session-id"))
            .turn()
            .when_turn_index(0)
            .responding_with(completion(text="hi"))
            .build()
        )
        name = exps[0].to_dict()["scenarioName"]
        assert name.startswith("__llm_conv_")
        assert name.endswith("__iso=header:x-session-id")

    def test_conversation_isolation_query_and_cookie_encoding(self):
        q = (
            conversation()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .isolate_by(query_parameter("agent"))
            .turn()
            .responding_with(completion(text="x"))
            .build()
        )
        assert q[0].to_dict()["scenarioName"].endswith("__iso=query_parameter:agent")
        c = (
            conversation()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .isolate_by(cookie("sid"))
            .turn()
            .responding_with(completion(text="x"))
            .build()
        )
        assert c[0].to_dict()["scenarioName"].endswith("__iso=cookie:sid")

    def test_conversation_all_predicate_kinds(self):
        exps = (
            conversation()
            .with_path("/p")
            .with_provider(Provider.ANTHROPIC)
            .turn()
            .when_latest_message_contains("hello")
            .when_latest_message_matches(r"\d+")
            .when_latest_message_role(Role.USER)
            .when_semantic_match("about the weather")
            .with_normalization(NormalizationOptions(collapse_whitespace=True, lowercase=True))
            .responding_with(completion(text="ok"))
            .build()
        )
        preds = exps[0].to_dict()["httpLlmResponse"]["conversationPredicates"]
        assert preds == {
            "latestMessageContains": "hello",
            "latestMessageMatches": r"\d+",
            "latestMessageRole": "USER",
            "semanticMatchAgainst": "about the weather",
            "normalization": {"collapseWhitespace": True, "lowercase": True},
        }

    def test_conversation_requires_turns_and_provider_and_path(self):
        with pytest.raises(ValueError):
            conversation().with_path("/p").with_provider(Provider.OPENAI).build()
        with pytest.raises(ValueError):
            conversation().with_provider(Provider.OPENAI).turn().responding_with(
                completion(text="x")
            ).and_then().build()
        with pytest.raises(ValueError):
            conversation().with_path("/p").turn().responding_with(
                completion(text="x")
            ).and_then().build()


# ---------------------------------------------------------------------------
# Failover
# ---------------------------------------------------------------------------


class TestFailover:
    def test_failover_orders_failures_then_success(self):
        exps = (
            llm_failover()
            .with_path("/v1/chat/completions")
            .with_provider(Provider.OPENAI)
            .with_model("gpt-4o")
            .fail_with(503)
            .fail_with(429)
            .then_respond_with(completion(text="finally ok"))
            .build()
        )
        assert len(exps) == 3
        d0, d1, d2 = (e.to_dict() for e in exps)

        # failure 1: 503, exactly once, unlimited TTL
        assert d0["httpResponse"]["statusCode"] == 503
        assert d0["times"] == {"remainingTimes": 1, "unlimited": False}
        assert d0["timeToLive"] == {"unlimited": True}
        assert "service_unavailable" in d0["httpResponse"]["body"]

        # failure 2: 429
        assert d1["httpResponse"]["statusCode"] == 429
        assert "rate_limit_error" in d1["httpResponse"]["body"]

        # success: llm response, unlimited times
        assert d2["httpLlmResponse"] == {
            "provider": "OPENAI",
            "model": "gpt-4o",
            "completion": {"text": "finally ok"},
        }
        assert d2["times"] == {"unlimited": True}

    def test_failover_coalesces_consecutive_identical_failures(self):
        exps = (
            llm_failover()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .fail_with(503, count=3)
            .then_respond_with(completion(text="ok"))
            .build()
        )
        # 3 identical failures coalesce into one + the success = 2 expectations
        assert len(exps) == 2
        assert exps[0].to_dict()["times"] == {"remainingTimes": 3, "unlimited": False}

    def test_failover_custom_error_body(self):
        exps = (
            llm_failover()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .fail_with(500, error_body='{"oops":true}')
            .then_respond_with(completion(text="ok"))
            .build()
        )
        assert exps[0].to_dict()["httpResponse"]["body"] == '{"oops":true}'

    def test_failover_failure_content_type_header(self):
        exps = (
            llm_failover()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .fail_with(503)
            .then_respond_with(completion(text="ok"))
            .build()
        )
        assert exps[0].to_dict()["httpResponse"]["headers"] == [
            {"name": "Content-Type", "values": ["application/json"]}
        ]

    def test_failover_validations(self):
        with pytest.raises(ValueError):
            llm_failover().with_path("/p").with_provider(Provider.OPENAI).fail_with(99)
        with pytest.raises(ValueError):
            # missing success completion
            llm_failover().with_path("/p").with_provider(Provider.OPENAI).fail_with(503).build()
        with pytest.raises(ValueError):
            # no failures
            llm_failover().with_path("/p").with_provider(Provider.OPENAI).then_respond_with(
                completion(text="ok")
            ).build()


# ---------------------------------------------------------------------------
# Chaos + round-trips
# ---------------------------------------------------------------------------


class TestChaosAndRoundTrip:
    def test_turn_chaos_profile_serialized(self):
        exps = (
            conversation()
            .with_path("/p")
            .with_provider(Provider.ANTHROPIC)
            .turn()
            .when_turn_index(0)
            .with_chaos(LlmChaosProfile(error_status=529, error_probability=0.5))
            .responding_with(completion(text="ok"))
            .build()
        )
        chaos = exps[0].to_dict()["httpLlmResponse"]["chaos"]
        assert chaos == {"errorStatus": 529, "errorProbability": 0.5}

    def test_http_llm_response_round_trip(self):
        action = (
            llm_response()
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude")
            .with_completion(
                completion(text="hi", stop_reason="end_turn", usage=usage(1, 2))
            )
        )
        round_tripped = HttpLlmResponse.from_dict(action.to_dict())
        assert round_tripped == action

    def test_expectation_round_trip_through_models(self):
        e = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .with_model("claude")
            .responding_with(completion(text="hi", stop_reason="end_turn"))
            .build()
        )
        round_tripped = Expectation.from_dict(e.to_dict())
        assert round_tripped.http_llm_response.provider == "ANTHROPIC"
        assert round_tripped.http_llm_response.completion.text == "hi"
        assert round_tripped.http_llm_response.completion.stop_reason == "end_turn"
        assert round_tripped.to_dict() == e.to_dict()


# ---------------------------------------------------------------------------
# apply_to wiring (uses a fake client that records upsert calls)
# ---------------------------------------------------------------------------


class _FakeClient:
    def __init__(self):
        self.upserted = None

    def upsert(self, *expectations):
        self.upserted = list(expectations)
        return self.upserted


class TestApplyTo:
    def test_llm_mock_apply_to_calls_upsert(self):
        client = _FakeClient()
        result = (
            llm_mock("/v1/messages")
            .with_provider(Provider.ANTHROPIC)
            .responding_with(completion(text="hi"))
            .apply_to(client)
        )
        assert len(client.upserted) == 1
        assert client.upserted[0].to_dict()["httpLlmResponse"]["provider"] == "ANTHROPIC"
        assert result == client.upserted

    def test_conversation_apply_to_upserts_all_turns(self):
        client = _FakeClient()
        (
            conversation()
            .with_path("/v1/messages")
            .with_provider(Provider.OPENAI)
            .turn()
            .when_turn_index(0)
            .responding_with(completion(text="a"))
            .and_then()
            .turn()
            .when_turn_index(1)
            .responding_with(completion(text="b"))
            .apply_to(client)
        )
        assert len(client.upserted) == 2

    def test_failover_apply_to_upserts_all(self):
        client = _FakeClient()
        (
            llm_failover()
            .with_path("/p")
            .with_provider(Provider.OPENAI)
            .fail_with(503)
            .then_respond_with(completion(text="ok"))
            .apply_to(client)
        )
        assert len(client.upserted) == 2


# ---------------------------------------------------------------------------
# Round-trip fidelity fills — LLM fields added to close the known-gaps manifest.
# ---------------------------------------------------------------------------


class TestUsageExtendedTokens:
    def test_cache_and_reasoning_tokens_round_trip(self):
        data = {
            "inputTokens": 10,
            "outputTokens": 25,
            "cachedInputTokens": 2,
            "cacheCreationTokens": 3,
            "reasoningTokens": 4,
        }
        assert Usage.from_dict(data).to_dict() == data

    def test_negative_cache_token_rejected(self):
        with pytest.raises(ValueError):
            Usage(cached_input_tokens=-1)


class TestStreamingPhysicsSubword:
    def test_subword_streaming_round_trip(self):
        data = {"tokensPerSecond": 50, "jitter": 0.2, "seed": 7, "subwordStreaming": True}
        assert StreamingPhysics.from_dict(data).to_dict() == data


class TestCompletionExtras:
    def test_completion_extra_fields_round_trip(self):
        data = {
            "text": "hi",
            "outputSchema": "{\"type\":\"object\"}",
            "enforceOutputSchema": True,
            "toolChoice": "auto",
            "reasoningText": "thinking",
            "reasoningSignature": "sig",
        }
        assert Completion.from_dict(data).to_dict() == data


class TestLlmChaosExtras:
    def test_content_filter_block_and_error_kind_round_trip(self):
        data = {
            "errorStatus": 429,
            "errorProbability": 0.1,
            "contentFilterBlockProbability": 0.2,
            "errorKind": "overloaded",
        }
        assert LlmChaosProfile.from_dict(data).to_dict() == data


class TestModerationRerankContentFilter:
    def test_moderation_round_trip(self):
        data = {"flaggedCategories": ["violence"], "model": "text-moderation-latest"}
        assert ModerationResponse.from_dict(data).to_dict() == data
        assert moderation(["violence"], "text-moderation-latest").to_dict() == data

    def test_rerank_round_trip(self):
        data = {"topN": 3, "deterministicFromInput": True, "seed": 42}
        assert RerankResponse.from_dict(data).to_dict() == data
        assert rerank(3, True, 42).to_dict() == data

    def test_content_filter_round_trip(self):
        data = {"hate": "low", "sexual": "safe", "violence": "medium", "selfHarm": "safe"}
        assert ContentFilter.from_dict(data).to_dict() == data
        assert content_filter("low", "safe", "medium", "safe").to_dict() == data

    def test_http_llm_response_wires_all_three(self):
        data = {
            "provider": "OPENAI",
            "model": "text-embedding-3-small",
            "embedding": {"dimensions": 1536, "deterministicFromInput": True, "seed": 42},
            "rerank": {"topN": 3, "deterministicFromInput": True, "seed": 42},
            "moderation": {"flaggedCategories": ["violence"], "model": "text-moderation-latest"},
            "contentFilter": {"hate": "low", "sexual": "safe", "violence": "medium", "selfHarm": "safe"},
        }
        assert HttpLlmResponse.from_dict(data).to_dict() == data
