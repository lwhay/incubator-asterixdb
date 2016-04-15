/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.tests.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;

import junit.framework.TestCase;

import org.apache.hyracks.data.std.primitive.DoublePointable;
import org.apache.hyracks.dataflow.std.parallel.IHistogram;
import org.apache.hyracks.dataflow.std.parallel.histogram.structures.DTStreamingHistogram;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author michael
 */
public class SampleAlgorithmTests extends TestCase {
    private static final Logger LOGGER = Logger.getLogger(SampleAlgorithmTests.class.getName());
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final static int ZIPFAN_COLUMN = 0;
    private/*final static*/int PARTITION_CARD = 7;

    private final static String zipFanFilePath = "data/skew/zipfan.tbl";

    @Before
    public void setUpStreams() {
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    public static <K, V extends Comparable<? super V>> Map<K, V> sortByValue(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new LinkedList<>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<K, V>>() {
            @Override
            public int compare(Map.Entry<K, V> o1, Map.Entry<K, V> o2) {
                return (o1.getValue()).compareTo(o2.getValue());
            }
        });

        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static <K extends Comparable<? super K>, V> Map<K, V> sortByKey(Map<K, V> map) {
        List<Map.Entry<K, V>> list = new LinkedList<>(map.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<K, V>>() {
            @Override
            public int compare(Map.Entry<K, V> o1, Map.Entry<K, V> o2) {
                return (o1.getKey()).compareTo(o2.getKey());
            }
        });

        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : list) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Test
    public void testZipfanRandom() throws Exception {
        /*for (int part = 20; part < 1025; part *= 200) {
            for (int scale = 1; scale < 3; scale++) {
                PARTITION_CARD = part;*/
        DTStreamingHistogram<DoublePointable> dth = new DTStreamingHistogram<DoublePointable>(
                IHistogram.FieldType.DOUBLE, true);
        dth.initialize();
        dth.allocate(PARTITION_CARD, 16, true);
        Map<Integer, DoublePointable> randString = new HashMap<Integer, DoublePointable>();
        BufferedReader br = new BufferedReader(new FileReader(zipFanFilePath));
        String line = null;
        while (null != (line = br.readLine())) {
            String[] fields = line.split("\t");
            DoublePointable key = (DoublePointable) DoublePointable.FACTORY.createPointable();
            byte[] buf = new byte[Double.SIZE / Byte.SIZE];
            key.set(buf, 0, Double.SIZE / Byte.SIZE);
            String strD = fields[ZIPFAN_COLUMN];
            double d = Double.parseDouble(strD);
            key.setDouble(d);
            randString.put((int) (Math.random() * 1000000000), key);
        }
        randString = sortByKey(randString);

        long begin = System.currentTimeMillis();
        for (Entry<Integer, DoublePointable> entry : randString.entrySet())
            dth.addItem(entry.getValue());
        List<Entry<DoublePointable, Integer>> quantiles = dth.generate(true);
        String quantileOut = "";
        for (int i = 0; i < quantiles.size(); i++) {
            quantileOut += ("<" + quantiles.get(i).getKey().getDouble() + ", " + quantiles.get(i).getValue() + ">\n");
        }
        LOGGER.info(quantileOut);
        br.close();
        dth.countReset();
        long end = System.currentTimeMillis();
        LOGGER.info("Eclipse: " + (end - begin));
        LOGGER.info("Verification");
        br = new BufferedReader(new FileReader(zipFanFilePath));
        line = null;
        while (null != (line = br.readLine())) {
            String[] fields = line.split("\t");
            DoublePointable key = (DoublePointable) DoublePointable.FACTORY.createPointable();
            byte[] buf = new byte[Double.SIZE / Byte.SIZE];
            key.set(buf, 0, Double.SIZE / Byte.SIZE);
            String strD = fields[ZIPFAN_COLUMN];
            double d = Double.parseDouble(strD);
            key.setDouble(d);
            dth.countItem(key);
        }
        quantiles = dth.generate(true);
        int maximal = 0;
        int minimal = Integer.MAX_VALUE;
        quantileOut = "";
        for (int i = 0; i < quantiles.size(); i++) {
            quantileOut += ("<" + i + ", " + quantiles.get(i).getKey().getDouble() + ", " + quantiles.get(i).getValue() + ">\n");
            int current = quantiles.get(i).getValue();
            if (current > maximal)
                maximal = current;
            if (current < minimal)
                minimal = current;
        }
        LOGGER.info(quantileOut);
    }

    @After
    public void cleanUpStreams() {
        System.setOut(null);
        System.setErr(null);
    }
}
