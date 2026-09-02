package com.fabriciojunio.codereview.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

/**
 * One trace span per database query.
 *
 * <p>Closes the gap {@link MessageTrace} leaves open: the trace says the
 * processing step took eight seconds, not which query inside it did. With this,
 * the review timeline shows the trips to the database nested inside each step.
 *
 * <p>It wraps the {@code DataSource} instead of instrumenting repository by
 * repository, which also catches what JPA emits on its own: lazy loading,
 * version checks, and the classic case of one query turning into N. That last
 * one is the strongest reason for this class to exist — an N+1 only becomes
 * obvious when someone sees the repeated statements lined up on a timeline, and
 * this project has entity graphs (a review, its results, its tags) that are
 * exactly the shape that produces them.
 *
 * <h2>What does not go into the span</h2>
 * Parameter values. A span is telemetry: it leaves the application and is
 * stored somewhere else. The source code being reviewed, and the e-mail of
 * whoever asked for the review, have no reason to travel there. The statement
 * text is enough to identify the query, and that is what the library calls the
 * prepared form.
 */
@Component
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class QueryTrace implements BeanPostProcessor {

    /** Cap on the text stored in the span. ORM-generated SQL is enormous. */
    private static final int TEXT_LIMIT = 500;

    private final Tracer tracer;

    public QueryTrace(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Swaps the context's {@code DataSource} for one that reports every query.
     *
     * <p>A bean post-processor rather than a new {@code @Bean}, because the
     * DataSource is built by Spring Boot from the properties, and rebuilding it
     * here would mean repeating all of that configuration, connection pool
     * included.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource original)) {
            return bean;
        }
        return ProxyDataSourceBuilder.create(original)
                .name("codereview")
                .listener(perQuery())
                .methodListener(quiet())
                .build();
    }

    private QueryExecutionListener perQuery() {
        return new QueryExecutionListener() {

            @Override
            public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
                // Nothing here: the span is opened and closed afterwards, with
                // the duration the library measured itself. Opening it earlier
                // would mean holding the span per thread, and the connection can
                // move between threads.
            }

            @Override
            public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
                if (queries.isEmpty()) {
                    return;
                }
                String statement = queries.get(0).getQuery();

                Span span = tracer.nextSpan().name(spanName(statement));
                span.tag("db.system", "postgresql");
                span.tag("db.statement", summarise(statement));
                span.tag("db.duration_ms", String.valueOf(execution.getElapsedTime()));
                if (queries.size() > 1) {
                    span.tag("db.statements_in_batch", String.valueOf(queries.size()));
                }
                if (execution.getThrowable() != null) {
                    span.error(execution.getThrowable());
                }
                span.start().end();
            }
        };
    }

    /**
     * The library also reports commit, rollback and connection opening.
     *
     * <p>Left out: in a flow with many short transactions that triples the
     * number of spans, and the dashboard turns into a wall where the slow query
     * disappears in the middle.
     */
    private MethodExecutionListener quiet() {
        return new MethodExecutionListener() {
            @Override
            public void beforeMethod(MethodExecutionContext context) {
            }

            @Override
            public void afterMethod(MethodExecutionContext context) {
            }
        };
    }

    /**
     * The span name is the operation and the table, not the whole statement.
     *
     * <p>Dashboards group by name. With the whole statement in the name, every
     * query becomes a group of one, and the question "which query is slow in
     * general" stops having an answer.
     */
    static String spanName(String statement) {
        String clean = statement.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("select")) {
            return "db select " + tableAfter(clean, " from ");
        }
        if (clean.startsWith("insert")) {
            return "db insert " + tableAfter(clean, " into ");
        }
        if (clean.startsWith("update")) {
            return "db update " + tableAfter(clean, "update ");
        }
        if (clean.startsWith("delete")) {
            return "db delete " + tableAfter(clean, " from ");
        }
        return "db statement";
    }

    private static String tableAfter(String statement, String marker) {
        int start = statement.indexOf(marker);
        if (start < 0) {
            return "?";
        }
        String rest = statement.substring(start + marker.length()).trim();
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    static String summarise(String statement) {
        String oneLine = statement.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= TEXT_LIMIT
                ? oneLine
                : oneLine.substring(0, TEXT_LIMIT) + "...";
    }
}
