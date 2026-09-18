package org.slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * A logger that renders what it is given and complains when it cannot.
 *
 * A log line with the wrong number of {} placeholders is a bug that hides until the one moment the
 * line fires — usually the moment someone is trying to work out what went wrong. Rendering every
 * line during verification, and counting the placeholders against the arguments, turns that into a
 * failure at check time instead.
 */
public class RecordingLogger implements Logger {

    public static final List<String> LINES = new ArrayList<>();
    public static final List<String> PROBLEMS = new ArrayList<>();

    public static void reset() {
        LINES.clear();
        PROBLEMS.clear();
    }

    @Override public void info(String format, Object... arguments) { record("INFO", format, arguments); }
    @Override public void warn(String format, Object... arguments) { record("WARN", format, arguments); }
    @Override public void error(String format, Object... arguments) { record("ERROR", format, arguments); }

    @Override
    public void error(String message, Throwable cause) {
        if (count(message) != 0) {
            PROBLEMS.add("ERROR: " + count(message) + " placeholders but a throwable and no arguments: " + message);
        }
        LINES.add("ERROR " + message + " (" + cause + ")");
    }

    private void record(String level, String format, Object[] arguments) {
        int placeholders = count(format);
        if (placeholders != arguments.length) {
            PROBLEMS.add(level + ": " + placeholders + " placeholders but " + arguments.length
                    + " arguments: " + format);
        }

        StringBuilder rendered = new StringBuilder();
        int next = 0;
        for (int i = 0; i < format.length(); i++) {
            if (i + 1 < format.length() && format.charAt(i) == '{' && format.charAt(i + 1) == '}') {
                rendered.append(next < arguments.length ? String.valueOf(arguments[next++]) : "{}");
                i++;
            } else {
                rendered.append(format.charAt(i));
            }
        }
        LINES.add(level + " " + rendered);
    }

    private static int count(String format) {
        int found = 0;
        for (int i = 0; i + 1 < format.length(); i++) {
            if (format.charAt(i) == '{' && format.charAt(i + 1) == '}') {
                found++;
                i++;
            }
        }
        return found;
    }
}
