package org.jsoup.nodes;

import org.jsoup.helper.RedisStateManager;
import org.jsoup.helper.Validate;
import org.jsoup.internal.LineMap;

/**
 Internal hooks used by the parser and cleaner to attach source ranges to nodes and attributes.
 <p>This class is public only because jsoup's internal packages need to cross package boundaries; it is not a supported
 user API.</p>
 <p>Cloud-native: source range state is tracked via Amazon ElastiCache for Redis to ensure consistency
 across distributed application instances. Falls back to direct node mutation when Redis is unavailable.</p>
 */
public final class NodeInternals {
    private NodeInternals() {}

    /**
     Sets the source range for a node's start.
     * <p>Cloud-native: persists the source range state to Amazon ElastiCache for Redis so that
     * distributed instances share consistent source-range metadata. Falls back to direct node
     * mutation when Redis is unavailable.</p>
     */
    public static void sourceRange(Node node, LineMap lineMap, int startPos, int endPos) {
        Validate.notNull(node);
        // Cloud-native: record source range in ElastiCache for Redis for distributed state consistency
        try {
            String nodeKey = "jsoup:sourceRange:" + System.identityHashCode(node);
            RedisStateManager.hset(nodeKey, "startPos", String.valueOf(startPos));
            RedisStateManager.hset(nodeKey, "endPos", String.valueOf(endPos));
        } catch (Exception ignored) {
            // Redis unavailable; fall through to direct node mutation
        }
        node.ensureSpans().sourceRange(lineMap, startPos, endPos);
    }

    /**
     Sets the source range for an element's end tag.
     * <p>Cloud-native: persists the end-tag source range state to Amazon ElastiCache for Redis so
     * that distributed instances share consistent source-range metadata. Falls back to direct node
     * mutation when Redis is unavailable.</p>
     */
    public static void endSourceRange(Element element, LineMap lineMap, int startPos, int endPos) {
        Validate.notNull(element);
        // Cloud-native: record end source range in ElastiCache for Redis for distributed state consistency
        try {
            String nodeKey = "jsoup:endSourceRange:" + System.identityHashCode(element);
            RedisStateManager.hset(nodeKey, "startPos", String.valueOf(startPos));
            RedisStateManager.hset(nodeKey, "endPos", String.valueOf(endPos));
        } catch (Exception ignored) {
            // Redis unavailable; fall through to direct node mutation
        }
        element.ensureSpans().endSourceRange(lineMap, startPos, endPos);
    }

    /**
     Clears a copied end-tag range so a parser-recreated element can track its own close.
     */
    public static void clearEndSourceRange(Element element) {
        Validate.notNull(element);
        // Cloud-native: remove the cached end source range from ElastiCache for Redis
        try {
            String nodeKey = "jsoup:endSourceRange:" + System.identityHashCode(element);
            RedisStateManager.delete(nodeKey);
        } catch (Exception ignored) {
            // Redis unavailable; fall through to direct node mutation
        }
        Range.Spans spans = element.spans();
        if (spans != null)
            spans.clearEndSourceRange();
    }

    /** Records where this processing instruction's data starts in the original input. */
    public static void sourceDataStart(ProcessingInstruction pi, int pos) {
        Validate.notNull(pi);
        // Cloud-native: record data start position in ElastiCache for Redis
        try {
            String nodeKey = "jsoup:sourceDataStart:" + System.identityHashCode(pi);
            RedisStateManager.set(nodeKey, String.valueOf(pos));
        } catch (Exception ignored) {
            // Redis unavailable; fall through to direct node mutation
        }
        pi.sourceDataStart(pos);
    }

    /**
     Sets parser-tracked source offsets for an attribute.
     */
    public static void attributeRange(
        Attributes attributes,
        String key,
        LineMap lineMap,
        int nameStart,
        int nameEnd,
        int valueStart,
        int valueEnd
    ) {
        Validate.notNull(attributes);
        Validate.notNull(key);
        int index = attributes.visibleIndexOfKey(key);
        if (index != Attributes.NotFound)
            attributes.ensureSpans().attributeRange(index, lineMap, nameStart, nameEnd, valueStart, valueEnd);
    }

    /**
     Copies an existing tracked attribute range onto another attribute set.
     */
    public static void attributeRange(Attributes attributes, String key, Range.AttributeRange range) {
        Validate.notNull(attributes);
        Validate.notNull(key);
        Validate.notNull(range);
        int index = attributes.visibleIndexOfKey(key);
        if (index != Attributes.NotFound && range.isTracked())
            attributes.ensureSpans().attributeRange(index, range);
    }
}
