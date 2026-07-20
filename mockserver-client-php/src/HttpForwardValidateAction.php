<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a "forward and validate against an OpenAPI spec" action.
 *
 * Produces the {@code httpForwardValidateAction} action JSON: the request is
 * forwarded to {@code host}/{@code port} and the exchange is validated against
 * the OpenAPI spec at {@code specUrlOrPayload}.
 *
 * Wire keys: delay, specUrlOrPayload, host, port, scheme, validateRequest,
 * validateResponse, validationMode, primary. {@code specUrlOrPayload} and
 * {@code host} are required by the server.
 *
 * @example
 *   HttpForwardValidateAction::forward('https://example.com/openapi.json', 'backend.example.com')
 *       ->port(443)
 *       ->scheme(HttpForwardValidateAction::HTTPS)
 *       ->validateRequest(true)
 *       ->validationMode(HttpForwardValidateAction::STRICT);
 */
class HttpForwardValidateAction implements \JsonSerializable
{
    /** Forward over plain HTTP. */
    public const HTTP = 'HTTP';
    /** Forward over HTTPS. */
    public const HTTPS = 'HTTPS';

    /** Fail the exchange when validation fails. */
    public const STRICT = 'STRICT';
    /** Record validation failures without failing the exchange. */
    public const LOG_ONLY = 'LOG_ONLY';

    private ?Delay $delay = null;
    private ?string $specUrlOrPayload = null;
    private ?string $host = null;
    private ?int $port = null;
    private ?string $scheme = null;
    private ?bool $validateRequest = null;
    private ?bool $validateResponse = null;
    private ?string $validationMode = null;
    private ?bool $primary = null;

    /**
     * Static factory. Both arguments are required by the server.
     *
     * @param string $specUrlOrPayload a URL to, or the inline payload of, the OpenAPI spec
     * @param string $host             the forward destination host
     */
    public static function forward(string $specUrlOrPayload, string $host): self
    {
        $action = new self();
        $action->specUrlOrPayload = $specUrlOrPayload;
        $action->host = $host;
        return $action;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function specUrlOrPayload(string $specUrlOrPayload): self
    {
        $this->specUrlOrPayload = $specUrlOrPayload;
        return $this;
    }

    public function host(string $host): self
    {
        $this->host = $host;
        return $this;
    }

    public function port(int $port): self
    {
        $this->port = $port;
        return $this;
    }

    /**
     * @param string $scheme HTTP or HTTPS
     */
    public function scheme(string $scheme): self
    {
        $this->scheme = strtoupper($scheme);
        return $this;
    }

    public function validateRequest(bool $validateRequest): self
    {
        $this->validateRequest = $validateRequest;
        return $this;
    }

    public function validateResponse(bool $validateResponse): self
    {
        $this->validateResponse = $validateResponse;
        return $this;
    }

    /**
     * @param string $validationMode STRICT or LOG_ONLY
     */
    public function validationMode(string $validationMode): self
    {
        $this->validationMode = strtoupper($validationMode);
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
        if ($this->specUrlOrPayload !== null) {
            $data['specUrlOrPayload'] = $this->specUrlOrPayload;
        }
        if ($this->host !== null) {
            $data['host'] = $this->host;
        }
        if ($this->port !== null) {
            $data['port'] = $this->port;
        }
        if ($this->scheme !== null) {
            $data['scheme'] = $this->scheme;
        }
        // The two validate* flags and primary are guarded on !== null, not truthiness: an
        // explicit false (validate nothing / non-primary) is a real configuration and must
        // reach the server rather than be dropped and re-defaulted.
        if ($this->validateRequest !== null) {
            $data['validateRequest'] = $this->validateRequest;
        }
        if ($this->validateResponse !== null) {
            $data['validateResponse'] = $this->validateResponse;
        }
        if ($this->validationMode !== null) {
            $data['validationMode'] = $this->validationMode;
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        return $data;
    }
}
