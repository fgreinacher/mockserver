<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP forward action.
 *
 * @example
 *   $forward = HttpForward::forward()
 *       ->host('example.com')
 *       ->port(443)
 *       ->scheme('HTTPS');
 */
class HttpForward implements \JsonSerializable
{
    private ?string $host = null;
    private ?int $port = null;
    private ?string $scheme = null;
    private ?bool $primary = null;
    private ?Delay $delay = null;

    /**
     * Static factory for fluent construction.
     */
    public static function forward(): self
    {
        return new self();
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
     * @param string $scheme "HTTP" or "HTTPS"
     */
    public function scheme(string $scheme): self
    {
        $this->scheme = strtoupper($scheme);
        return $this;
    }

    /**
     * Apply a delay before the request is forwarded.
     */
    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
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

    public function getHost(): ?string
    {
        return $this->host;
    }

    public function getPort(): ?int
    {
        return $this->port;
    }

    public function getScheme(): ?string
    {
        return $this->scheme;
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

        if ($this->host !== null) {
            $data['host'] = $this->host;
        }
        if ($this->port !== null) {
            $data['port'] = $this->port;
        }
        if ($this->scheme !== null) {
            $data['scheme'] = $this->scheme;
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
