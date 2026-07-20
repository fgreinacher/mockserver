<?php

declare(strict_types=1);

namespace MockServer;

/**
 * GraphQL subscription filter for the {@code graphql-transport-ws} protocol.
 *
 * Set on {@see HttpWebSocketResponse}: when present, incoming subscribe
 * messages are AST-matched against this filter, so a single WebSocket
 * expectation can answer one subscription and ignore others.
 *
 * Wire keys: type, query, operationName, variablesSchema,
 * selectionSetMatchType, fields. Only {@code query} is required by the server.
 *
 * @example
 *   GraphQLSubscriptionFilter::query('subscription OnMessage { messageAdded { id } }')
 *       ->operationName('OnMessage')
 *       ->selectionSetMatchType(GraphQLSubscriptionFilter::AST_SUBSET)
 *       ->fields(['messageAdded']);
 */
class GraphQLSubscriptionFilter implements \JsonSerializable
{
    /** Compare the selection set as a normalised string. */
    public const NORMALISED_STRING = 'NORMALISED_STRING';
    /** The selection sets must match exactly, as ASTs. */
    public const AST_EXACT = 'AST_EXACT';
    /** The incoming selection set must be a subset of this one. */
    public const AST_SUBSET = 'AST_SUBSET';

    private string $query;
    private ?string $type = null;
    private ?string $operationName = null;
    private ?string $variablesSchema = null;
    private ?string $selectionSetMatchType = null;
    /** @var list<string>|null */
    private ?array $fields = null;

    public function __construct(string $query)
    {
        $this->query = $query;
    }

    /**
     * Static factory. The subscription query is the only field the server requires.
     */
    public static function query(string $query): self
    {
        return new self($query);
    }

    /**
     * Body-type discriminator. Only "GRAPHQL" is meaningful, and the server
     * infers it, so this is rarely needed — it exists so a filter decoded from
     * a server response can be re-serialised without losing the key.
     */
    public function type(string $type): self
    {
        $this->type = strtoupper($type);
        return $this;
    }

    public function operationName(string $operationName): self
    {
        $this->operationName = $operationName;
        return $this;
    }

    public function variablesSchema(string $variablesSchema): self
    {
        $this->variablesSchema = $variablesSchema;
        return $this;
    }

    /**
     * @param string $selectionSetMatchType one of NORMALISED_STRING, AST_EXACT, AST_SUBSET
     */
    public function selectionSetMatchType(string $selectionSetMatchType): self
    {
        $this->selectionSetMatchType = strtoupper($selectionSetMatchType);
        return $this;
    }

    /**
     * Restrict matching to these top-level subscription fields.
     *
     * @param list<string> $fields
     */
    public function fields(array $fields): self
    {
        $this->fields = array_values($fields);
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

        if ($this->type !== null) {
            $data['type'] = $this->type;
        }
        $data['query'] = $this->query;
        if ($this->operationName !== null) {
            $data['operationName'] = $this->operationName;
        }
        if ($this->variablesSchema !== null) {
            $data['variablesSchema'] = $this->variablesSchema;
        }
        if ($this->selectionSetMatchType !== null) {
            $data['selectionSetMatchType'] = $this->selectionSetMatchType;
        }
        if ($this->fields !== null) {
            $data['fields'] = $this->fields;
        }

        return $data;
    }
}
