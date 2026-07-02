<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a JWT request matcher.
 *
 * Matches a request by inspecting a JSON Web Token carried on the request
 * (by default the {@code authorization} header with a {@code Bearer} scheme).
 * Claims are matched by exact string or regular expression; a leading {@code !}
 * negates the match for that claim.
 *
 * @example
 *   $jwt = Jwt::jwt()
 *       ->claim('sub', 'user-123')
 *       ->claim('role', '!admin')
 *       ->claim('email', '^.+@example.com$')
 *       ->issuer('https://issuer.example.com')
 *       ->audience('my-api')
 *       ->algorithm('RS256');
 */
class Jwt implements \JsonSerializable
{
    /** @var array<string, string> */
    private array $claims = [];
    private ?string $issuer = null;
    private ?string $audience = null;
    private ?string $algorithm = null;
    private ?string $header = null;
    private ?string $scheme = null;

    /**
     * Static factory for fluent construction.
     */
    public static function jwt(): self
    {
        return new self();
    }

    /**
     * Match a single claim by exact value or regular expression.
     *
     * A leading {@code !} negates the match (e.g. {@code '!admin'}).
     */
    public function claim(string $name, string $matcher): self
    {
        $this->claims[$name] = $matcher;
        return $this;
    }

    /**
     * Match multiple claims at once.
     *
     * @param array<string, string> $claims claim-name => exact-or-regex string
     */
    public function claims(array $claims): self
    {
        foreach ($claims as $name => $matcher) {
            $this->claims[$name] = $matcher;
        }
        return $this;
    }

    public function issuer(string $issuer): self
    {
        $this->issuer = $issuer;
        return $this;
    }

    public function audience(string $audience): self
    {
        $this->audience = $audience;
        return $this;
    }

    public function algorithm(string $algorithm): self
    {
        $this->algorithm = $algorithm;
        return $this;
    }

    /**
     * Name of the request header carrying the token (default {@code authorization}).
     */
    public function header(string $header): self
    {
        $this->header = $header;
        return $this;
    }

    /**
     * Authentication scheme prefix in the header value (e.g. {@code Bearer}).
     */
    public function scheme(string $scheme): self
    {
        $this->scheme = $scheme;
        return $this;
    }

    /**
     * @return array<string, string>
     */
    public function getClaims(): array
    {
        return $this->claims;
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

        if (!empty($this->claims)) {
            $data['claims'] = $this->claims;
        }
        if ($this->issuer !== null) {
            $data['issuer'] = $this->issuer;
        }
        if ($this->audience !== null) {
            $data['audience'] = $this->audience;
        }
        if ($this->algorithm !== null) {
            $data['algorithm'] = $this->algorithm;
        }
        if ($this->header !== null) {
            $data['header'] = $this->header;
        }
        if ($this->scheme !== null) {
            $data['scheme'] = $this->scheme;
        }

        return $data;
    }
}
