<?php

declare(strict_types=1);

namespace MockServer\Llm;

use MockServer\Delay;

/**
 * The {@code httpLlmResponse} action payload of an LLM expectation
 * (mirrors org.mockserver.model.HttpLlmResponse).
 *
 * Sibling of {@code httpRequest}, {@code httpResponse}, {@code scenarioName},
 * {@code scenarioState} and {@code newScenarioState} on an expectation.
 */
class HttpLlmResponse implements \JsonSerializable
{
    private ?string $provider = null;
    private ?string $model = null;
    private ?Completion $completion = null;
    private ?EmbeddingResponse $embedding = null;
    private ?ConversationPredicates $conversationPredicates = null;
    /** @var array<string, mixed>|null */
    private ?array $chaos = null;
    private ?Delay $delay = null;
    private ?bool $primary = null;

    /**
     * Static factory mirroring {@code HttpLlmResponse.llmResponse()}.
     */
    public static function llmResponse(): self
    {
        return new self();
    }

    public function withProvider(string $provider): self
    {
        $this->provider = $provider;
        return $this;
    }

    public function withModel(string $model): self
    {
        $this->model = $model;
        return $this;
    }

    public function withCompletion(Completion $completion): self
    {
        $this->completion = $completion;
        $this->embedding = null;
        return $this;
    }

    public function withEmbedding(EmbeddingResponse $embedding): self
    {
        $this->embedding = $embedding;
        $this->completion = null;
        return $this;
    }

    public function withConversationPredicates(ConversationPredicates $conversationPredicates): self
    {
        $this->conversationPredicates = $conversationPredicates;
        return $this;
    }

    /**
     * @param array<string, mixed> $chaos
     */
    public function withChaos(array $chaos): self
    {
        $this->chaos = $chaos;
        return $this;
    }

    public function withDelay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    /**
     * Mark this action as the primary one for the expectation.
     *
     * Only meaningful when an expectation carries more than one action: the
     * server requires exactly one to be marked primary and rejects the
     * expectation otherwise.
     *
     * Named {@code primary()} rather than {@code withPrimary()}, breaking this
     * class's {@code with*} convention on purpose: every other action builder
     * in the client spells it {@code primary()}, and the selector is set the
     * same way across actions on a multi-action expectation.
     */
    public function primary(bool $primary): self
    {
        $this->primary = $primary;
        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [];
        if ($this->provider !== null) {
            $data['provider'] = $this->provider;
        }
        if ($this->model !== null) {
            $data['model'] = $this->model;
        }
        if ($this->completion !== null) {
            $data['completion'] = $this->completion->toArray();
        }
        if ($this->embedding !== null) {
            $data['embedding'] = $this->embedding->toArray();
        }
        if ($this->conversationPredicates !== null) {
            $data['conversationPredicates'] = $this->conversationPredicates->toArray();
        }
        if ($this->chaos !== null) {
            $data['chaos'] = $this->chaos;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        return $data;
    }
}
