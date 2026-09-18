package org.slf4j;
public interface Logger {
    void error(String format, Object arg1, Object arg2);
    void error(String message, Throwable cause);
}
