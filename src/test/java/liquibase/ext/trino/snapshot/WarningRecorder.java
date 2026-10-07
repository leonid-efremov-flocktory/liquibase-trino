package liquibase.ext.trino.snapshot;

import liquibase.Scope;
import java.util.logging.Level;
import liquibase.logging.core.AbstractLogger;
import liquibase.logging.core.JavaLogService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Captures the warnings Liquibase logs while a command runs.
 *
 * <p>A skipped object is silent by design — that is the whole point of skipping it — so the warning
 * is the only trace of it. Asserting on it is what keeps "skip quietly" from turning into "skip
 * without telling anyone", which is how a generated changelog quietly loses a table.
 *
 * <p>Usage: {@code WarningRecorder.record(() -> command.execute())}. The body runs in a child
 * {@link Scope} carrying this log service, so nothing outside the block is affected and the warnings
 * come back with the result:
 *
 * <pre>
 * WarningRecorder.Result&lt;String&gt; recorded = WarningRecorder.record(() -&gt; snapshotJson(db));
 * assertTrue(recorded.mentions("broken_view"));
 * </pre>
 */
final class WarningRecorder extends JavaLogService {

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    /** What a recorded run produced: its value, and what it logged on the way. */
    record Result<T>(T value, List<String> warnings) {

        /** Whether any warning mentions the given fragment. */
        boolean mentions(String fragment) {
            return warnings.stream().anyMatch(w -> w.contains(fragment));
        }

        /** How many warnings mention the given fragment. */
        long count(String fragment) {
            return warnings.stream().filter(w -> w.contains(fragment)).count();
        }
    }

    /** A command that may throw anything the tests allow. */
    @FunctionalInterface
    interface Body<T> {
        T run() throws Exception;
    }

    private WarningRecorder() {
    }

    /** Runs {@code body} with this log service in scope and returns what it produced. */
    static <T> Result<T> record(Body<T> body) throws Exception {
        WarningRecorder recorder = new WarningRecorder();
        T value = Scope.child(Map.of(Scope.Attr.logService.name(), recorder), () -> body.run());
        synchronized (recorder.warnings) {
            return new Result<>(value, new ArrayList<>(recorder.warnings));
        }
    }

    @Override
    public liquibase.logging.Logger getLog(Class clazz) {
        return new RecordingLogger();
    }

    /** Only warnings are kept; the rest of Liquibase's chatter is not what these tests are about. */
    private final class RecordingLogger extends AbstractLogger {

        @Override
        public void log(Level level, String message, Throwable e) {
            if (level.intValue() == Level.WARNING.intValue() && message != null) {
                warnings.add(message);
            }
        }
    }
}