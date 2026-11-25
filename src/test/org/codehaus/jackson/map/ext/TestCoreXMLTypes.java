package org.codehaus.jackson.map.ext;

import javax.xml.datatype.*;
import javax.xml.namespace.QName;

import org.codehaus.jackson.map.*;
import org.codehaus.jackson.map.ext.CoreXMLDeserializers;

/**
 * Core XML types (javax.xml) are considered "external" (or more precisely "optional")
 * since some Java(-like) platforms do not include them: specifically, Google AppEngine
 * and Android seem to skimp on their inclusion. As such, they are dynamically loaded
 * only as needed, and need bit special handling.
 * 
 * @author tatu
 */
public class TestCoreXMLTypes
    extends BaseMapTest
{
    /*
    /**********************************************************
    /* Serializer tests
    /**********************************************************
     */

    public void testQNameSer() throws Exception
    {
        QName qn = new QName("http://abc", "tag", "prefix");
        assertEquals(quote(qn.toString()), serializeAsString(qn));
    }

    public void testDurationSer() throws Exception
    {
        DatatypeFactory dtf = DatatypeFactory.newInstance();
        // arbitrary value
        Duration dur = dtf.newDurationDayTime(false, 15, 19, 58, 1);
        assertEquals(quote(dur.toString()), serializeAsString(dur));
    }

    public void testXMLGregorianCalendarSerAndDeser() throws Exception
    {
        DatatypeFactory dtf = DatatypeFactory.newInstance();
        XMLGregorianCalendar cal = dtf.newXMLGregorianCalendar
            (1974, 10, 10, 18, 15, 17, 123, 0);
        /* Due to [JACKSON-308], 1.6 will use configurable Date serialization;
         * and it defaults to using timestamp. So let's try couple of combinations.
         */
        ObjectMapper mapper = new ObjectMapper();
        long timestamp = cal.toGregorianCalendar().getTimeInMillis();
        String numStr = String.valueOf(timestamp);
        assertEquals(numStr, mapper.writeValueAsString(cal));

        // [JACKSON-403] Needs to come back ok as well:
        XMLGregorianCalendar calOut = mapper.readValue(numStr, XMLGregorianCalendar.class);
        assertNotNull(calOut);
        assertEquals(timestamp, calOut.toGregorianCalendar().getTimeInMillis());
        
        // and then textual variant
        mapper.configure(SerializationConfig.Feature.WRITE_DATES_AS_TIMESTAMPS, false);
        // this is ALMOST same as default for XMLGregorianCalendar... just need to unify Z/+0000
        String exp = cal.toXMLFormat();
        String act = mapper.writeValueAsString(cal);
        act = act.substring(1, act.length() - 1); // remove quotes
        exp = removeZ(exp);
        act = removeZ(act);
        assertEquals(exp, act);
    }

    private String removeZ(String dateStr) {
        if (dateStr.endsWith("Z")) {
            return dateStr.substring(0, dateStr.length()-1);
        }
        if (dateStr.endsWith("+0000")) {
            return dateStr.substring(0, dateStr.length()-5);
        }
        return dateStr;
    }
    
    /*
    /**********************************************************
    /* Deserializer tests
    /**********************************************************
     */
    
    // First things first: must be able to load the deserializers...
    public void testDeserializerLoading()
    {
        new CoreXMLDeserializers.DurationDeserializer();
        new CoreXMLDeserializers.GregorianCalendarDeserializer();
        new CoreXMLDeserializers.QNameDeserializer();
    }

    public void testQNameDeser() throws Exception
    {
        QName qn = new QName("http://abc", "tag", "prefix");
        String qstr = qn.toString();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("Should deserialize to equal QName (exp serialization: '"+qstr+"')",
                     qn, mapper.readValue(quote(qstr), QName.class));
    }

    public void testCalendarDeser() throws Exception
    {
        DatatypeFactory dtf = DatatypeFactory.newInstance();
        XMLGregorianCalendar cal = dtf.newXMLGregorianCalendar
            (1974, 10, 10, 18, 15, 17, 123, 0);
        String exp = cal.toXMLFormat();
        assertEquals("Should deserialize to equal XMLGregorianCalendar ('"+exp+"')", cal,
                new ObjectMapper().readValue(quote(exp), XMLGregorianCalendar.class));
    }

    public void testDurationDeser() throws Exception
    {
        DatatypeFactory dtf = DatatypeFactory.newInstance();
        // arbitrary value, like... say, 27d5h15m59s
        Duration dur = dtf.newDurationDayTime(true, 27, 5, 15, 59);
        String exp = dur.toString();
        assertEquals("Should deserialize to equal Duration ('"+exp+"')", dur,
                new ObjectMapper().readValue(quote(exp), Duration.class));
    }

    /*
    /**********************************************************
    /* [databind#6127]: value length limit
    /**********************************************************
     */

    public void testDurationLengthLimit() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();

        // A Duration exactly at the limit is still accepted
        String maxDuration = "P" + _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH - 2) + "Y";
        assertNotNull(mapper.readValue(quote(maxDuration), Duration.class));

        // One character over the limit is rejected
        String bigDuration = "P" + _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH - 1) + "Y";
        try {
            mapper.readValue(quote(bigDuration), Duration.class);
            fail("Should not pass: expected JsonMappingException for oversized Duration");
        } catch (JsonMappingException e) {
            verifyException(e, "not a valid textual representation");
        }

        // Way over the limit (would take long to parse without the check)
        String hugeDuration = "P" + _repeat('9', 200000) + "Y";
        try {
            mapper.readValue(quote(hugeDuration), Duration.class);
            fail("Should not pass: expected JsonMappingException for oversized Duration");
        } catch (JsonMappingException e) {
            verifyException(e, "not a valid textual representation");
        }

        // Oversized fractional seconds are rejected as well
        String bigSeconds = "PT0." + _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH) + "S";
        try {
            mapper.readValue(quote(bigSeconds), Duration.class);
            fail("Should not pass: expected JsonMappingException for oversized Duration");
        } catch (JsonMappingException e) {
            verifyException(e, "not a valid textual representation");
        }

        // A normal-length Duration should still work
        Duration dur = mapper.readValue(quote("P1Y2M3D"), Duration.class);
        assertEquals(1, dur.getYears());
        assertEquals(2, dur.getMonths());
        assertEquals(3, dur.getDays());
    }

    public void testXMLGregorianCalendarLengthLimit() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();

        // Time-only value (valid xs:time lexical form) with oversized fractional seconds
        String bigTime = "00:00:00." + _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH);
        try {
            mapper.readValue(quote(bigTime), XMLGregorianCalendar.class);
            fail("Should not pass: expected JsonMappingException for oversized XMLGregorianCalendar");
        } catch (JsonMappingException e) {
            verifyException(e, "exceeds the maximum allowed");
        }

        // ISO-8601 date-time with oversized fractional seconds
        String bigFraction = "2023-06-15T12:00:00." + _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH);
        try {
            mapper.readValue(quote(bigFraction), XMLGregorianCalendar.class);
            fail("Should not pass: expected JsonMappingException for oversized XMLGregorianCalendar");
        } catch (JsonMappingException e) {
            verifyException(e, "exceeds the maximum allowed");
        }

        // ISO-8601 date-time with an oversized year
        String bigDateTime = _repeat('9', CoreXMLDeserializers.MAX_VALUE_LENGTH) + "-01-01T00:00:00";
        try {
            mapper.readValue(quote(bigDateTime), XMLGregorianCalendar.class);
            fail("Should not pass: expected JsonMappingException for oversized date-time value");
        } catch (JsonMappingException e) {
            verifyException(e, "exceeds the maximum allowed");
        }

        // A normal-length XMLGregorianCalendar should still work
        XMLGregorianCalendar cal = mapper.readValue(quote("2023-06-15T12:00:00"),
                XMLGregorianCalendar.class);
        assertEquals(2023, cal.getYear());
        assertEquals(6, cal.getMonth());
        assertEquals(15, cal.getDay());
    }

    // QName has no numeric components, so the length limit must not apply to it
    public void testQNameNotLengthLimited() throws Exception
    {
        String localPart = _repeat('a', CoreXMLDeserializers.MAX_VALUE_LENGTH + 100);
        QName qn = new ObjectMapper().readValue(quote(localPart), QName.class);
        assertEquals(localPart, qn.getLocalPart());
    }

    private static String _repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; ++i) {
            sb.append(c);
        }
        return sb.toString();
    }
}
