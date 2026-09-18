package org.slf4j;
public interface Logger {
    void error(String format, Object... arguments);
    void error(String message, Throwable cause);
    void warn(String format, Object... arguments);
    void warn(String message, Throwable cause);
}
