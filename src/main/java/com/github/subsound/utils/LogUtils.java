package com.github.subsound.utils;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

public class LogUtils {
    public static void setRootLogLevel(String level) {
        final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        final Logger logger = loggerContext.exists(org.slf4j.Logger.ROOT_LOGGER_NAME); // give it your logger name
        final Level newLevel = Level.toLevel(level, null); // give it your log level
        logger.setLevel(newLevel);
    }
}
