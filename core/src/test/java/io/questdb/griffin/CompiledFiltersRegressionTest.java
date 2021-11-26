/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.AbstractCairoTest;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.engine.functions.bind.BindVariableServiceImpl;
import io.questdb.griffin.engine.table.CompiledFilterRecordCursorFactory;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Misc;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(Parameterized.class)
public class CompiledFiltersRegressionTest extends AbstractCairoTest {

    private static final Log LOG = LogFactory.getLog(CompiledFiltersRegressionTest.class);
    private static final int N_SIMD_NO_TAIL = 128;
    private static final int N_SIMD_WITH_TAIL = N_SIMD_NO_TAIL + 3;

    @Parameterized.Parameters(name = "{0}")
    public static Object[] data() {
        return new Object[] { JitMode.SCALAR, JitMode.VECTORIZED };
    }

    @Parameterized.Parameter
    public JitMode jitMode;

    private static final StringSink jitSink = new StringSink();
    private static BindVariableService bindVariableService;
    private static SqlExecutionContext sqlExecutionContext;
    private static SqlCompiler compiler;

    @BeforeClass
    public static void setUpStatic() {
        AbstractCairoTest.setUpStatic();
        compiler = new SqlCompiler(engine);
        bindVariableService = new BindVariableServiceImpl(configuration);
        sqlExecutionContext = new SqlExecutionContextImpl(engine, 1)
                .with(
                        AllowAllCairoSecurityContext.INSTANCE,
                        bindVariableService,
                        null,
                        -1,
                        null);
        bindVariableService.clear();
    }

    @AfterClass
    public static void tearDownStatic() {
        AbstractCairoTest.tearDownStatic();
        compiler.close();
    }

    @Override
    @Before
    public void setUp() {
        super.setUp();
        bindVariableService.clear();
    }

    @Test
    public void testIntegerColumnConstantComparison() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte() i8," +
                " rnd_short() i16," +
                " rnd_int() i32," +
                " rnd_long() i64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64")
                .withComparisonOperator()
                .withAnyOf("-50", "0", "50");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testFloatColumnConstantComparison() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_float() f32," +
                " rnd_double() f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("f32", "f64")
                .withComparisonOperator()
                .withAnyOf("-50", "-25.5", "0", "0.0", "0.000", "25.5", "50");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testIntFloatColumnComparison() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte() i8," +
                " rnd_short() i16," +
                " rnd_int() i32," +
                " rnd_long() i64," +
                " rnd_float() f32," +
                " rnd_double() f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64")
                .withComparisonOperator()
                .withOptionalNegation().withAnyOf("f32", "f64");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testColumnArithmetics() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte() i8," +
                " rnd_short() i16," +
                " rnd_int() i32," +
                " rnd_long() i64," +
                " rnd_float() f32," +
                " rnd_double() f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64", "f32", "f64")
                .withArithmeticOperator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64", "f32", "f64")
                .withAnyOf(" = 0");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testConstantColumnArithmetics() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte() i8," +
                " rnd_short() i16," +
                " rnd_int() i32," +
                " rnd_long() i64," +
                " rnd_float() f32," +
                " rnd_double() f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64")
                .withArithmeticOperator()
                .withAnyOf("3", "-3.5")
                .withAnyOf(" + ")
                .withAnyOf("42.5", "-42")
                .withArithmeticOperator()
                .withOptionalNegation().withAnyOf("f32", "f64")
                .withAnyOf(" > 0");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testBooleanOperators() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte() i8," +
                " rnd_short() i16," +
                " rnd_double() f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNot().withAnyOf("i8 / 2 = 4")
                .withBooleanOperator()
                .withOptionalNot().withAnyOf("i16 < 0")
                .withBooleanOperator()
                .withOptionalNot().withAnyOf("f64 > 7.5");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testNullComparison() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte(-10, 10) i8," +
                " rnd_short(-10, 10) i16," +
                " rnd_int(-10, 10, 10) i32," +
                " rnd_long(-10, 10, 10) i64," +
                " rnd_float(10) f32," +
                " rnd_double(10) f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64")
                .withAnyOf(" = ", " <> ")
                .withAnyOf("null")
                .withBooleanOperator()
                .withOptionalNegation().withAnyOf("f32", "f64")
                .withAnyOf(" = ", " <> ")
                .withAnyOf("null");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testColumnArithmeticsNullComparison() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte(-10, 10) i8," +
                " rnd_short(-10, 10) i16," +
                " rnd_int(-10, 10, 10) i32," +
                " rnd_long(-10, 10, 10) i64," +
                " rnd_float(10) f32," +
                " rnd_double(10) f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64", "f32", "f64")
                .withArithmeticOperator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64", "f32", "f64")
                .withAnyOf(" = ", " <> ")
                .withAnyOf("null");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    @Test
    public void testIntFloatColumnComparisonIgnoreNull() throws Exception {
        final String ddl = "create table x as " +
                "(select timestamp_sequence(400000000000, 500000000) as k," +
                " rnd_byte(-10, 10) i8," +
                " rnd_short(-10, 10) i16," +
                " rnd_int(-10, 10, 10) i32," +
                " rnd_long(-10, 10, 10) i64," +
                " rnd_float(10) f32," +
                " rnd_double(10) f64 " +
                " from long_sequence(" + N_SIMD_WITH_TAIL + ")) timestamp(k) partition by DAY";
        FilterGenerator gen = new FilterGenerator()
                .withOptionalNegation().withAnyOf("i8", "i16", "i32", "i64")
                .withComparisonOperator()
                .withOptionalNegation().withAnyOf("f32", "f64");
        assertGeneratedQuery("select * from x", ddl, gen);
    }

    private void assertGeneratedQuery(CharSequence baseQuery, CharSequence ddl, FilterGenerator gen) throws Exception {
        final boolean forceScalarJit = jitMode == JitMode.SCALAR;
        assertMemoryLeak(() -> {
            if (ddl != null) {
                compiler.compile(ddl, sqlExecutionContext);
            }

            long maxSize = 0;
            List<String> filters = gen.generate();
            LOG.info().$("generated ").$(filters.size()).$(" filter expressions for base query: ").$(baseQuery).$();
            Assert.assertTrue(filters.size() > 0);
            for (String filter : filters) {
                long size = runQuery(baseQuery + " where " + filter, forceScalarJit);
                maxSize = Math.max(maxSize, size);

                TestUtils.assertEquals("result mismatch for filter: " + filter, sink, jitSink);
            }
            Assert.assertTrue(maxSize > 0);
        });
    }

    private void assertQuery(CharSequence query, CharSequence ddl) throws Exception {
        final boolean forceScalarJit = jitMode == JitMode.SCALAR;
        assertMemoryLeak(() -> {
            if (ddl != null) {
                compiler.compile(ddl, sqlExecutionContext);
            }

            runQuery(query, forceScalarJit);

            TestUtils.assertEquals(sink, jitSink);
        });
    }

    private long runQuery(CharSequence query, boolean forceScalarJit) throws SqlException {
        long resultSize;

        sqlExecutionContext.setJitMode(SqlExecutionContext.JIT_MODE_DISABLED);
        CompiledQuery cc = compiler.compile(query, sqlExecutionContext);
        RecordCursorFactory factory = cc.getRecordCursorFactory();
        Assert.assertFalse("JIT was enabled for query: " + query, factory instanceof CompiledFilterRecordCursorFactory);
        try (CountingRecordCursor cursor = new CountingRecordCursor(factory.getCursor(sqlExecutionContext))) {
            TestUtils.printCursor(cursor, factory.getMetadata(), true, sink, printer);
            resultSize = cursor.count();
        } finally {
            Misc.free(factory);
        }

        int jitMode = forceScalarJit ? SqlExecutionContext.JIT_MODE_FORCE_SCALAR : SqlExecutionContext.JIT_MODE_ENABLED;
        sqlExecutionContext.setJitMode(jitMode);
        cc = compiler.compile(query, sqlExecutionContext);
        factory = cc.getRecordCursorFactory();
        Assert.assertTrue("JIT was not enabled for query: " + query, factory instanceof CompiledFilterRecordCursorFactory);
        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
            TestUtils.printCursor(cursor, factory.getMetadata(), true, jitSink, printer);
        } finally {
            Misc.free(factory);
        }

        return resultSize;
    }

    private enum JitMode {
        SCALAR, VECTORIZED
    }

    private static class FilterGenerator {

        private static final String[] OPTIONAL_NEGATION = new String[]{"", "-"};
        private static final String[] OPTIONAL_NOT = new String[]{"", " not "};
        private static final String[] COMPARISON_OPERATORS = new String[]{" = ", " != ", " > ", " >= ", " < ", " <= "};
        private static final String[] ARITHMETIC_OPERATORS = new String[]{" + ", " - ", " * ", " / "};
        private static final String[] BOOLEAN_OPERATORS = new String[]{" and ", " or "};

        private final List<String[]> filterParts = new ArrayList<>();

        public FilterGenerator withAnyOf(String... parts) {
            filterParts.add(parts);
            return this;
        }

        public FilterGenerator withOptionalNegation() {
            filterParts.add(OPTIONAL_NEGATION);
            return this;
        }

        public FilterGenerator withOptionalNot() {
            filterParts.add(OPTIONAL_NOT);
            return this;
        }

        public FilterGenerator withComparisonOperator() {
            filterParts.add(COMPARISON_OPERATORS);
            return this;
        }

        public FilterGenerator withArithmeticOperator() {
            filterParts.add(ARITHMETIC_OPERATORS);
            return this;
        }

        public FilterGenerator withBooleanOperator() {
            filterParts.add(BOOLEAN_OPERATORS);
            return this;
        }

        /**
         * Generates a simple cartesian product of the given filter expression parts.
         *
         * The algorithm originates from Generating All n-tuple, of The Art Of Computer
         * Programming by Knuth.
         */
        public List<String> generate() {
            if (filterParts.size() == 0) {
                return Collections.emptyList();
            }

            int combinations = 1;
            for (String[] parts : filterParts) {
                combinations *= parts.length;
            }

            final List<String> filters = new ArrayList<>();
            final StringBuilder sb = new StringBuilder();

            for (int i = 0; i < combinations; i++) {
                int j = 1;
                for (String[] parts : filterParts) {
                    sb.append(parts[(i / j) % parts.length]);
                    j *= parts.length;
                }
                filters.add(sb.toString());
                sb.setLength(0);
            }
            return filters;
        }
    }

    private static class CountingRecordCursor implements RecordCursor {

        private final RecordCursor delegate;
        private long count;

        public CountingRecordCursor(RecordCursor delegate) {
            this.delegate = delegate;
        }

        public long count() {
            return count;
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public Record getRecord() {
            return delegate.getRecord();
        }

        @Override
        public SymbolTable getSymbolTable(int columnIndex) {
            return delegate.getSymbolTable(columnIndex);
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = delegate.hasNext();
            if (hasNext) {
                count++;
            }
            return hasNext;
        }

        @Override
        public Record getRecordB() {
            return delegate.getRecordB();
        }

        @Override
        public void recordAt(Record record, long atRowId) {
            delegate.recordAt(record, atRowId);
        }

        @Override
        public void toTop() {
            delegate.toTop();
        }

        @Override
        public long size() {
            return delegate.size();
        }
    }

    // TODO: test the following
    // geohash (+null), char, boolean, date, timestamp
    // filter on subquery
    // interval and filter
    // wrong type expression a+b
    // join
    // latest by
}
