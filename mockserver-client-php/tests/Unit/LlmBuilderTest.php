<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Llm\Completion;
use MockServer\Llm\HttpLlmResponse;
use MockServer\Llm\EmbeddingResponse;
use MockServer\Llm\IsolationSource;
use MockServer\Llm\LlmConversationBuilder;
use MockServer\Llm\LlmFailoverBuilder;
use MockServer\Llm\LlmMockBuilder;
use MockServer\Llm\Provider;
use MockServer\Llm\Role;
use MockServer\Llm\StreamingPhysics;
use MockServer\Llm\ToolUse;
use MockServer\Llm\Usage;
use PHPUnit\Framework\TestCase;

/**
 * Pure builder tests asserting the wire JSON shape produced by the LLM
 * builders, independent of any HTTP transport.
 */
class LlmBuilderTest extends TestCase
{
    public function testLlmMockCompletionWireShape(): void
    {
        $expectation = LlmMockBuilder::llmMock('/v1/chat/completions')
            ->withProvider(Provider::OPENAI)
            ->withModel('gpt-4o')
            ->respondingWith(
                Completion::completion()
                    ->withText('Hello!')
                    ->withStopReason('stop')
                    ->withUsage(Usage::usage()->withInputTokens(5)->withOutputTokens(2))
            )
            ->build();

        $arr = $expectation->toArray();

        $this->assertSame(['method' => 'POST', 'path' => '/v1/chat/completions'], $arr['httpRequest']);
        $this->assertArrayHasKey('httpLlmResponse', $arr);
        $this->assertSame('OPENAI', $arr['httpLlmResponse']['provider']);
        $this->assertSame('gpt-4o', $arr['httpLlmResponse']['model']);
        $this->assertSame('Hello!', $arr['httpLlmResponse']['completion']['text']);
        $this->assertSame('stop', $arr['httpLlmResponse']['completion']['stopReason']);
        $this->assertSame(
            ['inputTokens' => 5, 'outputTokens' => 2],
            $arr['httpLlmResponse']['completion']['usage']
        );
        $this->assertArrayNotHasKey('embedding', $arr['httpLlmResponse']);
    }

    public function testLlmMockEmbeddingClearsCompletion(): void
    {
        $expectation = LlmMockBuilder::llmMock('/v1/embeddings')
            ->withProvider(Provider::OPENAI)
            ->respondingWith(Completion::completion()->withText('ignored'))
            ->respondingWith(EmbeddingResponse::embedding()->withDimensions(1536)->withDeterministicFromInput(true))
            ->build();

        $llm = $expectation->toArray()['httpLlmResponse'];
        $this->assertArrayNotHasKey('completion', $llm);
        $this->assertSame(1536, $llm['embedding']['dimensions']);
        $this->assertTrue($llm['embedding']['deterministicFromInput']);
    }

    public function testToolCallArgumentsAndStreamingPhysics(): void
    {
        $completion = Completion::completion()
            ->withToolCall(ToolUse::toolUse('get_weather')->withId('call_1')->withArguments(['city' => 'London']))
            ->streaming()
            ->withStreamingPhysics(
                StreamingPhysics::streamingPhysics()
                    ->withTokensPerSecond(50)
                    ->withJitter(0.2)
                    ->withTimeToFirstToken(StreamingPhysics::timeToFirstToken(120))
            );

        $arr = $completion->toArray();
        $this->assertSame('get_weather', $arr['toolCalls'][0]['name']);
        $this->assertSame('call_1', $arr['toolCalls'][0]['id']);
        $this->assertSame('{"city":"London"}', $arr['toolCalls'][0]['arguments']);
        $this->assertTrue($arr['streaming']);
        $this->assertSame(50, $arr['streamingPhysics']['tokensPerSecond']);
        $this->assertSame(0.2, $arr['streamingPhysics']['jitter']);
        $this->assertSame(
            ['timeUnit' => 'MILLISECONDS', 'value' => 120],
            $arr['streamingPhysics']['timeToFirstToken']
        );
    }

    public function testUsageRejectsNegative(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        Usage::usage()->withInputTokens(-1);
    }

    public function testStreamingPhysicsValidatesRanges(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        StreamingPhysics::streamingPhysics()->withTokensPerSecond(0);
    }

    public function testConversationScenarioAdvancement(): void
    {
        $expectations = LlmConversationBuilder::conversation()
            ->withPath('/v1/chat/completions')
            ->withProvider(Provider::ANTHROPIC)
            ->withModel('claude-3')
            ->turn()
                ->whenLatestMessageRole(Role::USER)
                ->respondingWith(Completion::completion()->withText('Hi'))
            ->turn()
                ->respondingWith(Completion::completion()->withText('Bye'))
            ->build();

        $this->assertCount(2, $expectations);

        $first = $expectations[0]->toArray();
        $second = $expectations[1]->toArray();

        $this->assertSame('Started', $first['scenarioState']);
        $this->assertSame('turn_1', $first['newScenarioState']);
        $this->assertSame('turn_1', $second['scenarioState']);
        $this->assertSame('__done', $second['newScenarioState']);

        // Same auto-generated conversation scenario name across turns.
        $this->assertSame($first['scenarioName'], $second['scenarioName']);
        $this->assertStringStartsWith('__llm_conv_', $first['scenarioName']);

        // First turn carries a predicate; second has none.
        $this->assertSame('USER', $first['httpLlmResponse']['conversationPredicates']['latestMessageRole']);
        $this->assertArrayNotHasKey('conversationPredicates', $second['httpLlmResponse']);
        $this->assertSame('ANTHROPIC', $first['httpLlmResponse']['provider']);
    }

    public function testConversationTurnChaosIsAttached(): void
    {
        $expectations = LlmConversationBuilder::conversation()
            ->withPath('/v1/chat/completions')
            ->withProvider(Provider::OPENAI)
            ->turn()
                ->withChaos(['errorStatus' => 503, 'errorProbability' => 1.0])
                ->respondingWith(Completion::completion()->withText('boom'))
            ->build();

        $llm = $expectations[0]->toArray()['httpLlmResponse'];
        $this->assertSame(['errorStatus' => 503, 'errorProbability' => 1.0], $llm['chaos']);
    }

    public function testConversationIsolationSuffix(): void
    {
        $expectations = LlmConversationBuilder::conversation()
            ->withPath('/v1/chat/completions')
            ->withProvider(Provider::OPENAI)
            ->isolateBy(IsolationSource::header('x-session-id'))
            ->turn()->respondingWith(Completion::completion()->withText('a'))
            ->build();

        $name = $expectations[0]->toArray()['scenarioName'];
        $this->assertStringContainsString('__iso=header:x-session-id', $name);
    }

    public function testConversationRequiresTurnAndProvider(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        LlmConversationBuilder::conversation()
            ->withPath('/p')
            ->withProvider(Provider::OPENAI)
            ->build();
    }

    public function testFailoverCoalescingAndDefaultBodies(): void
    {
        $expectations = LlmFailoverBuilder::llmFailover()
            ->withPath('/v1/chat/completions')
            ->withProvider(Provider::OPENAI)
            ->failWith(429)
            ->failWith(429)
            ->failWith(503, 2)
            ->thenRespondWith(Completion::completion()->withText('recovered'))
            ->build();

        // 429 (coalesced x2) + 503 (count 2) + 1 success = 3 expectations.
        $this->assertCount(3, $expectations);

        $first = $expectations[0]->toArray();
        $this->assertSame(429, $first['httpResponse']['statusCode']);
        $this->assertSame(2, $first['times']['remainingTimes']);
        $this->assertFalse($first['times']['unlimited']);
        $this->assertTrue($first['timeToLive']['unlimited']);
        $this->assertSame(
            [['name' => 'Content-Type', 'values' => ['application/json']]],
            $first['httpResponse']['headers']
        );
        $this->assertSame(
            '{"error":{"type":"rate_limit_error","message":"Rate limit exceeded. Please retry after a brief wait."}}',
            $first['httpResponse']['body']
        );

        $second = $expectations[1]->toArray();
        $this->assertSame(503, $second['httpResponse']['statusCode']);
        $this->assertSame(2, $second['times']['remainingTimes']);
        $this->assertSame(
            '{"error":{"type":"service_unavailable","message":"The service is temporarily overloaded. Please retry later."}}',
            $second['httpResponse']['body']
        );

        $success = $expectations[2]->toArray();
        $this->assertTrue($success['times']['unlimited']);
        $this->assertSame('recovered', $success['httpLlmResponse']['completion']['text']);
        $this->assertSame('OPENAI', $success['httpLlmResponse']['provider']);
    }

    public function testFailoverCustomErrorBody(): void
    {
        $expectations = LlmFailoverBuilder::llmFailover()
            ->withPath('/p')
            ->withProvider(Provider::OPENAI)
            ->failWith(418, '{"custom":true}')
            ->thenRespondWith(Completion::completion()->withText('ok'))
            ->build();

        $this->assertSame('{"custom":true}', $expectations[0]->toArray()['httpResponse']['body']);
    }

    public function testFailoverValidatesStatusCode(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        LlmFailoverBuilder::llmFailover()->failWith(99);
    }

    public function testDefaultErrorBodyUnknownStatus(): void
    {
        $this->assertSame(
            '{"error":{"type":"error","message":"Request failed with status 599"}}',
            LlmFailoverBuilder::defaultErrorBody(599)
        );
    }

    public function testHttpLlmResponsePrimaryIsSerialised(): void
    {
        $arr = HttpLlmResponse::llmResponse()->withProvider(Provider::ANTHROPIC)->primary(true)->toArray();

        $this->assertArrayHasKey('primary', $arr);
        $this->assertTrue($arr['primary']);
    }

    public function testHttpLlmResponsePrimaryFalseIsSerialisedNotOmitted(): void
    {
        $arr = HttpLlmResponse::llmResponse()->withProvider(Provider::ANTHROPIC)->primary(false)->toArray();

        $this->assertArrayHasKey('primary', $arr);
        $this->assertFalse($arr['primary']);
    }

    public function testHttpLlmResponsePrimaryOmittedWhenNotSet(): void
    {
        $this->assertArrayNotHasKey('primary', HttpLlmResponse::llmResponse()->withProvider(Provider::ANTHROPIC)->toArray());
    }
}
