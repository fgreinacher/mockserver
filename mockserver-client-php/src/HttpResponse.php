<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP response action.
 *
 * @example
 *   $response = HttpResponse::response()
 *       ->statusCode(200)
 *       ->header('Content-Type', 'application/json')
 *       ->body('{"message":"hello"}');
 */
class HttpResponse implements \JsonSerializable
{
    private ?int $statusCode = null;
    private ?string $reasonPhrase = null;
    /** @var array<string, list<string>> */
    private array $headers = [];
    /** @var array<string, list<string>> */
    private array $cookies = [];
    private string|array|null $body = null;
    private ?Delay $delay = null;
    private ?ConnectionOptions $connectionOptions = null;
    private ?bool $primary = null;

    /**
     * Static factory for fluent construction.
     */
    public static function response(): self
    {
        return new self();
    }

    public function statusCode(int $statusCode): self
    {
        $this->statusCode = $statusCode;
        return $this;
    }

    public function reasonPhrase(string $reasonPhrase): self
    {
        $this->reasonPhrase = $reasonPhrase;
        return $this;
    }

    /**
     * Add a response header (multi-value supported).
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
     * Add a response cookie.
     */
    public function cookie(string $name, string $value): self
    {
        if (!isset($this->cookies[$name])) {
            $this->cookies[$name] = [];
        }
        $this->cookies[$name][] = $value;
        return $this;
    }

    /**
     * Set the response body as a plain string.
     */
    public function body(string $body): self
    {
        $this->body = $body;
        return $this;
    }

    /**
     * Set the response body as a typed JSON body.
     *
     * @param array|string $json JSON content (string or array that will be JSON-encoded)
     */
    public function jsonBody(array|string $json): self
    {
        $jsonString = is_array($json) ? json_encode($json, JSON_THROW_ON_ERROR) : $json;
        $this->body = [
            'type' => 'JSON',
            'json' => $jsonString,
        ];
        return $this;
    }

    /**
     * Set the response body as a file reference.
     *
     * @param string $filePath Path to the file to serve
     * @param string|null $contentType Optional content type for the response
     * @param string|null $templateType Optional template type ("VELOCITY" or "MUSTACHE") for templating the file
     */
    public function fileBody(string $filePath, ?string $contentType = null, ?string $templateType = null): self
    {
        $body = [
            'type' => 'FILE',
            'filePath' => $filePath,
        ];
        if ($contentType !== null) {
            $body['contentType'] = $contentType;
        }
        if ($templateType !== null) {
            $body['templateType'] = $templateType;
        }
        $this->body = $body;
        return $this;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function connectionOptions(ConnectionOptions $connectionOptions): self
    {
        $this->connectionOptions = $connectionOptions;
        return $this;
    }

    public function getStatusCode(): ?int
    {
        return $this->statusCode;
    }

    /**
     * @return array<string, list<string>>
     */
    public function getHeaders(): array
    {
        return $this->headers;
    }

    public function getBody(): string|array|null
    {
        return $this->body;
    }

    public function getDelay(): ?Delay
    {
        return $this->delay;
    }

    /**
     * Mark this action as the primary one for the expectation.
     *
     * Only meaningful when an expectation carries more than one action: the
     * server requires exactly one to be marked primary and rejects the
     * expectation otherwise. Omitting it from a re-submitted expectation can
     * silently change which action executes.
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

        if ($this->statusCode !== null) {
            $data['statusCode'] = $this->statusCode;
        }
        if ($this->reasonPhrase !== null) {
            $data['reasonPhrase'] = $this->reasonPhrase;
        }
        if (!empty($this->headers)) {
            $data['headers'] = $this->headers;
        }
        if (!empty($this->cookies)) {
            $data['cookies'] = $this->cookies;
        }
        if ($this->body !== null) {
            $data['body'] = $this->body;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->connectionOptions !== null) {
            $data['connectionOptions'] = $this->connectionOptions->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }

        return $data;
    }
}
