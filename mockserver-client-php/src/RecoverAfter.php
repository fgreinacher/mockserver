<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Deterministic retry/recovery primitive for an {@code httpResponse} action.
 *
 * Serves {@code failResponse} for the first {@code failTimes} matches, then
 * serves the action's configured response. Used to exercise a client's retry
 * and backoff behaviour without a stateful scenario.
 *
 * A {@code failTimes} that is null or <= 0 makes the whole thing inert, which
 * is why 0 is emitted rather than dropped — silently omitting it would change
 * a deliberately-inert configuration into an absent one.
 *
 * Wire keys: failTimes, failResponse, idempotencyHeader.
 *
 * @example
 *   RecoverAfter::failTimes(2)
 *       ->failResponse(HttpResponse::response()->statusCode(503))
 *       ->idempotencyHeader('X-Idempotency-Key');
 */
class RecoverAfter implements \JsonSerializable
{
    private ?int $failTimes = null;
    private ?HttpResponse $failResponse = null;
    private ?string $idempotencyHeader = null;

    /**
     * Static factory. Number of leading matches that serve the failure
     * response before the configured response is served.
     */
    public static function failTimes(int $failTimes): self
    {
        $recoverAfter = new self();
        $recoverAfter->failTimes = $failTimes;
        return $recoverAfter;
    }

    /**
     * Static factory for building without an initial failTimes.
     */
    public static function recoverAfter(): self
    {
        return new self();
    }

    public function withFailTimes(int $failTimes): self
    {
        $this->failTimes = $failTimes;
        return $this;
    }

    /**
     * The response served for the first failTimes matches.
     */
    public function failResponse(HttpResponse $failResponse): self
    {
        $this->failResponse = $failResponse;
        return $this;
    }

    /**
     * Request header whose value keys an idempotent retry, so repeated
     * attempts carrying the same value count as one.
     */
    public function idempotencyHeader(string $idempotencyHeader): self
    {
        $this->idempotencyHeader = $idempotencyHeader;
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

        if ($this->failTimes !== null) {
            $data['failTimes'] = $this->failTimes;
        }
        if ($this->failResponse !== null) {
            $data['failResponse'] = $this->failResponse->toArray();
        }
        if ($this->idempotencyHeader !== null) {
            $data['idempotencyHeader'] = $this->idempotencyHeader;
        }

        return $data;
    }
}
