<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a "forward with fallback" action.
 *
 * Produces the {@code httpForwardWithFallback} action JSON: the request is
 * forwarded via {@code httpForward}, and if the upstream fails (a status code
 * in {@code fallbackOnStatusCodes}, or a timeout when {@code fallbackOnTimeout}
 * is set) the {@code fallbackResponse} is served instead.
 *
 * Wire keys: delay, httpForward, fallbackResponse, fallbackOnStatusCodes,
 * fallbackOnTimeout, primary. {@code httpForward} and {@code fallbackResponse}
 * are required by the server.
 *
 * @example
 *   HttpForwardWithFallback::forward(
 *       HttpForward::forward()->host('backend.example.com')->port(443)->scheme('HTTPS'),
 *       HttpResponse::response()->statusCode(503)->body('unavailable'))
 *       ->fallbackOnStatusCodes(500, 502, 503)
 *       ->fallbackOnTimeout(true);
 */
class HttpForwardWithFallback implements \JsonSerializable
{
    private ?Delay $delay = null;
    private ?HttpForward $httpForward = null;
    private ?HttpResponse $fallbackResponse = null;
    /** @var list<int> */
    private array $fallbackOnStatusCodes = [];
    private ?bool $fallbackOnTimeout = null;
    private ?bool $primary = null;

    /**
     * Static factory. Both arguments are required by the server.
     */
    public static function forward(HttpForward $httpForward, HttpResponse $fallbackResponse): self
    {
        $action = new self();
        $action->httpForward = $httpForward;
        $action->fallbackResponse = $fallbackResponse;
        return $action;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function httpForward(HttpForward $httpForward): self
    {
        $this->httpForward = $httpForward;
        return $this;
    }

    public function fallbackResponse(HttpResponse $fallbackResponse): self
    {
        $this->fallbackResponse = $fallbackResponse;
        return $this;
    }

    /**
     * Set the upstream status codes that trigger the fallback (replaces any
     * previously set list).
     */
    public function fallbackOnStatusCodes(int ...$fallbackOnStatusCodes): self
    {
        $this->fallbackOnStatusCodes = array_values($fallbackOnStatusCodes);
        return $this;
    }

    public function fallbackOnTimeout(bool $fallbackOnTimeout): self
    {
        $this->fallbackOnTimeout = $fallbackOnTimeout;
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
        if ($this->httpForward !== null) {
            $data['httpForward'] = $this->httpForward->toArray();
        }
        if ($this->fallbackResponse !== null) {
            $data['fallbackResponse'] = $this->fallbackResponse->toArray();
        }
        if (!empty($this->fallbackOnStatusCodes)) {
            $data['fallbackOnStatusCodes'] = $this->fallbackOnStatusCodes;
        }
        // fallbackOnTimeout and primary are guarded on !== null, not truthiness: an explicit
        // false (do not fall back on timeout / non-primary) must reach the server rather than
        // be dropped and re-defaulted.
        if ($this->fallbackOnTimeout !== null) {
            $data['fallbackOnTimeout'] = $this->fallbackOnTimeout;
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        return $data;
    }
}
