package org.openqa.selenium.remote.server.intermediary;

import com.google.common.base.Preconditions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

class TeeInputStream extends InputStream {

  private final InputStream source;
  private final OutputStream[] sinks;

  public TeeInputStream(InputStream source, OutputStream... sinks) {
    this.source = Preconditions.checkNotNull(source);
    this.sinks = sinks;
  }

  @Override
  public int read() throws IOException {
    int c = source.read();
    if (c != -1) {
      for (OutputStream sink : sinks) {
        sink.write(c);
      }
    }

    return c;
  }

  @Override
  public void close() throws IOException {
    source.close();
    for (OutputStream sink : sinks) {
      sink.close();
    }
  }
}
