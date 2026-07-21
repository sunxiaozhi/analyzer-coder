package com.analyzercoder.application.intelligence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Package-local process builder that drains CLI output while the process runs. */
final class ProcessBuilder {
    private final java.lang.ProcessBuilder delegate;
    ProcessBuilder(List<String> command) { delegate = new java.lang.ProcessBuilder(command); }
    ProcessBuilder redirectErrorStream(boolean redirect) { delegate.redirectErrorStream(redirect); return this; }
    Map<String,String> environment() { return delegate.environment(); }
    Process start() throws IOException { return new DrainingProcess(delegate.start()); }

    private static final class DrainingProcess extends Process {
        private final Process delegate; private final ByteArrayOutputStream output = new ByteArrayOutputStream(); private final Thread reader;
        DrainingProcess(Process delegate) { this.delegate=delegate;reader=new Thread(()->{try{delegate.getInputStream().transferTo(output);}catch(IOException ignored){}},"codegraph-output-reader");reader.setDaemon(true);reader.start(); }
        @Override public OutputStream getOutputStream(){return delegate.getOutputStream();}
        @Override public InputStream getInputStream(){try{reader.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return new ByteArrayInputStream(output.toByteArray());}
        @Override public InputStream getErrorStream(){return delegate.getErrorStream();}
        @Override public int waitFor()throws InterruptedException{return delegate.waitFor();}
        @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return delegate.waitFor(timeout,unit);}
        @Override public int exitValue(){return delegate.exitValue();}
        @Override public void destroy(){delegate.destroy();}
        @Override public Process destroyForcibly(){delegate.destroyForcibly();return this;}
        @Override public boolean isAlive(){return delegate.isAlive();}
    }
}
