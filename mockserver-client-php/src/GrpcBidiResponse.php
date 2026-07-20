<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a gRPC bidirectional-streaming response action.
 *
 * Produces the {@code grpcBidiResponse} action JSON. Unconditional
 * {@code messages} are pushed as soon as the stream opens; {@code rules} match
 * inbound client messages and push their own responses in reply.
 *
 * Wire keys: delay, headers, statusName, statusMessage, messages, rules,
 * closeConnection, primary.
 *
 * @example
 *   GrpcBidiResponse::response()
 *       ->statusName('OK')
 *       ->message(GrpcBidiMessage::message('{"greeting":"hi"}'))
 *       ->rule(GrpcBidiRule::matchJson('{"name":"world"}')
 *           ->response(GrpcBidiMessage::message('{"reply":"hello world"}')))
 *       ->closeConnection(true);
 */
class GrpcBidiResponse implements \JsonSerializable
{
    private ?Delay $delay = null;
    /** @var array<string, list<string>> */
    private array $headers = [];
    private ?string $statusName = null;
    private ?string $statusMessage = null;
    /** @var list<GrpcBidiMessage> */
    private array $messages = [];
    /** @var list<GrpcBidiRule> */
    private array $rules = [];
    private ?bool $closeConnection = null;
    private ?bool $primary = null;

    public static function response(): self
    {
        return new self();
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    /**
     * Add a response header / metadata entry (multi-value supported).
     */
    public function header(string $name, string ...$values): self
    {
        if (!isset($this->headers[$name])) {
            $this->headers[$name] = [];
        }
        foreach ($values as $value) {
            $this->headers[$name][] = $value;
        }
        return $this;
    }

    /**
     * Set the trailing gRPC status name (e.g. "OK", "NOT_FOUND").
     */
    public function statusName(string $statusName): self
    {
        $this->statusName = $statusName;
        return $this;
    }

    public function statusMessage(string $statusMessage): self
    {
        $this->statusMessage = $statusMessage;
        return $this;
    }

    /**
     * Append a single unconditional stream message.
     */
    public function message(GrpcBidiMessage $message): self
    {
        $this->messages[] = $message;
        return $this;
    }

    /**
     * Replace the full unconditional message list.
     *
     * @param list<GrpcBidiMessage> $messages
     */
    public function messages(array $messages): self
    {
        $this->messages = array_values($messages);
        return $this;
    }

    /**
     * Append a single per-inbound-message rule.
     */
    public function rule(GrpcBidiRule $rule): self
    {
        $this->rules[] = $rule;
        return $this;
    }

    /**
     * Replace the full rule list.
     *
     * @param list<GrpcBidiRule> $rules
     */
    public function rules(array $rules): self
    {
        $this->rules = array_values($rules);
        return $this;
    }

    public function closeConnection(bool $closeConnection): self
    {
        $this->closeConnection = $closeConnection;
        return $this;
    }

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
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if (!empty($this->headers)) {
            $data['headers'] = $this->headers;
        }
        if ($this->statusName !== null) {
            $data['statusName'] = $this->statusName;
        }
        if ($this->statusMessage !== null) {
            $data['statusMessage'] = $this->statusMessage;
        }
        if (!empty($this->messages)) {
            $data['messages'] = array_map(
                static fn(GrpcBidiMessage $m): array => $m->toArray(),
                $this->messages,
            );
        }
        if (!empty($this->rules)) {
            $data['rules'] = array_map(
                static fn(GrpcBidiRule $r): array => $r->toArray(),
                $this->rules,
            );
        }
        // closeConnection and primary are guarded on !== null, not truthiness: an explicit
        // false is meaningful (keep-open / non-primary) and must reach the server rather than
        // be dropped and re-defaulted. PHP treats false as falsy, so a truthy guard would lose it.
        if ($this->closeConnection !== null) {
            $data['closeConnection'] = $this->closeConnection;
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        return $data;
    }
}
