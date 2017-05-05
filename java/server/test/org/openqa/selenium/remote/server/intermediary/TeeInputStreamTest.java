package org.openqa.selenium.remote.server.intermediary;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.google.gson.stream.JsonReader;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;


public class TeeInputStreamTest {

  @Test
  public void shouldDuplicateStreams() throws IOException {
    String expected = "{\"key\": \"value\"}";
    ByteArrayInputStream in = new ByteArrayInputStream(expected.getBytes(UTF_8));

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    InputStream tee = new TeeInputStream(in, bos);

    JsonReader reader = new JsonReader(new InputStreamReader(tee, UTF_8));


    reader.beginObject();
    assertEquals("key", reader.nextName());
    reader.skipValue();
    reader.endObject();

    assertEquals(expected, new String(bos.toByteArray(), UTF_8));
  }
}