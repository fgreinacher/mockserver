<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A per-incoming-message rule within a {@see GrpcBidiResponse}: when an inbound
 * client message matches {@code matchJson}, the rule's {@code responses} are
 * pushed back down the bidirectional stream.
 *
 * Wire keys: matchJson, responses. {@code matchJson} is matched as a JSON value
 * (a leading {@code !} negates, per the shared matcher convention); each entry
 * of {@code responses} is a {@see GrpcBidiMessage}.
 *
 * @example
 *   GrpcBidiRule::matchJson('{"name":"world"}')
 *       ->response(GrpcBidiMessage::message('{"reply":"hello world"}')
 *           ->templateType(GrpcBidiMessage::MUSTACHE));
 */
class GrpcBidiRule implements \JsonSerializable
{
    private string|array|null $matchJson = null;
    /** @var list<GrpcBidiMessage> */
    private array $responses = [];

    /**
     * Static factory. The inbound message this rule matches.
     *
     * @param array|string $matchJson an associative array (encoded to JSON) or a JSON string
     */
    public static function matchJson(array|string $matchJson): self
    {
        $rule = new self();
        $rule->matchJson = $matchJson;
        return $rule;
    }

    /**
     * Append a single response message pushed when this rule matches.
     */
    public function response(GrpcBidiMessage $response): self
    {
        $this->responses[] = $response;
        return $this;
    }

    /**
     * Replace the full response list.
     *
     * @param list<GrpcBidiMessage> $responses
     */
    public function responses(array $responses): self
    {
        $this->responses = array_values($responses);
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
        if ($this->matchJson !== null) {
            $data['matchJson'] = $this->matchJson;
        }
        if (!empty($this->responses)) {
            $data['responses'] = array_map(
                static fn(GrpcBidiMessage $r): array => $r->toArray(),
                $this->responses,
            );
        }
        return $data;
    }
}
