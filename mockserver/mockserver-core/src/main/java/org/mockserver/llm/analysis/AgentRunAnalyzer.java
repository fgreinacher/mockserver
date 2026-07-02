package org.mockserver.llm.analysis;

import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic, read-only analysis of an agent run reconstructed from recorded
 * LLM requests. Each request is decoded with the provider's
 * {@link ProviderCodec} into a {@link ParsedConversation}; the richest
 * conversation (most messages — i.e. the latest snapshot of the dialogue) is
 * treated as the canonical run.
 * <p>
 * Pure (no network, no LLM): it inspects the structure the codecs already
 * produce. Powers the {@code verify_tool_call} and {@code explain_agent_run}
 * MCP tools.
 */
public class AgentRunAnalyzer {

    /** A single tool call the assistant made. */
    public static final class ToolCallMatch {
        private final String name;
        private final String arguments;

        public ToolCallMatch(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() {
            return name;
        }

        public String getArguments() {
            return arguments;
        }
    }

    /** Result of a {@code verify_tool_call}-style inspection. */
    public static final class ToolCallReport {
        private final int count;
        private final List<ToolCallMatch> matches;

        public ToolCallReport(int count, List<ToolCallMatch> matches) {
            this.count = count;
            this.matches = matches;
        }

        public int getCount() {
            return count;
        }

        public List<ToolCallMatch> getMatches() {
            return matches;
        }
    }

    /** Structural summary of an agent run (for {@code explain_agent_run}). */
    public static final class RunSummary {
        private final int messageCount;
        private final int assistantTurnCount;
        private final List<String> toolCallSequence;
        private final List<String> toolResultsFor;
        private final String latestMessageRole;

        public RunSummary(int messageCount, int assistantTurnCount, List<String> toolCallSequence,
                          List<String> toolResultsFor, String latestMessageRole) {
            this.messageCount = messageCount;
            this.assistantTurnCount = assistantTurnCount;
            this.toolCallSequence = toolCallSequence;
            this.toolResultsFor = toolResultsFor;
            this.latestMessageRole = latestMessageRole;
        }

        public int getMessageCount() {
            return messageCount;
        }

        public int getAssistantTurnCount() {
            return assistantTurnCount;
        }

        public List<String> getToolCallSequence() {
            return toolCallSequence;
        }

        public List<String> getToolResultsFor() {
            return toolResultsFor;
        }

        public String getLatestMessageRole() {
            return latestMessageRole;
        }
    }

    /**
     * Count the assistant tool calls matching {@code toolName} (and, optionally,
     * whose arguments match {@code argumentsRegex}) across the canonical
     * conversation decoded from the given requests.
     *
     * @param argumentsRegex optional regex matched (via find) against the tool
     *                       call's argument JSON string; null/empty means any
     */
    public ToolCallReport inspectToolCalls(List<HttpRequest> requests, Provider provider,
                                           String toolName, String argumentsRegex) {
        Pattern argsPattern = compileOrNull(argumentsRegex);
        List<ToolCallMatch> matches = new ArrayList<>();
        ParsedConversation canonical = canonicalConversation(requests, provider);
        if (canonical != null) {
            for (ParsedMessage message : canonical.getMessages()) {
                if (message.getRole() != ParsedMessage.Role.ASSISTANT) {
                    continue;
                }
                for (ToolUse toolCall : message.getToolCalls()) {
                    if (toolName != null && !toolName.equals(toolCall.getName())) {
                        continue;
                    }
                    if (argsPattern != null) {
                        String args = toolCall.getArguments();
                        if (args == null || !argsPattern.matcher(args).find()) {
                            continue;
                        }
                    }
                    matches.add(new ToolCallMatch(toolCall.getName(), toolCall.getArguments()));
                }
            }
        }
        return new ToolCallReport(matches.size(), matches);
    }

    /**
     * Summarise the canonical conversation's structure: message and assistant
     * turn counts, the ordered sequence of tool-call names, the tool names a
     * result was returned for, and the latest message's role. Returns empty when
     * no conversation can be decoded.
     */
    public Optional<RunSummary> summarise(List<HttpRequest> requests, Provider provider) {
        ParsedConversation canonical = canonicalConversation(requests, provider);
        if (canonical == null || canonical.getMessages().isEmpty()) {
            return Optional.empty();
        }
        List<ParsedMessage> messages = canonical.getMessages();
        int assistantTurns = 0;
        List<String> toolCallSequence = new ArrayList<>();
        Set<String> toolResultsFor = new LinkedHashSet<>();
        for (ParsedMessage message : messages) {
            if (message.getRole() == ParsedMessage.Role.ASSISTANT) {
                assistantTurns++;
                for (ToolUse toolCall : message.getToolCalls()) {
                    toolCallSequence.add(toolCall.getName());
                }
            }
            if (message.getRole() == ParsedMessage.Role.TOOL) {
                toolResultsFor.addAll(message.getToolResults().keySet());
            }
        }
        String latestRole = messages.get(messages.size() - 1).getRole().name();
        return Optional.of(new RunSummary(messages.size(), assistantTurns, toolCallSequence,
            new ArrayList<>(toolResultsFor), latestRole));
    }

    /** A node in the agent-run call graph. */
    public static final class GraphNode {
        private final String id;
        private final String kind;   // USER / ASSISTANT / SYSTEM / TOOL / TOOL_CALL
        private final String label;

        public GraphNode(String id, String kind, String label) {
            this.id = id;
            this.kind = kind;
            this.label = label;
        }

        public String getId() {
            return id;
        }

        public String getKind() {
            return kind;
        }

        public String getLabel() {
            return label;
        }
    }

    /** A directed edge in the agent-run call graph. */
    public static final class GraphEdge {
        private final String from;
        private final String to;
        private final String kind;   // NEXT (sequence) / INVOKES (turn->tool call) / RESULT (tool call->result)

        public GraphEdge(String from, String to, String kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }

        public String getKind() {
            return kind;
        }
    }

    /** The correlated call graph of an agent run. */
    public static final class CallGraph {
        private final List<GraphNode> nodes;
        private final List<GraphEdge> edges;

        public CallGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public List<GraphNode> getNodes() {
            return nodes;
        }

        public List<GraphEdge> getEdges() {
            return edges;
        }
    }

    /**
     * Build a correlated call graph for an agent run: a node per message, a node
     * per assistant tool call, {@code NEXT} edges along the message sequence,
     * {@code INVOKES} edges from an assistant turn to the tool calls it made, and
     * {@code RESULT} edges from a tool call to the tool message that returned its
     * result (correlated by tool-call id, mirroring the matcher's correlation).
     * Empty graph when nothing decodes.
     */
    public CallGraph buildCallGraph(List<HttpRequest> requests, Provider provider) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        ParsedConversation canonical = canonicalConversation(requests, provider);
        if (canonical == null || canonical.getMessages().isEmpty()) {
            return new CallGraph(nodes, edges);
        }
        List<ParsedMessage> messages = canonical.getMessages();
        Map<String, String> toolCallIdToNode = new HashMap<>();
        String previousMessageId = null;
        for (int i = 0; i < messages.size(); i++) {
            ParsedMessage message = messages.get(i);
            String messageId = "m" + i;
            nodes.add(new GraphNode(messageId, message.getRole().name(), label(message)));
            if (previousMessageId != null) {
                edges.add(new GraphEdge(previousMessageId, messageId, "NEXT"));
            }
            previousMessageId = messageId;

            if (message.getRole() == ParsedMessage.Role.ASSISTANT) {
                int t = 0;
                for (ToolUse toolCall : message.getToolCalls()) {
                    String toolNodeId = messageId + "_tc" + (t++);
                    nodes.add(new GraphNode(toolNodeId, "TOOL_CALL", toolCall.getName()));
                    edges.add(new GraphEdge(messageId, toolNodeId, "INVOKES"));
                    if (toolCall.getId() != null) {
                        toolCallIdToNode.put(toolCall.getId(), toolNodeId);
                    }
                }
            }
            if (message.getRole() == ParsedMessage.Role.TOOL) {
                for (String resultId : message.getToolResults().keySet()) {
                    String toolNodeId = toolCallIdToNode.get(resultId);
                    if (toolNodeId != null) {
                        edges.add(new GraphEdge(toolNodeId, messageId, "RESULT"));
                    }
                }
            }
        }
        return new CallGraph(nodes, edges);
    }

    private static String label(ParsedMessage message) {
        String text = message.getTextContent();
        if (text == null || text.isEmpty()) {
            return message.getRole().name();
        }
        // truncate by code points so a surrogate pair is never split
        String trimmed = text;
        if (text.codePointCount(0, text.length()) > 40) {
            trimmed = new String(text.codePoints().limit(40).toArray(), 0, 40) + "…";
        }
        return trimmed.replaceAll("\\s+", " ").trim();
    }

    /**
     * Decode each request and return the conversation with the most messages —
     * the richest/latest snapshot of the dialogue. Null if none decode.
     * <p>
     * Public so sibling analysers ({@link AgentRunDiff}) reconstruct the canonical
     * run the same way this analyser does.
     */
    public ParsedConversation canonicalConversation(List<HttpRequest> requests, Provider provider) {
        if (requests == null || provider == null) {
            return null;
        }
        Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codecOpt.isPresent()) {
            return null;
        }
        ProviderCodec codec = codecOpt.get();
        ParsedConversation richest = null;
        // Requests arrive in log order; on an equal message count the later one wins
        // (>=), so "richest" resolves to the latest snapshot of the dialogue.
        for (HttpRequest request : requests) {
            try {
                ParsedConversation parsed = codec.decode(request);
                if (parsed != null && (richest == null || parsed.getMessages().size() >= richest.getMessages().size())) {
                    richest = parsed;
                }
            } catch (Exception e) {
                // skip requests that do not decode for this provider
            }
        }
        return richest;
    }

    private Pattern compileOrNull(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid argumentsRegex: " + regex, e);
        }
    }
}
