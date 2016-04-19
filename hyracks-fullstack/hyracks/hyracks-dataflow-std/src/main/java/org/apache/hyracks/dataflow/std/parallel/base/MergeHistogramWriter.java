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

import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.IBinaryComparator;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.data.std.api.AbstractPointable;
import org.apache.hyracks.dataflow.std.parallel.histogram.structures.DTStreamingHistogram;

/**
 * @author michael
 */
public class MergeHistogramWriter extends QuantileHistogramWriter {

    /**
     * @param ctx
     * @param sampleFields
     * @param sampleBasis
     * @param comparators
     * @param inRecordDesc
     * @param outRecordDesc
     * @param writer
     * @param local
     * @throws HyracksDataException
     */
    public MergeHistogramWriter(IHyracksTaskContext ctx, int[] sampleFields, int sampleBasis,
            IBinaryComparator[] comparators, RecordDescriptor inRecordDesc, RecordDescriptor outRecordDesc,
            IFrameWriter writer, boolean local) throws HyracksDataException {
        super(ctx, sampleFields, sampleBasis, comparators, inRecordDesc, outRecordDesc, writer, local);
        // TODO Auto-generated constructor stub
    }

    /**
     * @param ctx
     * @param sampleFields
     * @param sampleBasis
     * @param comparators
     * @param inRecordDesc
     * @param outRecordDesc
     * @param writer
     * @throws HyracksDataException
     */
    public MergeHistogramWriter(IHyracksTaskContext ctx, int[] sampleFields, int sampleBasis,
            IBinaryComparator[] comparators, RecordDescriptor inRecordDesc, RecordDescriptor outRecordDesc,
            IFrameWriter writer) throws HyracksDataException {
        super(ctx, sampleFields, sampleBasis, comparators, inRecordDesc, outRecordDesc, writer);
        // TODO Auto-generated constructor stub
    }

    @Override
    public void open() throws HyracksDataException {
        super.open();
        switch (type) {
            case STREAMING_NUMERIC:
                ((DTStreamingHistogram<AbstractPointable>) histogram).allocate(sampleBasis, DEFAULT_ELASTIC, false);
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
            histogram.appendItem(
                    (AbstractPointable) getSampledField(tRef.getFieldData(sampleFields[0]),
                            tRef.getFieldStart(sampleFields[0])),
                    getSampledCount(tRef.getFieldData(tRef.getFieldCount() - 1),
                            tRef.getFieldStart(tRef.getFieldCount() - 1)));
        }
    }
}
