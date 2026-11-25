package org.codehaus.jackson.map.deser;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import org.codehaus.jackson.*;
import org.codehaus.jackson.map.*;
import org.codehaus.jackson.map.annotate.JsonDeserialize;
import org.codehaus.jackson.map.module.SimpleModule;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;

/**
 * Unit tests verifying that deeply nested JSON content bound as "untyped"
 * values ({@link Object}, {@link Map}, {@link List}, <code>Object[]</code>)
 * or as trees ({@link JsonNode}) is rejected with a {@link JsonParseException}
 * instead of exhausting the stack (CVE-2026-95155).
 */
public class TestDeepNestingDeser
    extends BaseMapTest
{
    /**
     * Maximum number of nested JSON Arrays/Objects accepted
     */
    private final static int MAX_DEPTH = 1000;

    /**
     * Nesting that overflows the stack unless limited
     */
    private final static int TOO_DEEP = 100000;

    /**
     * Sub-class that overrides the <code>mapObject</code> extension point:
     * counts calls and marks every Map it produces.
     */
    static class MarkingUntypedDeserializer extends UntypedObjectDeserializer
    {
        int objectCalls = 0;

        @Override
        protected Map<String,Object> mapObject(JsonParser jp, DeserializationContext ctxt)
            throws IOException, JsonProcessingException
        {
            ++objectCalls;
            Map<String,Object> result = super.mapObject(jp, ctxt);
            result.put("marked", Boolean.TRUE);
            return result;
        }
    }

    static class MarkedBean
    {
        @JsonDeserialize(using=MarkingUntypedDeserializer.class)
        public Object value;
    }

    /*
    /**********************************************************
    /* Untyped (Object/Map/List/Object[]) binding
    /**********************************************************
     */

    public void testTooDeepUntypedArray() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedArrays(TOO_DEEP), Object.class);
    }

    public void testTooDeepUntypedObject() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedObjects(TOO_DEEP), Object.class);
    }

    public void testTooDeepUntypedMixed() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedMixed(TOO_DEEP), Object.class);
    }

    public void testTooDeepAsMap() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedObjects(TOO_DEEP), Map.class);
    }

    public void testTooDeepAsList() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedArrays(TOO_DEEP), List.class);
    }

    public void testTooDeepAsObjectArray() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), _nestedArrays(TOO_DEEP), Object[].class);
    }

    public void testTooDeepInBeanProperty() throws Exception
    {
        _verifyTooDeep(new ObjectMapper(), "{\"value\":" + _nestedArrays(TOO_DEEP) + "}",
                UntypedBean.class);
        _verifyTooDeep(new ObjectMapper(), "{\"value\":" + _nestedObjects(TOO_DEEP) + "}",
                UntypedBean.class);
    }

    public void testTooDeepWithDefaultTyping() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enableDefaultTyping();
        // typed values recurse via deserializeWithType, not mapArray/mapObject
        String doc = _nestedTypedLists(TOO_DEEP);
        _verifyTooDeep(mapper, doc, Object.class);

        // but moderate nesting is still fine
        Object ob = mapper.readValue(_nestedTypedLists(100), Object.class);
        assertEquals(100, _listDepth(ob));
    }

    public void testUntypedDepthLimit() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(900, _listDepth(mapper.readValue(_nestedArrays(900), Object.class)));
        assertEquals(MAX_DEPTH, _listDepth(mapper.readValue(_nestedArrays(MAX_DEPTH), Object.class)));
        _verifyTooDeep(mapper, _nestedArrays(MAX_DEPTH + 1), Object.class);

        assertEquals(900, _mapDepth(mapper.readValue(_nestedObjects(900), Object.class)));
        assertEquals(MAX_DEPTH, _mapDepth(mapper.readValue(_nestedObjects(MAX_DEPTH), Object.class)));
        _verifyTooDeep(mapper, _nestedObjects(MAX_DEPTH + 1), Object.class);
    }

    public void testDepthResetAfterFailure() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < 3; ++i) {
            _verifyTooDeep(mapper, _nestedMixed(TOO_DEEP), Object.class);
            _verifyTooDeep(mapper, _nestedArrays(MAX_DEPTH + 1), Object.class);
            // nothing left counted: full depth still available on this thread
            assertEquals(MAX_DEPTH, _listDepth(mapper.readValue(_nestedArrays(MAX_DEPTH), Object.class)));
            assertEquals(MAX_DEPTH, _mapDepth(mapper.readValue(_nestedObjects(MAX_DEPTH), Map.class)));
        }
        // and no per-thread state retained once done
        Field f = UntypedObjectDeserializer.class.getDeclaredField("_depth");
        f.setAccessible(true);
        ThreadLocal<?> tl = (ThreadLocal<?>) f.get(null);
        assertNull(tl.get());
    }

    public void testRegularUntypedContent() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        Object ob = mapper.readValue("{\"a\":[1,{\"b\":true},[]],\"c\":{},\"d\":\"x\"}", Object.class);
        assertTrue(ob instanceof Map<?,?>);
        Map<?,?> map = (Map<?,?>) ob;
        assertEquals(3, map.size());
        List<?> list = (List<?>) map.get("a");
        assertEquals(3, list.size());
        assertEquals(Integer.valueOf(1), list.get(0));
        assertEquals(Boolean.TRUE, ((Map<?,?>) list.get(1)).get("b"));
        assertEquals(0, ((List<?>) list.get(2)).size());
        assertEquals(0, ((Map<?,?>) map.get("c")).size());
        assertEquals("x", map.get("d"));
    }

    /*
    /**********************************************************
    /* Sub-classes overriding extension points
    /**********************************************************
     */

    public void testOverriddenMapObjectCalledAtAllLevels() throws Exception
    {
        MarkingUntypedDeserializer deser = new MarkingUntypedDeserializer();
        SimpleModule module = new SimpleModule("test", Version.unknownVersion());
        module.addDeserializer(Object.class, deser);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(module);

        Object ob = mapper.readValue("{\"a\":{\"b\":{\"c\":1}},\"list\":[{\"d\":2},[{}]]}",
                Object.class);
        // top level
        Map<?,?> root = (Map<?,?>) ob;
        assertEquals(Boolean.TRUE, root.get("marked"));
        // nested in Objects
        Map<?,?> a = (Map<?,?>) root.get("a");
        assertEquals(Boolean.TRUE, a.get("marked"));
        Map<?,?> b = (Map<?,?>) a.get("b");
        assertEquals(Boolean.TRUE, b.get("marked"));
        assertEquals(Integer.valueOf(1), b.get("c"));
        // nested in Arrays
        List<?> list = (List<?>) root.get("list");
        assertEquals(Boolean.TRUE, ((Map<?,?>) list.get(0)).get("marked"));
        assertEquals(Boolean.TRUE, ((Map<?,?>) ((List<?>) list.get(1)).get(0)).get("marked"));
        assertEquals(5, deser.objectCalls);

        // still limited, through the overridden method
        deser.objectCalls = 0;
        _verifyTooDeep(mapper, _nestedObjects(TOO_DEEP), Object.class);
        assertEquals(MAX_DEPTH + 1, deser.objectCalls);
        deser.objectCalls = 0;
        assertEquals(MAX_DEPTH, _mapDepth(mapper.readValue(_nestedObjects(MAX_DEPTH), Object.class)));
        assertEquals(MAX_DEPTH, deser.objectCalls);
    }

    public void testOverriddenMapObjectViaAnnotation() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        MarkedBean bean = mapper.readValue("{\"value\":{\"a\":[{\"b\":{}}]}}", MarkedBean.class);
        Map<?,?> value = (Map<?,?>) bean.value;
        assertEquals(Boolean.TRUE, value.get("marked"));
        Map<?,?> nested = (Map<?,?>) ((List<?>) value.get("a")).get(0);
        assertEquals(Boolean.TRUE, nested.get("marked"));
        assertEquals(Boolean.TRUE, ((Map<?,?>) nested.get("b")).get("marked"));

        _verifyTooDeep(mapper, "{\"value\":" + _nestedObjects(TOO_DEEP) + "}", MarkedBean.class);
    }

    /*
    /**********************************************************
    /* Tree (JsonNode) binding
    /**********************************************************
     */

    public void testTooDeepTree() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        _verifyTooDeepTree(mapper, _nestedArrays(TOO_DEEP));
        _verifyTooDeepTree(mapper, _nestedObjects(TOO_DEEP));
        _verifyTooDeepTree(mapper, _nestedMixed(TOO_DEEP));
        _verifyTooDeep(mapper, _nestedArrays(TOO_DEEP), JsonNode.class);
        _verifyTooDeep(mapper, _nestedArrays(TOO_DEEP), ArrayNode.class);
        _verifyTooDeep(mapper, _nestedObjects(TOO_DEEP), ObjectNode.class);
        _verifyTooDeep(mapper, "{\"value\":" + _nestedObjects(TOO_DEEP) + "}", NodeWrapper.class);
    }

    public void testTreeDepthLimit() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(900, _nodeDepth(mapper.readTree(_nestedArrays(900))));
        assertEquals(MAX_DEPTH, _nodeDepth(mapper.readTree(_nestedArrays(MAX_DEPTH))));
        _verifyTooDeepTree(mapper, _nestedArrays(MAX_DEPTH + 1));

        assertEquals(900, _nodeDepth(mapper.readTree(_nestedObjects(900))));
        assertEquals(MAX_DEPTH, _nodeDepth(mapper.readTree(_nestedObjects(MAX_DEPTH))));
        _verifyTooDeepTree(mapper, _nestedObjects(MAX_DEPTH + 1));

        assertEquals(MAX_DEPTH, _nodeDepth(mapper.readValue(_nestedObjects(MAX_DEPTH), ObjectNode.class)));
        _verifyTooDeep(mapper, _nestedObjects(MAX_DEPTH + 1), ObjectNode.class);
        assertEquals(MAX_DEPTH, _nodeDepth(mapper.readValue(_nestedArrays(MAX_DEPTH), ArrayNode.class)));
        _verifyTooDeep(mapper, _nestedArrays(MAX_DEPTH + 1), ArrayNode.class);
    }

    /*
    /**********************************************************
    /* Helper types, methods
    /**********************************************************
     */

    static class UntypedBean
    {
        public Object value;
    }

    static class NodeWrapper
    {
        public JsonNode value;
    }

    private void _verifyTooDeep(ObjectMapper mapper, String doc, Class<?> type) throws Exception
    {
        try {
            Object ob = mapper.readValue(doc, type);
            fail("Should have failed for too deeply nested content, got: "+ob.getClass().getName());
        } catch (JsonParseException e) {
            verifyException(e, "JSON is too deeply nested");
        }
    }

    private void _verifyTooDeepTree(ObjectMapper mapper, String doc) throws Exception
    {
        try {
            JsonNode n = mapper.readTree(doc);
            fail("Should have failed for too deeply nested content, got: "+n.getClass().getName());
        } catch (JsonParseException e) {
            verifyException(e, "JSON is too deeply nested");
        }
    }

    // [[[...]]]
    private static String _nestedArrays(int depth)
    {
        StringBuilder sb = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; ++i) {
            sb.append('[');
        }
        for (int i = 0; i < depth; ++i) {
            sb.append(']');
        }
        return sb.toString();
    }

    // {"a":{"a":...{"a":1}...}}
    private static String _nestedObjects(int depth)
    {
        StringBuilder sb = new StringBuilder(depth * 7);
        for (int i = 0; i < depth; ++i) {
            sb.append("{\"a\":");
        }
        sb.append('1');
        for (int i = 0; i < depth; ++i) {
            sb.append('}');
        }
        return sb.toString();
    }

    // {"a":[{"a":[...]}]}
    private static String _nestedMixed(int depth)
    {
        StringBuilder sb = new StringBuilder(depth * 4);
        for (int i = 0; i < depth; ++i) {
            sb.append(((i & 1) == 0) ? "{\"a\":" : "[");
        }
        for (int i = depth; --i >= 0; ) {
            sb.append(((i & 1) == 0) ? '}' : ']');
        }
        return sb.toString();
    }

    // default typing: ["java.util.ArrayList",[["java.util.ArrayList",[...]]]]
    private static String _nestedTypedLists(int depth)
    {
        final String open = "[\"java.util.ArrayList\",[";
        StringBuilder sb = new StringBuilder(depth * (open.length() + 2));
        for (int i = 0; i < depth; ++i) {
            sb.append(open);
        }
        for (int i = 0; i < depth; ++i) {
            sb.append("]]");
        }
        return sb.toString();
    }

    private static int _listDepth(Object ob)
    {
        int depth = 0;
        while (ob instanceof List<?>) {
            ++depth;
            List<?> list = (List<?>) ob;
            ob = list.isEmpty() ? null : list.get(0);
        }
        return depth;
    }

    private static int _mapDepth(Object ob)
    {
        int depth = 0;
        while (ob instanceof Map<?,?>) {
            ++depth;
            ob = ((Map<?,?>) ob).get("a");
        }
        return depth;
    }

    private static int _nodeDepth(JsonNode n)
    {
        int depth = 0;
        while (n != null && n.isContainerNode()) {
            ++depth;
            n = n.isArray() ? n.get(0) : n.get("a");
        }
        return depth;
    }
}
