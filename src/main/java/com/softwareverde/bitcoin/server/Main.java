package com.softwareverde.bitcoin.server;

import com.softwareverde.bitcoin.server.socket.SocketConnectionManager;
import com.softwareverde.bitcoin.server.socket.message.ProtocolMessage;
import com.softwareverde.bitcoin.server.socket.message.VersionPayload;
import com.softwareverde.bitcoin.util.BitcoinUtil;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public class Main {
    protected final Configuration _configuration;
    protected final Environment _environment;

    protected void _exitFailure() {
        System.exit(1);
    }

    protected void _printError(final String errorMessage) {
        System.err.println(errorMessage);
    }

    protected void _printUsage() {
        _printError("Usage: java -jar " + System.getProperty("java.class.path") + " <configuration-file>");
    }

    protected Configuration _loadConfigurationFile(final String configurationFilename) {
        final File configurationFile =  new File(configurationFilename);
        if (! configurationFile.isFile()) {
            _printError("Invalid configuration file.");
            _exitFailure();
        }

        return new Configuration(configurationFile);
    }

    protected void _printMemoryUsage() {
        final Runtime runtime = Runtime.getRuntime();
        final Long maxMemory = runtime.maxMemory();
        final Long freeMemory = runtime.freeMemory();
        final Long reservedMemory = runtime.totalMemory();
        final Long currentMemoryUsage = reservedMemory - freeMemory;

        final Long toMegabytes = 1048576L;
        System.out.print("\33[1A\33[2K");
        System.out.println((currentMemoryUsage/toMegabytes) +"mb / "+ (maxMemory/toMegabytes) +"mb ("+ String.format("%.2f", (currentMemoryUsage.floatValue() / maxMemory.floatValue() * 100.0F)) +"%)");
    }

    protected void _checkForDeadlockedThreads() {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final long[] threadIds = bean.findDeadlockedThreads(); // Returns null if no threads are deadlocked.

        if (threadIds != null) {
            final ThreadInfo[] threadInfo = bean.getThreadInfo(threadIds);

            for (final ThreadInfo info : threadInfo) {
                final StackTraceElement[] stack = info.getStackTrace();
                for (final StackTraceElement stackTraceElement : stack) {
                    System.out.println(stackTraceElement);
                }
            }
        }
    }

    public Main(final String[] commandLineArguments) {
        if (commandLineArguments.length != 1) {
            _printUsage();
            _exitFailure();
        }

        final String configurationFilename = commandLineArguments[0];

        _configuration = _loadConfigurationFile(configurationFilename);
        _environment = new Environment();

        final Configuration.ServerProperties serverProperties = _configuration.getServerProperties();
    }

    public void loop() {
        System.out.println("[Server Online]");

        final SocketConnectionManager socketConnectionManager = new SocketConnectionManager("btc.softwareverde.com", 8333);
        socketConnectionManager.setMessageReceivedCallback(new SocketConnectionManager.MessageReceivedCallback() {
            @Override
            public void onMessageReceived(final byte[] message) {
                System.out.println(BitcoinUtil.toHexString(message));
            }
        });
        socketConnectionManager.setOnConnectCallback(new Runnable() {
            @Override
            public void run() {
                System.out.println("Socket connected.");

                final ProtocolMessage protocolMessage = new ProtocolMessage(ProtocolMessage.Command.SUBMIT_VERSION);
                protocolMessage.setPayload((new VersionPayload()).getBytes());
                System.out.println(BitcoinUtil.toHexString(protocolMessage.serializeAsLittleEndian()));
                socketConnectionManager.queueMessage(protocolMessage);
            }
        });
        socketConnectionManager.setOnDisconnectCallback(new Runnable() {
            @Override
            public void run() {
                System.out.println("Socket disconnected.");
            }
        });
        socketConnectionManager.startConnectionThread();

        while (true) {
            try { Thread.sleep(500); } catch (final Exception e) { }

            if ((Math.random() * 777) % 1000 < 10) {
                System.gc();
            }

            // _printMemoryUsage();
            // _checkForDeadlockedThreads();
        }
    }

    public static void main(final String[] commandLineArguments) {
        final Main application = new Main(commandLineArguments);
        application.loop();
    }
}