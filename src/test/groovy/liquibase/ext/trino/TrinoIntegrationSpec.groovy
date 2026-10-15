package liquibase.ext.trino

import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Tag

/**
 * Base for the integration specs that cover what the Liquibase Test Harness does not: the
 * {@code changelog} commands, the {@code snapshot}/{@code generate-changelog} failure handling and
 * the {@code diff} family (plan stage 8).
 *
 * <p>It exists for one reason: every integration test in this project is gated the same two ways —
 * it carries {@code @Tag("integration")} so the {@code unit} profile can drop it, and it skips
 * itself when the stand is down via the same reachability probe the Java classes used to spell out
 * with {@code @EnabledIf}. Repeating those two lines on each spec is the boilerplate this removes;
 * everything else (the fixture, the connection, the state under test) stays in the spec, because it
 * differs per class and a base that tried to cover it would hide which fixture a spec stands on.
 *
 * <p>{@code @Tag} is inherited by default; {@code @IgnoreIf} is not — it carries its own
 * {@code inherited} flag, and left at the default a subclass would run its {@code setupSpec}
 * against a down stand and error instead of skip. It is set explicitly here, and this is the one
 * place that has to remember to.
 *
 * <p>What it does not do: inherit {@link TrinoTestSupport}. That class is a final bag of static
 * helpers, so there is nothing to inherit — the specs call it directly, exactly as the Java tests
 * did. Keeping the helpers static and the base a plain gate is what lets the harness suites and
 * these specs share one support class without either owning the other.
 */
@Tag("integration")
@IgnoreIf(value = { !TrinoTestSupport.isReachable() }, inherited = true)
abstract class TrinoIntegrationSpec extends Specification {
}
