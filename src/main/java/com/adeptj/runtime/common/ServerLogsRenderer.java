package com.adeptj.runtime.common;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListener;
import org.apache.commons.io.input.TailerListenerAdapter;

import javax.websocket.Session;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Tails server logs.
 *
 * @author Rakesh.Kumar, AdeptJ.
 */
public class ServerLogsRenderer {

    private static final int DELAY_MILLIS = 1000;

    private final File logFile;

    private final Session session;

    private Tailer tailer;

    public ServerLogsRenderer(File file, Session session) {
        this.logFile = file;
        this.session = session;
    }

    public void initRenderer() {
        this.tailer = new Tailer(this.logFile, this.createListener(), DELAY_MILLIS, true);
        Thread thread = new Thread(() -> {
            try {
                tailer.run();
            } catch (Exception ex) {
                this.session.getAsyncRemote().sendText(ex.toString());
            }
        });
        thread.start();
    }

    public void stopRenderer() {
        this.tailer.stop();
    }

    private TailerListener createListener() {

        return new TailerListenerAdapter() {

            @Override
            public void fileRotated() {
                // ignore, just keep tailing
            }

            @Override
            public void handle(String line) {
                session.getAsyncRemote().sendText(line);
            }

            @Override
            public void fileNotFound() {
                session.getAsyncRemote().sendText(new FileNotFoundException(logFile.toString()).toString());
            }

            @Override
            public void handle(Exception ex) {
                session.getAsyncRemote().sendText(ex.toString());
            }
        };
    }
}
