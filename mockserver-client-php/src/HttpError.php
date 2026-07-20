<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP error action.
 *
 * @example
 *   $error = HttpError::error()
 *       ->dropConnection(true)
 *       ->responseBytes(base64_encode("garbage"));
 *
 * Wire keys: dropConnection, responseBytes, streamError, primary.
 */
class HttpError implements \JsonSerializable
{
    private ?bool $dropConnection = null;
    private ?string $responseBytes = null;
    private ?int $streamError = null;
    private ?bool $primary = null;

    /**
     * Static factory for fluent construction.
     */
    public static function error(): self
    {
        return new self();
    }

    public function dropConnection(bool $dropConnection): self
    {
        $this->dropConnection = $dropConnection;
        return $this;
    }

    /**
     * @param string $responseBytes Base64-encoded bytes to return before dropping
     */
    public function responseBytes(string $responseBytes): self
    {
        $this->responseBytes = $responseBytes;
        return $this;
    }

    /**
     * Reset the matched request stream with this error code instead of
     * returning a response (HTTP/2 RST_STREAM, HTTP/3 RESET_STREAM). HTTP/1.1
     * has no stream concept, so there it falls back to dropping the connection.
     *
     * @param int $streamError protocol-level stream error code, e.g. 2 (INTERNAL_ERROR)
     */
    public function streamError(int $streamError): self
    {
        $this->streamError = $streamError;
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

        if ($this->dropConnection !== null) {
            $data['dropConnection'] = $this->dropConnection;
        }
        if ($this->responseBytes !== null) {
            $data['responseBytes'] = $this->responseBytes;
        }
        if ($this->streamError !== null) {
            $data['streamError'] = $this->streamError;
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }

        return $data;
    }
}
