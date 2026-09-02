package com.fabriciojunio.codereview.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The trace span each database query produces.
 *
 * <p>The span name is what matters most. A tracing dashboard groups by name,
 * and a name carrying the whole statement makes every execution a group of one,
 * which erases the question the tool exists to answer: which query is slow in
 * general.
 */
@DisplayName("Query trace")
class QueryTraceTest {

    private final TestTracing.Setup setup = TestTracing.build();
    private final QueryTrace trace = new QueryTrace(setup.tracer());

    @ParameterizedTest
    @CsvSource({
            "'select r.status from reviews r where r.id = ?',        db select reviews",
            "'insert into review_results (id, summary) values (?,?)', db insert review_results",
            "'update reviews set status = ? where id = ?',            db update reviews",
            "'delete from analysis_metrics where created_at < ?',     db delete analysis_metrics",
    })
    @DisplayName("the span name is the operation and the table, so the dashboard can group")
    void groupableName(String statement, String expected) {
        assertThat(QueryTrace.spanName(statement)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "'SELECT * FROM REVIEWS WHERE ID = ?', db select reviews",
            "'  select x from review_tags  ',      db select review_tags",
    })
    @DisplayName("upper case and surrounding space do not change the grouping")
    void nameIgnoresCaseAndSpace(String statement, String expected) {
        assertThat(QueryTrace.spanName(statement)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a statement outside the four known ones still gets a span, with a generic name")
    void unknownStatement() {
        assertThat(QueryTrace.spanName("create index idx on table (col)"))
                .isEqualTo("db statement");
    }

    @Test
    @DisplayName("a select with no from does not blow up, returns the unknown marker")
    void selectWithoutFrom() {
        assertThat(QueryTrace.spanName("select 1")).isEqualTo("db select ?");
    }

    @Test
    @DisplayName("the stored text is the prepared statement, without parameter values")
    void doesNotStoreParameterValues() {
        String statement = "select * from users where email = ?";

        assertThat(QueryTrace.summarise(statement))
                .as("a span leaves the application; the reviewed source and the user e-mail should not ride along")
                .isEqualTo(statement)
                .contains("?");
    }

    @Test
    @DisplayName("an enormous ORM statement is cut, so it does not blow up the span")
    void enormousStatementIsCut() {
        String enormous = "select " + "column_with_a_long_name, ".repeat(200) + "x from reviews";

        String stored = QueryTrace.summarise(enormous);

        assertThat(stored).hasSizeLessThan(enormous.length());
        assertThat(stored).endsWith("...");
    }

    @Test
    @DisplayName("a line break becomes a space, or the dashboard shows a column of text")
    void lineBreakBecomesSpace() {
        String statement = "select *\n  from reviews\n where id = ?";

        assertThat(QueryTrace.summarise(statement))
                .doesNotContain("\n")
                .isEqualTo("select * from reviews where id = ?");
    }

    @Test
    @DisplayName("wraps the DataSource and lets every other bean through untouched")
    void wrapsOnlyTheDataSource() {
        DataSource original = mock(DataSource.class);
        Object other = "any other bean";

        assertThat(trace.postProcessAfterInitialization(original, "dataSource"))
                .as("without wrapping, no query shows up on the trace")
                .isNotSameAs(original);
        assertThat(trace.postProcessAfterInitialization(other, "other"))
                .as("wrapping something that is not a DataSource would break the whole context")
                .isSameAs(other);
    }

    @Test
    @DisplayName("what wraps the DataSource is still a DataSource")
    void theWrapperIsStillADataSource() {
        Object wrapped = trace.postProcessAfterInitialization(mock(DataSource.class), "dataSource");

        assertThat(wrapped).isInstanceOf(DataSource.class);
    }
}
