/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.job.hadoop.cube;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mrunit.mapreduce.ReduceDriver;
import org.apache.hadoop.mrunit.types.Pair;
import org.apache.kylin.common.util.LocalFileMetadataTestCase;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.kv.RowConstants;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.job.constant.BatchConstants;
import org.apache.kylin.metadata.measure.MeasureCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CubeReducerTest extends LocalFileMetadataTestCase {

    ReduceDriver<Text, Text, Text, Text> reduceDriver;

    ByteBuffer buf = ByteBuffer.allocate(RowConstants.ROWVALUE_BUFFER_SIZE);

    @Before
    public void setUp() throws Exception {
        createTestMetadata();

        // hack for distributed cache
        FileUtils.deleteDirectory(new File("../job/meta"));
        FileUtils.copyDirectory(new File(getTestConfig().getMetadataUrl()), new File("../job/meta"));

        CuboidReducer reducer = new CuboidReducer();
        reduceDriver = ReduceDriver.newReduceDriver(reducer);
    }

    @After
    public void after() throws Exception {
        cleanupTestMetadata();
        FileUtils.deleteDirectory(new File("../job/meta"));
    }

    @Test
    public void testReducer() throws Exception {

        reduceDriver.getConfiguration().set(BatchConstants.CFG_CUBE_NAME, "test_kylin_cube_with_slr_ready");

        CubeDesc cubeDesc = CubeManager.getInstance(getTestConfig()).getCube("test_kylin_cube_with_slr_ready").getDescriptor();
        MeasureCodec codec = new MeasureCodec(cubeDesc.getMeasures());

        Text key1 = new Text("72010ustech");
        List<Text> values1 = new ArrayList<Text>();
        values1.add(newValueText(codec, "15.09", "15.09", "15.09", 1,22));
        values1.add(newValueText(codec, "20.34", "20.34", "20.34", 1,23));
        values1.add(newValueText(codec, "10", "10", "10", 1,24));

        Text key2 = new Text("1tech");
        List<Text> values2 = new ArrayList<Text>();
        values2.add(newValueText(codec, "15.09", "15.09", "15.09", 1,12));
        values2.add(newValueText(codec, "20.34", "20.34", "20.34", 1,13));

        Text key3 = new Text("0");
        List<Text> values3 = new ArrayList<Text>();
        values3.add(newValueText(codec, "146.52", "146.52", "146.52", 4,11));

        reduceDriver.withInput(key1, values1);
        reduceDriver.withInput(key2, values2);
        reduceDriver.withInput(key3, values3);

        List<Pair<Text, Text>> result = reduceDriver.run();

        Pair<Text, Text> p1 = new Pair<Text, Text>(new Text("72010ustech"), newValueText(codec, "45.43", "10", "20.34", 3,69));
        Pair<Text, Text> p2 = new Pair<Text, Text>(new Text("1tech"), newValueText(codec, "35.43", "15.09", "20.34", 2,25));
        Pair<Text, Text> p3 = new Pair<Text, Text>(new Text("0"), newValueText(codec, "146.52", "146.52", "146.52", 4,11));

        assertEquals(3, result.size());

        assertTrue(result.contains(p1));
        assertTrue(result.contains(p2));
        assertTrue(result.contains(p3));
    }

    private Text newValueText(MeasureCodec codec, String sum, String min, String max, int count,int itemcount) {
        Object[] values = new Object[] { new BigDecimal(sum), new BigDecimal(min), new BigDecimal(max), new LongWritable(count),new LongWritable(itemcount) };

        buf.clear();
        codec.encode(values, buf);

        Text t = new Text();
        t.set(buf.array(), 0, buf.position());
        return t;
    }

}
