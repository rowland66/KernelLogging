package org.rowland.jinix.kernellogger;

import org.rowland.jinix.exec.InvalidExecutableException;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.logger.LogServer;
import org.rowland.jinix.naming.NameSpace;
import org.rowland.jinix.proc.ProcessManager;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.rmi.RemoteException;

/**
 * Jinix kernel logging daemon. Daemon (generally started by init) that reads the
 * Jinix kernel log from the LogServer and writes somewhere. Usually a file.
 */
public class KernelLoggerDaemon {

    private static final int LOG_BATCH_SIZE = 50;
    private static boolean run = true;
    private static Thread mainThread;

    public static void main(String[] args) {
        try {

            if (!JinixRuntime.getRuntime().isForkChild()) {
                try {
                    int pid = JinixRuntime.getRuntime().fork();
                    if (pid > 0) {
                        System.out.println("Starting klogd with process ID: " + pid);
                        return;
                    } else {
                        throw new RuntimeException("fork return error");
                    }
                } catch (FileNotFoundException | InvalidExecutableException e) {
                    throw new RuntimeException(e);
                }
            }

            mainThread = Thread.currentThread();

            JinixRuntime.getRuntime().registerSignalHandler(new ProcessSignalHandler() {
                @Override
                public void handleSignal(ProcessManager.Signal signal) {
                    if (signal == ProcessManager.Signal.HANGUP) {
                        return;
                    }
                    if (signal == ProcessManager.Signal.TERMINATE) {
                        run = false;
                        mainThread.interrupt();
                    }
                }
            });

            NameSpace rootNameSpace = JinixRuntime.getRuntime().getRootNamespace();
            LogServer ls = (LogServer) rootNameSpace.lookup(LogServer.SERVER_NAME).remote;

            OutputStream os = Files.newOutputStream(Paths.get("/var/log/kernel.log"),
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            PrintStream logStream = new PrintStream(new BufferedOutputStream(os));

            try {
                while(run) {
                    String[] logs = ls.getLogs(LOG_BATCH_SIZE);
                    if (logs != null && logs.length > 0) {
                        for(String l : logs) {
                            logStream.print(l);
                        }
                        logStream.flush();
                    }

                    if (logs == null || logs.length < LOG_BATCH_SIZE) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            // continue since more likely, run has been set to false
                        }
                    }
                }
            } finally {
                logStream.close();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
