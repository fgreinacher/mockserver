<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP request matcher.
 *
 * @example
 *   $request = HttpRequest::request()
 *       ->method('GET')
 *       ->path('/hello')
 *       ->queryStringParameter('q', 'value')
 *       ->header('Accept', 'application/json')
 *       ->body('request body');
 */
class HttpRequest implements \JsonSerializable
{
    private ?string $method = null;
    private ?string $path = null;
    /** @var array<string, list<string>> */
    private array $queryStringParameters = [];
    /** @var array<string, list<string>> */
    private array $headers = [];
    /** @var array<string, list<string>> */
    private array $cookies = [];
    private string|array|null $body = null;
    private ?bool $keepAlive = null;
    private ?bool $secure = null;
    private ?array $socketAddress = null;
    private ?array $jwt = null;

    /**
     * Static factory for fluent construction.
     */
    public static function request(): self
    {
        return new self();
    }

    public function method(string $method): self
    {
        $this->method = $method;
        return $this;
    }

    public function path(string $path): self
    {
        $this->path = $path;
        return $this;
    }

    /**
     * Add a query string parameter (multi-value supported).
     */
    public function queryStringParameter(string $name, string ...$values): self
    {
        if (!isset($this->queryStringParameters[$name])) {
            $this->queryStringParameters[$name] = [];
        }
        foreach ($values as $value) {
            $this->queryStringParameters[$name][] = $value;
        }
        return $this;
    }

    /**
     * Add a header (multi-value supported).
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
     * Add a cookie.
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
     * Set the request body as a plain string.
     */
    public function body(string $body): self
    {
        $this->body = $body;
        return $this;
    }

    /**
     * Set the request body as a typed JSON body.
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
     * Set the request body as a file reference.
     *
     * @param string $filePath Path to the file to serve
     * @param string|null $contentType Optional content type
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

    /**
     * Build a JSON_PATH body matcher array (for composition, e.g. inside allOfBody()).
     *
     * @return array<string, string>
     */
    public static function jsonPathBody(string $jsonPath): array
    {
        return [
            'type' => 'JSON_PATH',
            'jsonPath' => $jsonPath,
        ];
    }

    /**
     * Build a REGEX body matcher array (for composition, e.g. inside allOfBody()).
     *
     * @return array<string, string>
     */
    public static function regexBody(string $regex): array
    {
        return [
            'type' => 'REGEX',
            'regex' => $regex,
        ];
    }

    /**
     * Set the request body to an ALL_OF matcher that requires every supplied
     * sub-body matcher to match.
     *
     * Each sub-body is a typed body matcher array such as those produced by
     * {@see jsonPathBody()} / {@see regexBody()}, or any other body matcher
     * array (e.g. {@code ['type' => 'JSON', 'json' => '...']}).
     *
     * @param array<int, array<string, mixed>> $bodies list of body matcher arrays
     */
    public function allOfBody(array $bodies): self
    {
        $this->body = [
            'type' => 'ALL_OF',
            'bodyAllOf' => array_values($bodies),
        ];
        return $this;
    }

    /**
     * Match the request against a JSON Web Token carried on the request.
     *
     * @param Jwt|array<string, mixed> $jwt a {@see Jwt} builder or a raw jwt matcher array
     */
    public function jwt(Jwt|array $jwt): self
    {
        $this->jwt = $jwt instanceof Jwt ? $jwt->toArray() : $jwt;
        return $this;
    }

    public function keepAlive(bool $keepAlive): self
    {
        $this->keepAlive = $keepAlive;
        return $this;
    }

    public function socketAddress(string $host, int $port, string $scheme = 'HTTP'): self
    {
        $this->socketAddress = ['host' => $host, 'port' => $port, 'scheme' => $scheme];
        return $this;
    }

    public function secure(bool $secure): self
    {
        $this->secure = $secure;
        return $this;
    }

    public function getMethod(): ?string
    {
        return $this->method;
    }

    public function getPath(): ?string
    {
        return $this->path;
    }

    /**
     * @return array<string, list<string>>
     */
    public function getQueryStringParameters(): array
    {
        return $this->queryStringParameters;
    }

    /**
     * @return array<string, list<string>>
     */
    public function getHeaders(): array
    {
        return $this->headers;
    }

    /**
     * @return array<string, list<string>>
     */
    public function getCookies(): array
    {
        return $this->cookies;
    }

    public function getBody(): string|array|null
    {
        return $this->body;
    }

    /**
     * @return array<string, mixed>|null
     */
    public function getJwt(): ?array
    {
        return $this->jwt;
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

        if ($this->method !== null) {
            $data['method'] = $this->method;
        }
        if ($this->path !== null) {
            $data['path'] = $this->path;
        }
        if (!empty($this->queryStringParameters)) {
            $data['queryStringParameters'] = $this->queryStringParameters;
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
        if ($this->jwt !== null) {
            $data['jwt'] = $this->jwt;
        }
        if ($this->keepAlive !== null) {
            $data['keepAlive'] = $this->keepAlive;
        }
        if ($this->secure !== null) {
            $data['secure'] = $this->secure;
        }
        if ($this->socketAddress !== null) {
            $data['socketAddress'] = $this->socketAddress;
        }

        return $data;
    }
}
