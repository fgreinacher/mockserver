<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a WebSocket response action.
 *
 * Produces the {@code httpWebSocketResponse} action JSON. Wire keys:
 * subprotocol, messages, closeConnection, delay, primary,
 * graphqlSubscriptionFilter.
 *
 * @example
 *   HttpWebSocketResponse::response()
 *       ->subprotocol('chat')
 *       ->message(WebSocketMessage::text('hello'))
 *       ->message(WebSocketMessage::binary("\x00\x01"));
 */
class HttpWebSocketResponse implements \JsonSerializable
{
    private ?string $subprotocol = null;
    /** @var list<WebSocketMessage> */
    private array $messages = [];
    private ?bool $closeConnection = null;
    private ?Delay $delay = null;
    private ?bool $primary = null;
    private ?GraphQLSubscriptionFilter $graphqlSubscriptionFilter = null;

    public static function response(): self
    {
        return new self();
    }

    public function subprotocol(string $subprotocol): self
    {
        $this->subprotocol = $subprotocol;
        return $this;
    }

    /**
     * Append a single WebSocket message.
     */
    public function message(WebSocketMessage $message): self
    {
        $this->messages[] = $message;
        return $this;
    }

    /**
     * Replace the full message list.
     *
     * @param list<WebSocketMessage> $messages
     */
    public function messages(array $messages): self
    {
        $this->messages = array_values($messages);
        return $this;
    }

    public function closeConnection(bool $closeConnection): self
    {
        $this->closeConnection = $closeConnection;
        return $this;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function primary(bool $primary): self
    {
        $this->primary = $primary;
        return $this;
    }

    /**
     * Only answer subscribe messages whose GraphQL query matches this filter.
     *
     * Used with the {@code graphql-transport-ws} subprotocol.
     */
    public function graphqlSubscriptionFilter(GraphQLSubscriptionFilter $filter): self
    {
        $this->graphqlSubscriptionFilter = $filter;
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
        if ($this->subprotocol !== null) {
            $data['subprotocol'] = $this->subprotocol;
        }
        if (!empty($this->messages)) {
            $data['messages'] = array_map(fn(WebSocketMessage $m) => $m->toArray(), $this->messages);
        }
        if ($this->closeConnection !== null) {
            $data['closeConnection'] = $this->closeConnection;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        if ($this->graphqlSubscriptionFilter !== null) {
            $data['graphqlSubscriptionFilter'] = $this->graphqlSubscriptionFilter->toArray();
        }
        return $data;
    }
}
