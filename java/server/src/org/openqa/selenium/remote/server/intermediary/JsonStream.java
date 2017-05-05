package org.openqa.selenium.remote.server.intermediary;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class JsonStream {

  public static void spool(JsonReader in, JsonWriter out) throws IOException {
    while (in.hasNext()) {
      switch (in.peek()) {
        case BEGIN_ARRAY:
          in.beginArray();
          out.beginArray();
          while (in.hasNext()) {
            spool(in, out);
          }
          in.endArray();
          out.endArray();
          return;

        case BEGIN_OBJECT:
          in.beginObject();
          out.beginObject();
          while (in.hasNext()) {
            spool(in, out);
          }
          in.endObject();
          out.endObject();
          return;

        case BOOLEAN:
          out.value(in.nextBoolean());
          return;

        case END_DOCUMENT:
          return;

        case NAME:
          out.name(in.nextName());
          return;

        case NULL:
          in.nextNull();
          out.nullValue();
          return;

        case NUMBER:
          out.value(in.nextDouble());
          return;

        case STRING:
          out.value(in.nextString());
          return;

        default:
          throw new RuntimeException("I have no idea what I am doing: " + in.peek());
      }
    }
  }

}
