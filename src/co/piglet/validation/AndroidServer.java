package co.piglet.validation;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AndroidServer implements Runnable {
    private ServerSocket server;
    private Executor executor;
    private boolean running;

    public AndroidServer() {
        try {
            executor = Executors.newCachedThreadPool();
            server = new ServerSocket(55555);
            running = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        running = true;

        while (running) {
            try {
                Socket client = server.accept();
                AndroidCommandProcessor processor = new AndroidCommandProcessor(client);
                executor.execute(processor);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void shutdown() {
        running = false;

        try {
            server.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
