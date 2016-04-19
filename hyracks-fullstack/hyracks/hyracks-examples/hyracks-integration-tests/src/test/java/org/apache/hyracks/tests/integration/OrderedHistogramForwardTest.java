/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hyracks.tests.integration;

import java.io.File;

import org.apache.hyracks.api.constraints.PartitionConstraintHelper;
import org.apache.hyracks.api.dataflow.IOperatorDescriptor;
import org.apache.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import org.apache.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import org.apache.hyracks.api.dataflow.value.ISerializerDeserializer;
import org.apache.hyracks.api.dataflow.value.ITuplePartitionComputerFactory;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.dataset.ResultSetId;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.job.JobSpecification;
import org.apache.hyracks.data.std.accessors.PointableBinaryComparatorFactory;
import org.apache.hyracks.data.std.primitive.UTF8StringPointable;
import org.apache.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.normalizers.UTF8StringNormalizedKeyComputerFactory;
import org.apache.hyracks.dataflow.common.data.parsers.IValueParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.IntegerParserFactory;
import org.apache.hyracks.dataflow.common.data.parsers.UTF8StringParserFactory;
import org.apache.hyracks.dataflow.common.data.partition.range.FieldRangePartitionComputerFactory;
import org.apache.hyracks.dataflow.common.data.partition.range.IRangeMap;
import org.apache.hyracks.dataflow.common.data.partition.range.RangeMap;
import org.apache.hyracks.dataflow.std.connectors.MToNPartitioningConnectorDescriptor;
import org.apache.hyracks.dataflow.std.connectors.MToNPartitioningMergingConnectorDescriptor;
import org.apache.hyracks.dataflow.std.connectors.MToNReplicatingConnectorDescriptor;
import org.apache.hyracks.dataflow.std.connectors.OneToOneConnectorDescriptor;
import org.apache.hyracks.dataflow.std.file.ConstantFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.DelimitedDataTupleParserFactory;
import org.apache.hyracks.dataflow.std.file.FileScanOperatorDescriptor;
import org.apache.hyracks.dataflow.std.file.FileSplit;
import org.apache.hyracks.dataflow.std.file.IFileSplitProvider;
import org.apache.hyracks.dataflow.std.file.LineFileWriteOperatorDescriptor;
import org.apache.hyracks.dataflow.std.group.IFieldAggregateDescriptorFactory;
import org.apache.hyracks.dataflow.std.group.aggregators.IntSumFieldAggregatorFactory;
import org.apache.hyracks.dataflow.std.group.aggregators.MultiFieldsAggregatorFactory;
import org.apache.hyracks.dataflow.std.parallel.HistogramAlgorithm;
import org.apache.hyracks.dataflow.std.parallel.base.FieldRangePartitionDelayComputerFactory;
import org.apache.hyracks.dataflow.std.parallel.histogram.AbstractHistogramOperatorDescriptor;
import org.apache.hyracks.dataflow.std.parallel.histogram.ForwardOperatorDescriptor;
import org.apache.hyracks.dataflow.std.parallel.histogram.LocalHistogramOperatorDescriptor;
import org.apache.hyracks.dataflow.std.parallel.histogram.MergeHistogramOperatorDescriptor;
import org.apache.hyracks.dataflow.std.sort.ExternalSortOperatorDescriptor;
import org.junit.Test;

/**
 * @author michael
 */
public class OrderedHistogramForwardTest extends AbstractIntegrationTest {
    private static final int balance_factor = 10;
    private static final int rangeMergeArity = 1;
    private static final int outputFiles = 2;
    private static int[] sampleFields = new int[] { 2 };
    private static int[] normalFields = new int[] { 0 };
    private IBinaryComparatorFactory[] sampleCmpFactories = new IBinaryComparatorFactory[] { PointableBinaryComparatorFactory
            .of(UTF8StringPointable.FACTORY) };
    private INormalizedKeyComputerFactory sampleKeyFactories = new UTF8StringNormalizedKeyComputerFactory();
    MultiFieldsAggregatorFactory sampleAggFactory = new MultiFieldsAggregatorFactory(
            new IFieldAggregateDescriptorFactory[] { new IntSumFieldAggregatorFactory(1, true) });

    @Test
    public void byPassHistogramSort() throws Exception {
        JobSpecification spec = new JobSpecification();
        File[] outputFile = new File[outputFiles];
        for (int i = 0; i < outputFiles; i++) {
            outputFile[i] = File.createTempFile("output-" + i + "-", null, new File("data"));
        }
        FileSplit[] custSplits = new FileSplit[] {
                new FileSplit(NC1_ID, new FileReference(new File("data/tpch0.001/customer-part1.tbl"))),
                new FileSplit(NC2_ID, new FileReference(new File("data/tpch0.001/customer-part2.tbl"))) };
        IFileSplitProvider custSplitsProvider = new ConstantFileSplitProvider(custSplits);
        RecordDescriptor custDesc = new RecordDescriptor(new ISerializerDeserializer[] {
                IntegerSerializerDeserializer.INSTANCE, new UTF8StringSerializerDeserializer(),
                new UTF8StringSerializerDeserializer(), IntegerSerializerDeserializer.INSTANCE,
                new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer(),
                new UTF8StringSerializerDeserializer(), new UTF8StringSerializerDeserializer() });

        FileScanOperatorDescriptor custScanner = new FileScanOperatorDescriptor(spec, custSplitsProvider,
                new DelimitedDataTupleParserFactory(new IValueParserFactory[] { IntegerParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        IntegerParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE, UTF8StringParserFactory.INSTANCE,
                        UTF8StringParserFactory.INSTANCE }, '|'), custDesc);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, custScanner, NC1_ID, NC2_ID);

        AbstractHistogramOperatorDescriptor materSampleCust = new LocalHistogramOperatorDescriptor(spec, 4,
                sampleFields, 2 * balance_factor, custDesc, sampleCmpFactories, null, 1, new boolean[] { true });
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, materSampleCust, NC1_ID, NC2_ID);
        spec.connect(new OneToOneConnectorDescriptor(spec), custScanner, 0, materSampleCust, 0);

        RecordDescriptor outputSamp = new RecordDescriptor(new ISerializerDeserializer[] {
                IntegerSerializerDeserializer.INSTANCE, IntegerSerializerDeserializer.INSTANCE });

        byte[] byteRange = new byte[rangeMergeArity];
        int[] offRange = new int[rangeMergeArity];
        for (int i = 0; i < rangeMergeArity; i++) {
            byteRange[i] = Byte.parseByte(String.valueOf(i * (150 / rangeMergeArity + 1)));
            offRange[i] = i;
        }

        IRangeMap rangeMap = new RangeMap(normalFields.length, byteRange, offRange);

        ITuplePartitionComputerFactory tpcf = new FieldRangePartitionComputerFactory(normalFields, sampleCmpFactories,
                rangeMap);

        IOperatorDescriptor mergeSampleCust = new MergeHistogramOperatorDescriptor(spec, 4, normalFields, outputSamp, 4,
                sampleKeyFactories, sampleCmpFactories, HistogramAlgorithm.ORDERED_HISTOGRAM, false);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, mergeSampleCust, NC1_ID, NC2_ID);
        spec.connect(new MToNPartitioningMergingConnectorDescriptor(spec, tpcf, normalFields, sampleCmpFactories,
                sampleKeyFactories, false), materSampleCust, 0, mergeSampleCust, 0);

        ITuplePartitionComputerFactory tpc = new FieldRangePartitionDelayComputerFactory(sampleFields,
                sampleCmpFactories);

        RecordDescriptor outputRec = custDesc;
        IOperatorDescriptor forward = new ForwardOperatorDescriptor(spec, 4, normalFields, outputSamp,
                outputRec, sampleCmpFactories);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, forward, NC1_ID, NC2_ID);
        spec.connect(new MToNReplicatingConnectorDescriptor(spec), mergeSampleCust, 0, forward, 0);
        spec.connect(new OneToOneConnectorDescriptor(spec), materSampleCust, 1, forward, 1);

        ExternalSortOperatorDescriptor sorterCust = new ExternalSortOperatorDescriptor(spec, 4, sampleFields,
                sampleCmpFactories, custDesc);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, sorterCust, NC1_ID, NC2_ID);
        spec.connect(new MToNPartitioningConnectorDescriptor(spec, tpc), forward, 0, sorterCust, 0);

        ResultSetId rsId = new ResultSetId(1);
        spec.addResultSetId(rsId);

        FileSplit[] files = new FileSplit[outputFiles];
        for (int i = 0; i < outputFiles; i++) {
            files[i] = new FileSplit((0 == i % 2) ? NC1_ID : NC2_ID, new FileReference(outputFile[i]));
        }

        IOperatorDescriptor printer = new LineFileWriteOperatorDescriptor(spec, files);
        PartitionConstraintHelper.addAbsoluteLocationConstraint(spec, printer, NC1_ID, NC2_ID);
        spec.connect(new OneToOneConnectorDescriptor(spec), sorterCust, 0, printer, 0);

        spec.addRoot(printer);
        runTest(spec);
    }
}
