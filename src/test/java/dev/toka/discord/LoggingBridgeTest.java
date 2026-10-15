package dev.toka.discord;

import dev.toka.discord.logging.NukkitSlf4jProvider;
import org.junit.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import static org.junit.Assert.*;

public class LoggingBridgeTest {
    @Test
    public void providerHandsOutLoggersThatAnswerInfoAndAbove() {
        NukkitSlf4jProvider provider = new NukkitSlf4jProvider();
        provider.initialize();
        ILoggerFactory factory = provider.getLoggerFactory();
        Logger logger = factory.getLogger("net.dv8tion.jda.api.JDA");
        assertSame(logger, factory.getLogger("net.dv8tion.jda.api.JDA"));
        assertTrue(logger.isInfoEnabled());
        assertTrue(logger.isWarnEnabled());
        assertTrue(logger.isErrorEnabled());
        assertFalse(logger.isDebugEnabled());
        assertFalse(logger.isTraceEnabled());
        // no server in the test; the line goes to System.err and must not throw
        logger.warn("hello {}", "world");
        logger.error("failed", new IllegalStateException("boom"));
    }

    @Test
    public void providerIsRegisteredForServiceLoader() {
        boolean found = false;
        for (org.slf4j.spi.SLF4JServiceProvider provider : java.util.ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider.class)) {
            found |= provider instanceof NukkitSlf4jProvider;
        }
        assertTrue(found);
    }
}
