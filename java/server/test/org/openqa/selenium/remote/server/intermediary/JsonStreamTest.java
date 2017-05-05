package org.openqa.selenium.remote.server.intermediary;

import static org.junit.Assert.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

public class JsonStreamTest {

  @Test
  public void readASimpleObject() throws IOException {
    assertSerialization(ImmutableMap.of("hello", "world"));
  }

  @Test
  public void readArray() throws IOException {
   assertSerialization(ImmutableList.of("cheese", "peas", "sausages"));
  }

  @Test
  public void canReadBooleanValues() throws IOException {
    assertSerialization(ImmutableMap.of("cake", false));
  }

  @Test
  public void canReadNumbers() throws IOException {
    assertSerialization(ImmutableMap.of("answer", 42, "almost-pi", 3.1));
  }

  @Test
  public void handlesNestedObjects() throws IOException {
    assertSerialization(ImmutableMap.of("cheeses", ImmutableMap.of("cake", true, "peas", false)));
  }

  @Test
  public void canStreamNulls() throws IOException {
    Map<String, Object> map = new HashMap<>();
    map.put("futility", null);
    assertSerialization(map);
  }

  private void assertSerialization(Object toSerialize) throws IOException {
    // This looks weird. The problem is that we don't really know what the expected output looks
    // like. The simple approach (run the object through "toJson" once) will mean that ints are left
    // as ints. The problem is that when parsing this json, Gson doesn't differentiate between
    // "int", "long", "double", or "float". We choose "double" because that's what Gson does by
    // default. So we stringify, destringify, and then stringify again to get the expected output.
    Gson gson = new Gson();
    Object throughSerialization = gson.fromJson(gson.toJson(toSerialize), Object.class);
    String expected = gson.toJson(throughSerialization);

    StringWriter seen = new StringWriter();
    try (JsonReader in = new JsonReader(new StringReader(expected));
         JsonWriter out = new JsonWriter(seen)) {
      JsonStream.spool(in, out);
    }

    assertEquals(expected, seen.toString());
  }
}