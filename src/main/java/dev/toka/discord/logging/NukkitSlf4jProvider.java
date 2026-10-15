package dev.toka.discord.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sends the logs of JDA (which logs through SLF4J) to the Nukkit server log.
 * Without an SLF4J implementation JDA prints four warnings and falls back to its own logger.
 */
public final class NukkitSlf4jProvider implements SLF4JServiceProvider {
    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final IMarkerFactory markers = new BasicMarkerFactory();
    private final MDCAdapter mdc = new NOPMDCAdapter();
    private final ILoggerFactory factory = name -> this.loggers.computeIfAbsent(name, NukkitSlf4jLogger::new);

    @Override
    public ILoggerFactory getLoggerFactory() {
        return this.factory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return this.markers;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return this.mdc;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.99";
    }

    @Override
    public void initialize() {
        // nothing to prepare
    }
}
