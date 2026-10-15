package dev.toka.discord.logging;

import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.BiConsumer;

/** One SLF4J logger that forwards INFO, WARN and ERROR; DEBUG and TRACE are dropped. */
final class NukkitSlf4jLogger extends LegacyAbstractLogger {
    /** Where the lines go: (level, text). Replaced in tests; by default the Nukkit server log. */
    static volatile BiConsumer<Level, String> sink = NukkitSlf4jLogger::toServerLog;

    NukkitSlf4jLogger(String name) {
        this.name = name;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String message, Object[] arguments, Throwable error) {
        if (!this.enabled(level)) {
            return;
        }
        StringBuilder text = new StringBuilder("[").append(shortName(this.name)).append("] ")
                .append(MessageFormatter.arrayFormat(message, arguments).getMessage());
        if (error != null) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            text.append('\n').append(trace.toString().stripTrailing());
        }
        sink.accept(level, text.toString());
    }

    private boolean enabled(Level level) {
        return level == Level.INFO || level == Level.WARN || level == Level.ERROR;
    }

    @Override public boolean isTraceEnabled() { return false; }
    @Override public boolean isDebugEnabled() { return false; }
    @Override public boolean isInfoEnabled() { return true; }
    @Override public boolean isWarnEnabled() { return true; }
    @Override public boolean isErrorEnabled() { return true; }

    private static String shortName(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? String.valueOf(name) : name.substring(dot + 1);
    }

    private static void toServerLog(Level level, String text) {
        cn.nukkit.Server server = cn.nukkit.Server.getInstance();
        if (server == null) {
            System.err.println(level + " " + text);
            return;
        }
        switch (level) {
            case ERROR -> server.getLogger().error(text);
            case WARN -> server.getLogger().warning(text);
            default -> server.getLogger().info(text);
        }
    }
}
