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
package org.apache.hyracks.dataflow.std.parallel.base;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map.Entry;
import java.util.logging.Logger;

import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.AbstractPointable;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.DoublePointable;
import org.apache.hyracks.data.std.primitive.FloatPointable;
import org.apache.hyracks.data.std.primitive.IntegerPointable;
import org.apache.hyracks.data.std.primitive.LongPointable;
import org.apache.hyracks.data.std.primitive.ShortPointable;
import org.apache.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import org.apache.hyracks.dataflow.common.data.marshalling.DoubleSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.FloatSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.Integer64SerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.IntegerSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.ShortSerializerDeserializer;
import org.apache.hyracks.dataflow.common.data.marshalling.UTF8StringSerializerDeserializer;
import org.apache.hyracks.dataflow.std.parallel.IHistogram;
import org.apache.hyracks.dataflow.std.parallel.IHistogram.FieldType;
import org.apache.hyracks.dataflow.std.parallel.histogram.structures.DTStreamingHistogram;

/**
 * @author michael
 */
public class QuantileHistogramWriter extends AbstractHistogramWriter {
    private static final Logger LOGGER = Logger.getLogger(QuantileHistogramWriter.class.getName());

    protected static final boolean DEBUG = true;
    protected final IHistogram.HistogramType type;
    protected final int[] sampleFields;
    protected final int sampleBasis;
    protected final RecordDescriptor inDesc;

    protected static final int DEFAULT_ELASTIC = 4;
    protected static final double DEFAULT_MU = 0.2;

    protected FrameTupleReference tRef = null;

    //private final IHistogram merging;
    protected final IHistogram<AbstractPointable> histogram;

    public QuantileHistogramWriter(IHyracksTaskContext ctx, int[] sampleFields, int sampleBasis,
            IBinaryComparator[] comparators, RecordDescriptor inRecordDesc, RecordDescriptor outRecordDesc,
            IFrameWriter writer, boolean local) throws HyracksDataException {
        super(ctx, sampleFields, sampleBasis, comparators, inRecordDesc, outRecordDesc, writer);
        this.sampleFields = sampleFields;
        this.sampleBasis = sampleBasis;
        this.inDesc = inRecordDesc;
        this.partialOrLocal = local;
        this.tRef = new FrameTupleReference();
        // It supports the one-dimensional numeric histogram, will includes the string and multi-dimensional numeric fields.
        if (inDesc.getFields()[sampleFields[0]] instanceof ShortSerializerDeserializer) {
            this.histogram = new DTStreamingHistogram<AbstractPointable>(FieldType.SHORT, true);
            type = IHistogram.HistogramType.STREAMING_NUMERIC;
        } else if (inDesc.getFields()[sampleFields[0]] instanceof IntegerSerializerDeserializer) {
            this.histogram = new DTStreamingHistogram<AbstractPointable>(FieldType.INT, true);
            type = IHistogram.HistogramType.STREAMING_NUMERIC;
        } else if (inDesc.getFields()[sampleFields[0]] instanceof Integer64SerializerDeserializer) {
            this.histogram = new DTStreamingHistogram<AbstractPointable>(FieldType.LONG, true);
            type = IHistogram.HistogramType.STREAMING_NUMERIC;
        } else if (inDesc.getFields()[sampleFields[0]] instanceof FloatSerializerDeserializer) {
            this.histogram = new DTStreamingHistogram<AbstractPointable>(FieldType.FLOAT, true);
            type = IHistogram.HistogramType.STREAMING_NUMERIC;
        } else if (inDesc.getFields()[sampleFields[0]] instanceof DoubleSerializerDeserializer) {
            this.histogram = new DTStreamingHistogram<AbstractPointable>(FieldType.DOUBLE, true);
            type = IHistogram.HistogramType.STREAMING_NUMERIC;
        } else if (inDesc.getFields()[sampleFields[0]] instanceof UTF8StringSerializerDeserializer) {
            throw new HyracksDataException("Will support the field: " + inDesc.getFields()[sampleFields[0]]);
        } else {
            throw new HyracksDataException("Invalid type: " + inDesc.getFields()[sampleFields[0]]);
        }
    }

    public QuantileHistogramWriter(IHyracksTaskContext ctx, int[] sampleFields, int sampleBasis,
            IBinaryComparator[] comparators, RecordDescriptor inRecordDesc, RecordDescriptor outRecordDesc,
            IFrameWriter writer) throws HyracksDataException {
        this(ctx, sampleFields, sampleBasis, comparators, inRecordDesc, outRecordDesc, writer, false);
    }

    protected IPointable getSampledField(byte[] data, int fStart) {
        IPointable ip = null;
        switch (histogram.getType()) {
            case SHORT:
                ip = ShortPointable.FACTORY.createPointable();
                ip.set(data, fStart, ShortPointable.TYPE_TRAITS.getFixedLength());
                break;
            case INT:
                ip = IntegerPointable.FACTORY.createPointable();
                ip.set(data, fStart, IntegerPointable.TYPE_TRAITS.getFixedLength());
                break;
            case FLOAT:
                ip = FloatPointable.FACTORY.createPointable();
                ip.set(data, fStart, FloatPointable.TYPE_TRAITS.getFixedLength());
                break;
            case DOUBLE:
                ip = DoublePointable.FACTORY.createPointable();
                ip.set(data, fStart, DoublePointable.TYPE_TRAITS.getFixedLength());
                break;
            case LONG:
                ip = LongPointable.FACTORY.createPointable();
                ip.set(data, fStart, LongPointable.TYPE_TRAITS.getFixedLength());
                break;
            default:
                break;
        }
        return ip;
    }

    protected int getSampledCount(byte[] data, int fStart) {
        return IntegerPointable.getInteger(data, fStart);
    }

    @Override
    public void open() throws HyracksDataException {
        super.open();
        switch (type) {
            case STREAMING_NUMERIC:
                ((DTStreamingHistogram<AbstractPointable>) histogram).allocate(sampleBasis, DEFAULT_ELASTIC,
                        !partialOrLocal);
                break;
            case TERNARY_UTF8STRING:
                break;
            default:
                break;
        }
    }

    @Override
    public void nextFrame(ByteBuffer buffer) throws HyracksDataException {
        inFrameAccessor.reset(buffer);
        int nTuples = inFrameAccessor.getTupleCount();
        for (int i = 0; i < nTuples; i++) {
            tRef.reset(inFrameAccessor, i);
            // Currently, we support the one-dimensional histogram.
            // The numeric histogram can be supported by concatenating the homogeneous fields.
            /*for (int j = 0; j < sampleFields.length; j++) {*/
            histogram.addItem((AbstractPointable) getSampledField(tRef.getFieldData(sampleFields[0]),
                    tRef.getFieldStart(sampleFields[0])));
            if (!partialOrLocal) {
                IntegerPointable ip = (IntegerPointable) IntegerPointable.FACTORY.createPointable();
                ip.set(tRef.getFieldData(tRef.getFieldCount() - 1), tRef.getFieldStart(tRef.getFieldCount() - 1),
                        IntegerPointable.TYPE_TRAITS.getFixedLength());
            }
            /*}*/
        }
    }

    @Override
    public void fail() throws HyracksDataException {
        isFailed = true;
        appenderWrapper.fail();
    }

    private void append1() throws HyracksDataException {
        List<Entry<AbstractPointable, Integer>> pairs = histogram.generate(!partialOrLocal);
        int offset = 0;
        for (int i = 0; i < pairs.size(); i++) {
            if (DEBUG && !partialOrLocal) {
                String quantile = "";
                if (pairs.get(i).getKey() instanceof DoublePointable) {
                    quantile += ((DoublePointable) pairs.get(i).getKey()).getDouble() + " <-> "
                            + pairs.get(i).getValue();
                } else {
                    quantile += pairs.get(i).getKey().toString() + " <-> " + pairs.get(i).getValue();
                }
                LOGGER.info(quantile);
            }
            Entry<AbstractPointable, Integer> entry = pairs.get(i);
            appenderWrapper.append(entry.getKey().getByteArray(), offset, entry.getKey().getLength());
            //offset += entry.getKey().getLength();
            IntegerPointable count = (IntegerPointable) IntegerPointable.FACTORY.createPointable();
            byte[] buf = new byte[IntegerPointable.TYPE_TRAITS.getFixedLength()];
            count.set(buf, 0, IntegerPointable.TYPE_TRAITS.getFixedLength());
            count.setInteger(pairs.get(i).getValue());
            appenderWrapper.append(count.getByteArray(), offset, count.getLength());
            //offset += count.getLength();
        }
        //appenderWrapper.write();
    }

    private void append() throws HyracksDataException {
        List<Entry<AbstractPointable, Integer>> pairs = histogram.generate(!partialOrLocal);
        for (int i = 0; i < pairs.size(); i++) {
            tupleBuilder.reset();
            Entry<AbstractPointable, Integer> entry = pairs.get(i);
            tupleBuilder.addField(entry.getKey().getByteArray(), entry.getKey().getStartOffset(), entry.getKey()
                    .getLength());
            IntegerPointable count = (IntegerPointable) IntegerPointable.FACTORY.createPointable();
            byte[] buf = new byte[IntegerPointable.TYPE_TRAITS.getFixedLength()];
            count.set(buf, 0, IntegerPointable.TYPE_TRAITS.getFixedLength());
            count.setInteger(pairs.get(i).getValue());
            tupleBuilder.addField(count.getByteArray(), count.getStartOffset(), count.getLength());
            appenderWrapper.appendSkipEmptyField(tupleBuilder.getFieldEndOffsets(), tupleBuilder.getByteArray(), 0,
                    tupleBuilder.getSize());
        }

    }

    @Override
    public void close() throws HyracksDataException {
        append();
        if (!isFailed /*&& !isFirst*/) {
            appenderWrapper.write();
        }
        appenderWrapper.close();
    }
}
