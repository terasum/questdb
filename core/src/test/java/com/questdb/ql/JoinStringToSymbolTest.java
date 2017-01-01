/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql;

import com.questdb.JournalWriter;
import com.questdb.factory.ReaderFactory;
import com.questdb.factory.WriterFactory;
import com.questdb.factory.configuration.JournalConfigurationBuilder;
import com.questdb.model.Album;
import com.questdb.model.Band;
import com.questdb.ql.impl.AllRowSource;
import com.questdb.ql.impl.JournalPartitionSource;
import com.questdb.ql.impl.JournalRecordSource;
import com.questdb.ql.impl.join.CrossJoinRecordSource;
import com.questdb.test.tools.TestUtils;
import com.questdb.test.tools.TheFactory;
import com.questdb.txt.RecordSourcePrinter;
import com.questdb.txt.sink.StringSink;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JoinStringToSymbolTest {
    @Rule
    public final TheFactory theFactory = new TheFactory(new JournalConfigurationBuilder() {{
        $(Band.class)
                .$sym("name").index()
                .$sym("type")
                .$bin("image")
                .$ts()
        ;

        $(Album.class)
                .$str("band").index()
                .$sym("name").index()
                .$ts("releaseDate");

    }});

    private JournalWriter<Band> bw;
    private JournalWriter<Album> aw;

    public ReaderFactory getReaderFactory() {
        return theFactory.getReaderFactory();
    }

    public WriterFactory getWriterFactory() {
        return theFactory.getWriterFactory();
    }

    @Before
    public void setUp() throws Exception {
        bw = getWriterFactory().writer(Band.class);
        aw = getWriterFactory().writer(Album.class);
    }

    @After
    public void tearDown() throws Exception {
        aw.close();
        bw.close();
    }

    @Test
    public void testCrossJoin() throws Exception {
        bw.append(new Band().setName("band1").setType("rock").setUrl("http://band1.com"));
        bw.append(new Band().setName("band2").setType("hiphop").setUrl("http://band2.com"));
        bw.append(new Band().setName("band3").setType("jazz").setUrl("http://band3.com"));
        bw.append(new Band().setName("band1").setType("jazz").setUrl("http://new.band1.com"));

        bw.commit();

        aw.append(new Album().setName("album X").setBand("band1").setGenre("pop"));
        aw.append(new Album().setName("album BZ").setBand("band1").setGenre("rock"));
        aw.append(new Album().setName("album Y").setBand("band3").setGenre("metal"));

        aw.commit();

        StringSink sink = new StringSink();
        RecordSourcePrinter p = new RecordSourcePrinter(sink);
        try (RecordSource rs = new CrossJoinRecordSource(new JournalRecordSource(
                new JournalPartitionSource(aw.getMetadata(), false), new AllRowSource()), new JournalRecordSource(
                new JournalPartitionSource(bw.getMetadata(), false), new AllRowSource()))) {
            p.print(rs, theFactory.getCachingReaderFactory());
        }

        final String expected = "band1\talbum X\tpop\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://band1.com\trock\t\n" +
                "band1\talbum X\tpop\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband2\thttp://band2.com\thiphop\t\n" +
                "band1\talbum X\tpop\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband3\thttp://band3.com\tjazz\t\n" +
                "band1\talbum X\tpop\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://new.band1.com\tjazz\t\n" +
                "band1\talbum BZ\trock\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://band1.com\trock\t\n" +
                "band1\talbum BZ\trock\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband2\thttp://band2.com\thiphop\t\n" +
                "band1\talbum BZ\trock\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband3\thttp://band3.com\tjazz\t\n" +
                "band1\talbum BZ\trock\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://new.band1.com\tjazz\t\n" +
                "band3\talbum Y\tmetal\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://band1.com\trock\t\n" +
                "band3\talbum Y\tmetal\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband2\thttp://band2.com\thiphop\t\n" +
                "band3\talbum Y\tmetal\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband3\thttp://band3.com\tjazz\t\n" +
                "band3\talbum Y\tmetal\t1970-01-01T00:00:00.000Z\t1970-01-01T00:00:00.000Z\tband1\thttp://new.band1.com\tjazz\t\n";

        TestUtils.assertEquals(expected, sink);
    }
}
