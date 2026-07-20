<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A single protobuf message within a {@see GrpcBidiResponse} — used both for
 * the unconditional {@code messages} list and for the {@code responses} of a
 * {@see GrpcBidiRule}, which share the same wire shape.
 *
 * Wire keys: json, templateType, delay. The {@code json} value is the protobuf
 * message rendered as JSON (decoded against the uploaded descriptor set); when
 * a {@code templateType} is set the {@code json} is treated as a template.
 *
 * Distinct from {@see GrpcStreamMessage} (server-stream): that type has no
 * {@code templateType}, which the bidi message schema allows — reusing it here
 * would silently drop the field.
 *
 * @example
 *   GrpcBidiMessage::message(['reply' => 'hi']);
 *   GrpcBidiMessage::message('{"reply":"{{request.body}}"}')
 *       ->templateType(GrpcBidiMessage::MUSTACHE);
 */
class GrpcBidiMessage implements \JsonSerializable
{
    /** Apache Velocity template engine. */
    public const VELOCITY = 'VELOCITY';
    /** JavaScript (Nashorn/GraalJS) template engine. */
    public const JAVASCRIPT = 'JAVASCRIPT';
    /** Mustache template engine. */
    public const MUSTACHE = 'MUSTACHE';

    private string|array|null $json = null;
    private ?string $templateType = null;
    private ?Delay $delay = null;

    /**
     * Create a message from a JSON-encodable payload.
     *
     * @param array|string $json an associative array (encoded to JSON) or a JSON string
     */
    public static function message(array|string $json): self
    {
        $message = new self();
        $message->json = $json;
        return $message;
    }

    /**
     * @param string $templateType one of VELOCITY, JAVASCRIPT, MUSTACHE
     */
    public function templateType(string $templateType): self
    {
        $this->templateType = strtoupper($templateType);
        return $this;
    }

    public function withDelay(Delay $delay): self
    {
        $this->delay = $delay;
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
        if ($this->json !== null) {
            $data['json'] = $this->json;
        }
        if ($this->templateType !== null) {
            $data['templateType'] = $this->templateType;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        return $data;
    }
}
